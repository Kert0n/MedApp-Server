package org.kert0n.medappserver.integration

import com.sksamuel.aedile.core.Cache
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.orchestrators.IntakeOutcome
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Повторная отправка приёма не должна списывать дозу второй раз.
 *
 * Физические ограничения дают только нижнюю границу: `quantity >= 0` защищён проверками, но
 * ретрай после потерянного ответа списывал дозу заново — инвариант при этом соблюдён, а число
 * неверно, и расходится оно в опасную сторону: приложение показывает, что лекарства меньше,
 * чем в реальности, и что доза уже принята.
 *
 * Ретрай для offline-first клиента не край, а норма: запрос уходит с телефона из фоновой
 * очереди по плохой сети.
 */
@PostgresIntegrationTest
class IntakeIdempotencyTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var treatmentPlanService: TreatmentPlanService
    @Autowired private lateinit var usingService: UsingService
    @Autowired private lateinit var medKitService: MedKitService

    @Autowired
    @Qualifier("intakeResultsCache")
    private lateinit var intakeResultsCache: Cache<String, IntakeOutcome>

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        // Кеш — синглтон контекста, иначе тесты влияли бы друг на друга через общий intakeId.
        intakeResultsCache.invalidateAll()
    }

    /**
     * Именованные поля вместо `Triple`: `val (user, drug, _)` читается только со сверкой по
     * объявлению, а прочерк на месте третьего элемента ещё и скрывает, что там аптечка.
     */
    private class Fixture(val user: User, val drug: Drug, val medKit: MedKit)

    private fun prepare(stock: Double, plan: Double): Fixture {
        // Ключ уникален: класс не @Transactional (иначе HTTP-слой не увидел бы данные),
        // поэтому строки остаются между тестами и упёрлись бы в ix_users_hashed_key.
        val user = userRepository.save(User(hashedKey = "{noop}k-${UUID.randomUUID()}"))
        val medKit = medKitRepository.save(MedKit())
        medKit.users.add(user)
        user.medKits.add(medKit)
        medKitRepository.save(medKit)
        val drug = drugRepository.save(
            Drug(
                name = "Аспирин", quantity = qty(stock), quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        treatmentPlanService.create(user.id, drug.id, qty(plan))
        // Аптечка возвращается явно: Drug.medKit ленивый, а класс не @Transactional,
        // поэтому обращение к прокси вне сессии даёт LazyInitializationException.
        return Fixture(user = user, drug = drug, medKit = medKit)
    }

    private fun intake(user: User, drug: Drug, amount: Double, intakeId: UUID) =
        mockMvc.perform(
            post("/v1/using/drug/${drug.id}/intake")
                .with(jwt().jwt { it.subject(user.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantityConsumed":$amount,"intakeId":"$intakeId"}""")
        )

    @Test
    fun `повтор с тем же intakeId не списывает дозу второй раз`() {
        val fixture = prepare(stock = 10.0, plan = 5.0)
        val user = fixture.user
        val drug = fixture.drug
        val intakeId = UUID.randomUUID()

        val first = intake(user, drug, 2.0, intakeId).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val second = intake(user, drug, 2.0, intakeId).andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertEquals(first, second, "повтор должен вернуть тот же ответ, что и первый запрос")
        assertQty(8.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "остаток должен уменьшиться один раз, а не дважды")
    }

    @Test
    fun `разные intakeId списывают независимо`() {
        val fixture = prepare(stock = 10.0, plan = 5.0)
        val user = fixture.user
        val drug = fixture.drug

        intake(user, drug, 2.0, UUID.randomUUID()).andExpect(status().isOk)
        intake(user, drug, 2.0, UUID.randomUUID()).andExpect(status().isOk)

        assertQty(6.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "два разных события — два списания")
    }

    @Test
    fun `повтор приёма, обнулившего план, возвращает тот же результат, а не 404`() {
        // Самый важный случай: приём забирает план целиком, план удаляется. Наивная отметка
        // «уже обработано» заставила бы повтор искать удалённый план и вернуть 404, из-за чего
        // клиент решил бы, что операция не прошла, и повторял бы её дальше.
        val fixture = prepare(stock = 10.0, plan = 3.0)
        val user = fixture.user
        val drug = fixture.drug
        val intakeId = UUID.randomUUID()

        val first = intake(user, drug, 3.0, intakeId).andExpect(status().isOk)
            .andReturn().response.contentAsString
        val second = intake(user, drug, 3.0, intakeId).andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertTrue(first.isBlank(), "план исчез, поэтому тело первого ответа пустое: '$first'")
        assertEquals(first, second, "повтор обязан вернуть тот же результат, а не 404")
        assertQty(7.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "списание должно быть однократным")
    }

    @Test
    fun `отказ не кешируется и не блокирует последующий приём`() {
        // Кешируется только состоявшийся приём. Закешированный отказ переживёт исправление
        // своей причины: клиент починил количество, а сервер продолжает отдавать прежнюю
        // ошибку до истечения TTL. Ровно поэтому запись в кеш стоит после успешного вызова.
        val fixture = prepare(stock = 10.0, plan = 2.0)
        val user = fixture.user
        val drug = fixture.drug
        val intakeId = UUID.randomUUID()

        intake(user, drug, 5.0, intakeId).andExpect(status().isBadRequest)
        intake(user, drug, 2.0, intakeId).andExpect(status().isOk)

        assertQty(8.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "приём после отказа с тем же intakeId должен примениться: первого списания не было")
    }

    @Test
    fun `один intakeId у разных пользователей не пересекается`() {
        val fixture = prepare(stock = 10.0, plan = 4.0)
        val alice = fixture.user
        val drug = fixture.drug
        val medKit = fixture.medKit
        val bob = userRepository.save(User(hashedKey = "{noop}k-${UUID.randomUUID()}"))
        // Через сервис, а не правкой коллекций руками: он транзакционный и синхронизирует обе
        // стороны связи. Обращение к drug.medKit.users вне сессии давало
        // LazyInitializationException — Drug.medKit ленивый, а класс не @Transactional.
        medKitService.addUserToMedKit(medKit.id, bob.id)
        treatmentPlanService.create(bob.id, drug.id, qty(4.0))

        // Клиент генерирует intakeId сам, поэтому совпадение возможно; ключ кеша включает
        // пользователя, и приём Боба не должен получить результат Алисы.
        val shared = UUID.randomUUID()
        intake(alice, drug, 1.0, shared).andExpect(status().isOk)
        intake(bob, drug, 1.0, shared).andExpect(status().isOk)

        assertQty(8.0, drugRepository.findById(drug.id).orElseThrow().quantity,
            "оба приёма должны примениться: это разные пользователи")
    }
}
