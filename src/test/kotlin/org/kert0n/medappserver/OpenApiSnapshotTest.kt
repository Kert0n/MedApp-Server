package org.kert0n.medappserver

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
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

/**
 * Держит закоммиченный контракт в согласии с приложением.
 *
 * `open-api.yaml` существует затем, чтобы контракт можно было прочитать, не поднимая Spring и не
 * разбирая аннотации, и он порождается, а не пишется руками: снимку, которому нельзя верить,
 * лучше не быть вовсе. Расхождение роняет сборку, и каждая правка контракта уносит в диф
 * перегенерированный файл.
 *
 * Перегенерировать после намеренного изменения:
 *
 *     ./gradlew test -DupdateOpenApi=true
 *
 * Всегда через MockMvc: именно поэтому запись `servers` определена, а не содержит случайный порт,
 * который выдал бы настоящий сервер.
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
        // UTF-8 явно: springdoc здесь кодировку не объявляет, а `contentAsString` откатился бы
        // к ISO-8859-1 и испортил бы каждый неascii-символ в описаниях.
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
        // Тесты запускаются из каталога проекта.
        private val SNAPSHOT: Path = Path.of("open-api.yaml")
    }
}
