package org.kert0n.medappserver.api

import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.kert0n.medappserver.testutil.asJsonTree
import org.kert0n.medappserver.testutil.field
import org.kert0n.medappserver.testutil.text
import org.springframework.web.context.WebApplicationContext


/**
 * Ни одна величина не опубликована числом.
 *
 * Правило, а не список из девяти полей: `type` в схеме springdoc выводит из типа свойства, и
 * новое поле `BigDecimal` попадёт в контракт числом само, без чьего-либо решения. Сервер при
 * этом напишет строку — сериализатор общий на файл, — и контракт разойдётся с ответом молча.
 *
 * Проверяется опубликованный документ, а не аннотации: важно, что увидит клиент, а не что мы
 * написали рядом с полем.
 */
@SpringBootTest
@ActiveProfiles("test")
class DecimalSchemaTest {

    @Autowired private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `каждое поле BigDecimal опубликовано строкой`() {
        val schemas = publishedSchemas()
        val checked = mutableListOf<String>()

        decimalProperties().forEach { (schemaName, propertyName) ->
            val property = schemas.field(schemaName).field("properties").field(propertyName)
            assertTrue(
                property != null,
                "$schemaName.$propertyName не попало в контракт — схема названа иначе?"
            )
            assertEquals(
                "string",
                property.field("type").text(),
                "$schemaName.$propertyName опубликовано не строкой: величина на проводе — строка, " +
                    "добавьте type = \"string\" в @Schema рядом с полем"
            )
            checked += "$schemaName.$propertyName"
        }

        // Иначе молчаливо зелёный тест: не нашёл классов — не нашёл и нарушений.
        assertTrue(checked.isNotEmpty(), "не найдено ни одного поля BigDecimal — обход DTO сломан")
    }

    /** Пары «схема — свойство» для всех величин в опубликованных DTO. */
    private fun decimalProperties(): List<Pair<String, String>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(Any::class.java))
        return scanner.findCandidateComponents(API_PACKAGE)
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it).kotlin }
            .flatMap { klass ->
                klass.memberProperties
                    .filter { it.returnType.classifier == BigDecimal::class }
                    .map { klass.simpleName!! to it.name }
            }
    }

    private fun publishedSchemas(): JsonElement? =
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)
            .asJsonTree()
            .field("components").field("schemas")

    private companion object {
        const val API_PACKAGE = "org.kert0n.medappserver.api"
    }
}
