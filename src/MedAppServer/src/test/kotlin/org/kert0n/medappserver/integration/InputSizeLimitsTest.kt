package org.kert0n.medappserver.integration

import java.util.UUID
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.CatalogueService
import org.kert0n.medappserver.testutil.ApiRoutes
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

/**
 * Bounds on what a single authenticated request can ask for: unbounded `limit` reaches Postgres
 * as `LIMIT -1` or becomes an out-of-memory lever, and an unbounded `description` is a column of
 * Integer.MAX_VALUE.
 *
 * CatalogueService is mocked so this stays a test of the validation boundary: the real query
 * calls pg_trgm's similarity(), which H2 does not have.
 */
@SpringBootTest
@ActiveProfiles("test")
class InputSizeLimitsTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    @MockitoBean
    private lateinit var catalogueService: CatalogueService

    private lateinit var mockMvc: MockMvc

    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
        whenever(catalogueService.fuzzySearch(any(), any())).thenReturn(emptyList())
    }

    private fun search(limit: String) = mockMvc.perform(
        get(ApiRoutes.DRUG_TEMPLATES)
            .param("query", "aspirin")
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
            get(ApiRoutes.DRUG_TEMPLATES)
                .param("query", "aspirin")
                .with(jwt().jwt { it.subject(userId.toString()) })
        ).andExpect(status().isOk)
    }

    @Test
    fun `oversized description is rejected`() {
        val body = """
            {"name":"Aspirin","quantity":10.0,"quantityUnit":"tab",
             "description":"${"x".repeat(4001)}"}
        """.trimIndent()

        mockMvc.perform(
            post(ApiRoutes.drugsOf(UUID.randomUUID()))
                .with(jwt().jwt { it.subject(userId.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `oversized search term is rejected`() {
        mockMvc.perform(
            get(ApiRoutes.DRUG_TEMPLATES)
                .param("query", "x".repeat(201))
                .with(jwt().jwt { it.subject(userId.toString()) })
        ).andExpect(status().isBadRequest)
    }
}
