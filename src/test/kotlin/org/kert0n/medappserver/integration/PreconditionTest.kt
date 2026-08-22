package org.kert0n.medappserver.integration

import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Предусловие обязательно, и отказы на нём различаются.
 *
 * Три разных «нет» вместо одного: не прислали версию — 428, прислали мусор — 400, прислали
 * чужую — 412. Отвечать на них одинаково значило бы не сказать клиенту, что именно чинить.
 * Проигранная гонка — уже не про предусловие: она выясняется при записи и отвечает 409.
 */
@PostgresIntegrationTest
class PreconditionTest {

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
    fun `команда без версии отвергается с 428`() {
        val owner = dbHelper.freshUser("pre-none")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            patch(ApiRoutes.drug(drug.id)).with(asUser(owner.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Аспирин"}""")
        ).andExpect(status().isPreconditionRequired)
    }

    @Test
    fun `нечитаемая версия отвергается с 400`() {
        val owner = dbHelper.freshUser("pre-garbage")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            patch(ApiRoutes.drug(drug.id)).with(asUser(owner.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Аспирин","version":"позавчера"}""")
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `чужая версия отвергается с 412`() {
        val owner = dbHelper.freshUser("pre-stale")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            patch(ApiRoutes.drug(drug.id)).with(asUser(owner.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Аспирин","version":99}""")
        ).andExpect(status().isPreconditionFailed)
    }

    /**
     * Недоступное остаётся 404 и при верной версии: чужая пачка для вызывающего не существует,
     * и подсказывать обратное отказом про версию нельзя.
     */
    @Test
    fun `недоступная упаковка отвечает 404, а не 412`() {
        val stranger = dbHelper.freshUser("pre-stranger")
        val owner = dbHelper.freshUser("pre-owner")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            patch(ApiRoutes.drug(drug.id)).with(asUser(stranger.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Аспирин","version":0}""")
        ).andExpect(status().isNotFound)
    }

    /** Версия в параметре запроса — там, где тела нет. */
    @Test
    fun `удаление без версии отвергается с 428`() {
        val owner = dbHelper.freshUser("pre-delete")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(delete(ApiRoutes.drug(drug.id)).with(asUser(owner.id)))
            .andExpect(status().isPreconditionRequired)
    }

    @Test
    fun `верная версия проходит`() {
        val owner = dbHelper.freshUser("pre-ok")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            patch(ApiRoutes.drug(drug.id)).with(asUser(owner.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Аспирин","version":${dbHelper.drugVersion(drug.id)}}""")
        ).andExpect(status().isOk)
    }

    /** Бронь предъявляет версию картины, а не свою: своей у неё нет. */
    @Test
    fun `бронь без версии картины отвергается с 428`() {
        val owner = dbHelper.freshUser("pre-reservation")
        val drug = dbHelper.freshDrug(dbHelper.freshMedKit(owner.id).id, 10.0)

        mockMvc.perform(
            post(ApiRoutes.RESERVATIONS).with(asUser(owner.id))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"drugId":"${drug.id}","amount":"3.0"}""")
        ).andExpect(status().isPreconditionRequired)
    }

    private fun asUser(userId: Uuid) = jwt().jwt { it.subject(userId.toString()) }
}
