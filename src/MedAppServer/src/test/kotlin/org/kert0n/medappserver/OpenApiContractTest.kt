package org.kert0n.medappserver

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

@SpringBootTest
@ActiveProfiles("test")
class OpenApiContractTest {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `committed OpenAPI matches generated contract`() {
        val mockMvc: MockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        val generated = mockMvc.perform(get("/v3/api-docs"))
            .andReturn()
            .response
            .contentAsString
        val contractPath = Path.of("open-api.yaml")

        if (System.getProperty("updateOpenApi") == "true") {
            val formatted = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(objectMapper.readTree(generated)) + System.lineSeparator()
            Files.writeString(contractPath, formatted)
        }

        assertEquals(
            objectMapper.readTree(Files.readString(contractPath)),
            objectMapper.readTree(generated),
            "Run ./gradlew test --tests '*OpenApiContractTest' -DupdateOpenApi=true"
        )
    }
}
