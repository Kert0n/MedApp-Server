package org.kert0n.medappserver.integration

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.VidalDrugService
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * Bounds on what a single authenticated request can ask for.
 *
 * `limit` used to be unbounded: -1 reached Postgres as LIMIT -1 and came back as a 500,
 * and a large value was an out-of-memory lever. `description` had no length constraint at
 * all, with the column declared as Integer.MAX_VALUE.
 *
 * VidalDrugService is mocked so this stays a test of the validation boundary: the real
 * query calls pg_trgm's similarity(), which does not exist on the H2 test database.
 */
@SpringBootTest
@ActiveProfiles("test")
class InputSizeLimitsTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @MockitoBean
    private lateinit var vidalDrugService: VidalDrugService

    private lateinit var mockMvc: MockMvc

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        whenever(vidalDrugService.fuzzySearchByName(any(), any())).thenReturn(emptyList())
    }

    private fun search(limit: String) = mockMvc.perform(
        get("/drug/template/search")
            .param("searchTerm", "aspirin")
            .param("limit", limit)
            .with(jwt().jwt { it.subject(userId.toString()) })
    )

    @Test
    fun `out of range limit is rejected with 400, not 500`() {
        search("0").andExpect(status().isBadRequest)
        search("-1").andExpect(status().isBadRequest)
        search("51").andExpect(status().isBadRequest)
        search("10000000").andExpect(status().isBadRequest)
    }

    @Test
    fun `limit inside the allowed range is accepted`() {
        search("1").andExpect(status().isOk)
        search("50").andExpect(status().isOk)
        // Default applies when the parameter is absent.
        mockMvc.perform(
            get("/drug/template/search")
                .param("searchTerm", "aspirin")
                .with(jwt().jwt { it.subject(userId.toString()) })
        ).andExpect(status().isOk)
    }

    @Test
    fun `oversized description is rejected`() {
        val body = """
            {"name":"Aspirin","quantity":10.0,"quantityUnit":"tab",
             "medKitId":"${UUID.randomUUID()}","description":"${"x".repeat(4001)}"}
        """.trimIndent()

        mockMvc.perform(
            post("/drug")
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `oversized search term is rejected`() {
        mockMvc.perform(
            get("/drug/template/search")
                .param("searchTerm", "x".repeat(201))
                .with(jwt().jwt { it.subject(userId.toString()) })
        ).andExpect(status().isBadRequest)
    }
}
