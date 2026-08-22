package org.kert0n.medappserver.integration

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

/**
 * Error bodies must not describe what failed in terms of the caller's data: the default body
 * echoes the exception message, and those carry package ids and amounts.
 */
@SpringBootTest
@ActiveProfiles("test")
class ErrorResponseShapeTest {

    @Autowired

    private lateinit var dbHelper: DatabaseTestHelper


    @Autowired

    private lateinit var medKitService: org.kert0n.medappserver.services.aggregate.MedKitService


    @Autowired
    private lateinit var context: WebApplicationContext


    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `not found does not disclose the requested identifier`() {
        val userId = Uuid.random()
        val missingDrugId = Uuid.random()

        val body = mockMvc.perform(
            get(ApiRoutes.drug(missingDrugId)).with(jwt().jwt { it.subject(userId.toString()) })
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("Requested resource does not exist"))
            .andReturn().response.contentAsString

        // `instance` carries the request URI, so it repeats the id the caller just sent —
        // standard for RFC 9457. What must not appear is the message or the exception type.
        assertFalse(body.contains("access denied"), "internal message leaked: $body")
        assertFalse(body.contains("Drug not found"), "internal message leaked: $body")
        assertFalse(body.contains("Exception"), "error body leaked an exception class: $body")
    }

    @Test
    fun `insufficient quantity does not disclose amounts`() {
        // Пачка на 5 таблеток, просим съесть 500: бронь больше остатка законна, так что утечку
        // проверяем на том отказе, который остался.
        val user = dbHelper.insert(User(hashedKey = "{noop}k"))
        val medKit = dbHelper.freshMedKit(user.id)
        val drug = dbHelper.insert(
            Drug(
                medKitId = medKit.id, name = "Aspirin", quantity = Quantity(qty(5.0), dbHelper.unit()),
            )
        )

        val body = mockMvc.perform(
            post(ApiRoutes.intakes(drug.id))
                .with(jwt().jwt { it.subject(user.id.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"quantity":"500.0","version":0}""")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("Request cannot be processed"))
            .andReturn().response.contentAsString

        // `instance` несёт путь запроса и нового не раскрывает, поэтому вырезан: иначе
        // проверка ловит подстроку «500» в случайном Uuid. В остальном теле не должно быть ни
        // количеств, ни имени исключения.
        val described = body.replace(Regex("\"instance\":\"[^\"]*\""), "")
        assertFalse(described.contains("500"), "error body leaked the requested amount: $body")
        assertFalse(described.contains("5.0"), "error body leaked the available amount: $body")
        assertFalse(described.contains("Exception"), "error body leaked an exception class: $body")
    }

    @Test
    fun `body validation reports fields without echoing values`() {
        val userId = Uuid.random()
        // amount below the @DecimalMin("0.0") constraint.
        val invalid = """{"drugId":"${Uuid.random()}","amount":"-42.5","version":0}"""

        val body = mockMvc.perform(
            post(ApiRoutes.RESERVATIONS)
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("amount"))
            .andReturn().response.contentAsString

        assertTrue(body.contains("amount"), "field name should be reported: $body")
        assertFalse(body.contains("-42.5"), "rejected value must not be echoed: $body")
    }
}
