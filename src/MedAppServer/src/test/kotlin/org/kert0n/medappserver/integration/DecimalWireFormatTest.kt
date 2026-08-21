package org.kert0n.medappserver.integration

import java.util.UUID
import org.hamcrest.Matcher
import org.hamcrest.Matchers.instanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * Величина едет строкой — и туда, и обратно.
 *
 * Проверяется на живом запросе, а не на сериализаторе: между ним и проводом стоит выбор
 * конвертера, и правило «kotlinx берёт типы с `@Serializable`» верно ровно до тех пор, пока
 * кто-нибудь не снимет аннотацию. Тогда Jackson молча напишет число, и всё остальное
 * продолжит работать — кроме клиента.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecimalWireFormatTest {

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
    fun `в ответе величина и идентификатор — строки`() {
        val owner = dbHelper.freshUser("wire")
        val kit = dbHelper.freshMedKit(owner.id)
        val drug = dbHelper.freshDrug(kit.id, quantity = 100.0)

        mockMvc.perform(get(ApiRoutes.drug(drug.id)).with(asUser(owner.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.quantity").value(isJsonString()))
            .andExpect(jsonPath("$.drug.id").value(isJsonString()))
            .andExpect(jsonPath("$.drug.medKitId").value(isJsonString()))
            .andExpect(jsonPath("$.reservations.total").value(isJsonString()))
    }

    /**
     * Главное, ради чего затевалась строка.
     *
     * Предел колонки — двадцать значащих цифр, а `Double` держит семнадцать. Пройди это число
     * числом хоть на одном конце, оно вернулось бы другим — и тест сказал бы об этом здесь, а
     * не пользователь через полгода.
     */
    @Test
    fun `предельная величина доезжает до колонки и обратно без потери знаков`() {
        val owner = dbHelper.freshUser("wire-precision")
        val kit = dbHelper.freshMedKit(owner.id)
        val exact = "9999999999999.999999"

        val created = createDrug(kit.id, owner.id, """"$exact"""")
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val drugId = Regex(""""id":"([0-9a-f-]{36})"""").find(created)!!.groupValues[1]

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser(owner.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.quantity").value(exact))
    }

    /** Дробь мельче, чем видит `Double`, тоже обязана вернуться собой. */
    @Test
    fun `половина таблетки остаётся половиной`() {
        val owner = dbHelper.freshUser("wire-half")
        val kit = dbHelper.freshMedKit(owner.id)

        val created = createDrug(kit.id, owner.id, """"0.1"""")
            .andExpect(status().isCreated)
            .andReturn().response.contentAsString
        val drugId = Regex(""""id":"([0-9a-f-]{36})"""").find(created)!!.groupValues[1]

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser(owner.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.quantity").value("0.100000"))
    }

    /**
     * Испорченная величина — ошибка запроса, а не сервера.
     *
     * `NumberFormatException` из разбора пролетела бы наружу пятисоткой: клиент прислал мусор,
     * а виноватым выглядел бы сервер.
     */
    @Test
    fun `не-число отвергается четырёхсоткой`() {
        val owner = dbHelper.freshUser("wire-nan")
        val kit = dbHelper.freshMedKit(owner.id)

        createDrug(kit.id, owner.id, """"abc"""").andExpect(status().isBadRequest)
    }

    /** Контракт обещает строку, и число вместо неё — нарушение контракта, а не вольность. */
    @Test
    fun `число вместо строки не принимается`() {
        val owner = dbHelper.freshUser("wire-number")
        val kit = dbHelper.freshMedKit(owner.id)

        createDrug(kit.id, owner.id, "10.0").andExpect(status().isBadRequest)
    }

    /** Смена написания не отменила проверок: разрядность и «больше нуля» на месте. */
    @Test
    fun `валидация переживает смену написания`() {
        val owner = dbHelper.freshUser("wire-validation")
        val kit = dbHelper.freshMedKit(owner.id)

        createDrug(kit.id, owner.id, """"0"""").andExpect(status().isBadRequest)
        createDrug(kit.id, owner.id, """"12345678901234.1234567"""").andExpect(status().isBadRequest)
    }

    /**
     * Ответ об ошибке идёт мимо kotlinx: `ProblemDetail` — класс Spring без `@Serializable`, и
     * по правилу Boot достаётся Jackson. Правило объявленное, но проверяется, а не берётся на
     * слово: если бы конвертер забрал и его, ошибки перестали бы отвечать вовсе.
     */
    @Test
    fun `ошибка остаётся ProblemDetail`() {
        mockMvc.perform(get(ApiRoutes.drug(UUID.randomUUID())))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value(isJsonString()))
    }

    /** Узел JSON — именно строка: число под тем же значением тест обязан отличить. */
    private fun isJsonString(): Matcher<Any> = instanceOf(String::class.java)

    private fun asUser(userId: UUID) = jwt().jwt { it.subject(userId.toString()) }

    /** `quantity` подставляется куском JSON, а не значением: испытывается именно написание. */
    private fun createDrug(medKitId: UUID, userId: UUID, quantity: String): ResultActions =
        mockMvc.perform(
            post(ApiRoutes.drugsOf(medKitId))
                .with(asUser(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"name":"Aspirin","quantity":$quantity,""" +
                        """"quantityUnitId":"${dbHelper.unit().id}"}"""
                )
        )
}
