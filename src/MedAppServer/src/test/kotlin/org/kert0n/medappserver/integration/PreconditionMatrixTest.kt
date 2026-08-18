package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Предусловие целиком: что требуется, что принимается и в каком порядке проверяется.
 *
 * Через настоящий HTTP и настоящие сервисы: разбор заголовка, порядок проверок и код ответа —
 * это поведение границы, а не сервиса, и на моках оно доказывается само собой.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreconditionMatrixTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var reservationService: ReservationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    // ── Чего не хватает и что нечитаемо ──────────────────────────────────────────

    /**
     * Молчание клиента — не согласие.
     *
     * Команда без `If-Match` не говорит, по какому состоянию её посчитали, и выполнить её
     * значит согласиться потерять чужую одновременную правку. 428 просит клиента сказать это
     * явно, а не отказывает навсегда.
     */
    @Test
    fun `команда без If-Match отвергается 428`() {
        val world = world()

        mockMvc.perform(intake(world).content(TWO_TABLETS))
            .andExpect(status().isPreconditionRequired)

        assertEquals(world.drugVersion(), dbHelper.drugVersion(world.drugId), "команда не выполнялась")
    }

    /**
     * `*` значит «лишь бы существовал» и от потерянного обновления не защищает: принять его —
     * дать способ обойти предусловие, не заметив этого. Слабый тег допускает «примерно то же
     * состояние», а здесь совпадение обязано быть точным.
     */
    @Test
    fun `нечитаемый If-Match отвергается 400`() {
        val world = world()

        listOf("*", "W/\"0\"", "abc", "0", "\"\"", "  ", "\"-1\"").forEach { malformed ->
            mockMvc.perform(intake(world).header(HttpHeaders.IF_MATCH, malformed).content(TWO_TABLETS))
                .andExpect(status().isBadRequest)
        }

        assertEquals(world.drugVersion(), dbHelper.drugVersion(world.drugId), "ни одна команда не прошла")
    }

    // ── Устаревшая и верная версия ───────────────────────────────────────────────

    /**
     * 412, а не 409: предусловие предъявлено и не выполнено.
     *
     * 409 остаётся тому, что выясняется при записи, — дублю брони и версиям из тела
     * синхронизации, которые предусловием запроса не были.
     */
    @Test
    fun `устаревшая версия отвергается 412`() {
        val world = world()
        val stale = world.drugVersion()

        mockMvc.perform(intake(world).header(HttpHeaders.IF_MATCH, tag(stale)).content(TWO_TABLETS))
            .andExpect(status().isOk)

        // Тот же тег во второй раз: клиент решал по состоянию, которого уже нет.
        mockMvc.perform(intake(world).header(HttpHeaders.IF_MATCH, tag(stale)).content(TWO_TABLETS))
            .andExpect(status().isPreconditionFailed)

        assertEquals(qty(98.0), dbHelper.drugQuantity(world.drugId), "списание прошло ровно одно")
    }

    @Test
    fun `верная версия принимается и ответ несёт новый тег`() {
        val world = world()
        val before = world.drugVersion()

        val response = mockMvc.perform(intake(world).header(HttpHeaders.IF_MATCH, tag(before)).content(TWO_TABLETS))
            .andExpect(status().isOk)
            .andReturn().response

        val newTag = assertNotNull(response.getHeader(HttpHeaders.ETAG), "ответ команды обязан нести тег")
        assertTrue(newTag != tag(before), "тег обязан отличаться: состояние изменилось")
        assertEquals(tag(dbHelper.drugVersion(world.drugId)), newTag, "тег — версия того, что в базе")
    }

    // ── Порядок проверок ─────────────────────────────────────────────────────────

    /**
     * Недоступность выясняется раньше версии.
     *
     * Иначе по коду ответа на чужую пачку различались бы «такой нет» и «есть, но версия другая»,
     * и наличие чужого препарата в чужой аптечке становилось бы наблюдаемым.
     */
    @Test
    fun `чужая упаковка отвечает 404 при любой версии`() {
        val world = world()
        val stranger = dbHelper.freshUser("eve")

        listOf(tag(world.drugVersion()), tag(999)).forEach { anyTag ->
            mockMvc.perform(
                post(ApiRoutes.intakes(world.drugId))
                    .with(jwt().jwt { it.subject(stranger.id.toString()) })
                    .header(HttpHeaders.IF_MATCH, anyTag)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TWO_TABLETS)
            )
                .andExpect(status().isNotFound)
        }
    }

    /**
     * Без заголовка ответ одинаков для всего на свете.
     *
     * 428 приходит раньше, чем сервер посмотрит на упаковку, — и раскрыть ничего не может
     * именно поэтому: чужая пачка, своя и несуществующая отвечают буквально одним и тем же.
     * Различать их начинает только запрос, дошедший до агрегата, а туда без предусловия не
     * попадают.
     */
    @Test
    fun `без If-Match чужая, своя и несуществующая упаковки неразличимы`() {
        val world = world()
        val stranger = dbHelper.freshUser("eve")

        listOf(
            world.caller() to world.drugId,
            jwt().jwt { it.subject(stranger.id.toString()) } to world.drugId,
            world.caller() to UUID.randomUUID()
        ).forEach { (caller, drugId) ->
            mockMvc.perform(
                post(ApiRoutes.intakes(drugId))
                    .with(caller)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TWO_TABLETS)
            )
                .andExpect(status().isPreconditionRequired)
        }
    }

    // ── Теги на чтении ───────────────────────────────────────────────────────────

    @Test
    fun `чтение отдаёт тег, по которому и делается команда`() {
        val world = world()

        val readTag = mockMvc.perform(get(ApiRoutes.drug(world.drugId)).with(world.caller()))
            .andExpect(status().isOk)
            .andReturn().response.getHeader(HttpHeaders.ETAG)

        mockMvc.perform(intake(world).header(HttpHeaders.IF_MATCH, readTag!!).content(TWO_TABLETS))
            .andExpect(status().isOk)
    }

    @Test
    fun `аптечка и бронь тоже отдают теги`() {
        val world = world()
        reservationService.create(world.userId, world.drugId, qty(20.0))

        mockMvc.perform(get(ApiRoutes.medKit(world.medKitId)).with(world.caller()))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ETAG, tag(dbHelper.medKitVersion(world.medKitId))))

        mockMvc.perform(get(ApiRoutes.reservation(world.drugId)).with(world.caller()))
            .andExpect(status().isOk)
            .andExpect(
                header().string(HttpHeaders.ETAG, tag(dbHelper.reservationVersion(world.userId, world.drugId)))
            )
    }

    // ── Команда, которая ничего не просит ────────────────────────────────────────

    /**
     * Пустой PATCH — не команда: менять нечего, а версию он бы сдвинул и обесценил чужие теги.
     */
    @Test
    fun `пустой PATCH отвергается 400`() {
        val world = world()

        mockMvc.perform(
            patch(ApiRoutes.drug(world.drugId))
                .with(world.caller())
                .header(HttpHeaders.IF_MATCH, tag(world.drugVersion()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
        )
            .andExpect(status().isBadRequest)

        assertEquals(world.drugVersion(), dbHelper.drugVersion(world.drugId), "версия не сдвинулась")
    }

    // ── Команды без предусловия ──────────────────────────────────────────────────

    /** Создание ничего не перезаписывает: предъявлять нечего, и требовать нечего. */
    @Test
    fun `создание упаковки предусловия не требует`() {
        val world = world()

        mockMvc.perform(
            post(ApiRoutes.drugsOf(world.medKitId))
                .with(world.caller())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"Aspirin","quantity":10.0,"quantityUnitId":"${dbHelper.unit().id}"}"""
                )
        )
            .andExpect(status().isCreated)
            .andExpect(header().exists(HttpHeaders.ETAG))
    }

    // ── Прочие команды требуют предусловия так же ────────────────────────────────

    @Test
    fun `бронь и членство требуют предусловия наравне с упаковкой`() {
        val world = world()
        reservationService.create(world.userId, world.drugId, qty(20.0))

        mockMvc.perform(
            patch(ApiRoutes.reservation(world.drugId))
                .with(world.caller())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"amount":30.0}""")
        )
            .andExpect(status().isPreconditionRequired)

        mockMvc.perform(delete(ApiRoutes.membership(world.medKitId)).with(world.caller()))
            .andExpect(status().isPreconditionRequired)

        mockMvc.perform(delete(ApiRoutes.medKit(world.medKitId)).with(world.caller()))
            .andExpect(status().isPreconditionRequired)
    }

    // ── Оснастка ─────────────────────────────────────────────────────────────────

    private fun world(): World {
        val owner = dbHelper.freshUser("alice")
        val kit = medKitService.create(owner.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        return World(owner.id, kit.id, drug.id)
    }

    private inner class World(val userId: UUID, val medKitId: UUID, val drugId: UUID) {
        fun caller() = jwt().jwt { it.subject(userId.toString()) }
        fun drugVersion(): Long = dbHelper.drugVersion(drugId)
    }

    private fun intake(world: World) = post(ApiRoutes.intakes(world.drugId))
        .with(world.caller())
        .contentType(MediaType.APPLICATION_JSON)

    private fun tag(version: Long) = "\"$version\""

    private companion object {
        const val TWO_TABLETS = """{"quantity":2.0}"""
    }
}
