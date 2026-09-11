package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Приём под придуманным клиентом идентификатором.
 *
 * Потерянный ответ на приём клиент повторяет тем же телом, не зная, дошёл ли первый. Повтор
 * обязан ничего не списывать — даже когда версия упаковки с тех пор ушла вперёд и сам по себе
 * такой запрос получил бы 412.
 */
@PostgresIntegrationTest
class IntakeRepeatTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var dbHelper: DatabaseTestHelper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `повтор того же приёма не списывает второй раз и отвечает тем же снимком`() {
        val owner = dbHelper.freshUser("intake-repeat")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val intakeId = Uuid.random()
        val body = """{"quantity":"5.0","version":${dbHelper.drugVersion(drug.id)}}"""

        val first = intake(owner.id, drug.id, intakeId, body).andExpect(status().isOk)
            .andReturn().response.contentAsString
        // Версия в теле уже устарела: без журнала это был бы 412, а клиент подготовил бы заново.
        val repeated = intake(owner.id, drug.id, intakeId, body).andExpect(status().isOk)
            .andReturn().response.contentAsString

        assertEquals(first, repeated, "повтор отвечает тем же снимком")
        assertQty(15.0, dbHelper.drugQuantity(drug.id), "повтор обязан ничего не делать")
    }

    @Test
    fun `повтор после чужого приёма отвечает снимком, а не предусловием`() {
        val owner = dbHelper.freshUser("intake-repeat-after")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val intakeId = Uuid.random()
        val body = """{"quantity":"5.0","version":${dbHelper.drugVersion(drug.id)}}"""

        intake(owner.id, drug.id, intakeId, body).andExpect(status().isOk)
        intake(owner.id, drug.id, Uuid.random(), """{"quantity":"2.0","version":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isOk)
        intake(owner.id, drug.id, intakeId, body).andExpect(status().isOk)

        assertQty(13.0, dbHelper.drugQuantity(drug.id), "списаны оба приёма, и каждый один раз")
    }

    @Test
    fun `тот же идентификатор с другим содержимым — конфликт`() {
        val owner = dbHelper.freshUser("intake-conflict")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val intakeId = Uuid.random()

        intake(owner.id, drug.id, intakeId, """{"quantity":"5.0","version":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isOk)
        intake(owner.id, drug.id, intakeId, """{"quantity":"9.0","version":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isConflict)

        assertQty(15.0, dbHelper.drugQuantity(drug.id), "конфликт ничего не списал")
    }

    @Test
    fun `новый приём с устаревшей версией — 412`() {
        val owner = dbHelper.freshUser("intake-stale")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)

        intake(owner.id, drug.id, Uuid.random(), """{"quantity":"1.0","version":99}""")
            .andExpect(status().isPreconditionFailed)
    }

    /** Одно списание под одним идентификатором — повтор, каким бы входом оно ни пришло. */
    @Test
    fun `приём и синхронизация под одним идентификатором — одно списание`() {
        val owner = dbHelper.freshUser("intake-as-sync")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 20.0)
        val id = Uuid.random()

        intake(owner.id, drug.id, id, """{"quantity":"5.0","version":${dbHelper.drugVersion(drug.id)}}""")
            .andExpect(status().isOk)
        mockMvc.perform(
            put(ApiRoutes.sync(drug.id, id))
                .with(jwt().jwt { it.subject(owner.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"consumed":"5.0","drugVersion":${dbHelper.drugVersion(drug.id)}}""")
        ).andExpect(status().isOk)

        assertQty(15.0, dbHelper.drugQuantity(drug.id), "синхронизация повторила приём, а не списала второй раз")
    }

    private fun intake(userId: Uuid, drugId: Uuid, intakeId: Uuid, body: String) =
        mockMvc.perform(
            put(ApiRoutes.intake(drugId, intakeId))
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
}
