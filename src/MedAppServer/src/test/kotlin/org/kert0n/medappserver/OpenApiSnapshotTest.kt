package org.kert0n.medappserver

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * Keeps the committed API contract honest.
 *
 * open-api.yaml exists so the contract can be read without starting Spring or digging
 * through annotations. It was maintained by hand and had already drifted, which defeats the
 * point: a snapshot you cannot trust is worse than no snapshot. Now it is generated, and any
 * drift fails the build.
 *
 * Side effect worth having: every commit that changes the contract carries the regenerated
 * file, so an API change is visible in the diff of a pull request without reading code.
 *
 * To regenerate after an intentional change:
 *
 *     ./gradlew test -DupdateOpenApi=true
 *
 * Always goes through MockMvc, which is what makes the `servers` entry deterministic — a
 * real server would put its randomly assigned port in there.
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenApiSnapshotTest {

    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `committed contract matches the generated one`() {
        // Bytes decoded as UTF-8 explicitly: springdoc does not declare a charset on this
        // response, so MockHttpServletResponse.contentAsString would fall back to
        // ISO-8859-1 and mangle every non-ASCII character in the descriptions.
        val generated = mockMvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)

        if (System.getProperty("updateOpenApi") == "true") {
            Files.writeString(SNAPSHOT, generated)
            println("Rewrote $SNAPSHOT from the running application")
            return
        }

        val committed = if (Files.exists(SNAPSHOT)) Files.readString(SNAPSHOT) else ""
        assertEquals(
            committed,
            generated,
            "$SNAPSHOT no longer matches the generated contract. If the API change was " +
                "intentional, regenerate it in the same commit: ./gradlew test -DupdateOpenApi=true"
        )
    }

    private companion object {
        // Tests run with the project directory as working directory.
        private val SNAPSHOT: Path = Path.of("open-api.yaml")
    }
}
