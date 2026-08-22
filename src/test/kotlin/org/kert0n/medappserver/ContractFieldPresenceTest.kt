package org.kert0n.medappserver

import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.asJsonTree
import org.kert0n.medappserver.testutil.field
import org.kert0n.medappserver.testutil.fields
import org.kert0n.medappserver.testutil.items
import org.kert0n.medappserver.testutil.sourcesIn
import org.kert0n.medappserver.testutil.text
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
 * Контракт говорит про каждое поле, приезжает ли оно всегда и бывает ли пустым.
 *
 * Объявления ставит `OpenApiFieldPresence` по описанию kotlinx — из того же места, откуда тела и
 * пишутся. Проверять это тем же описанием было бы проверкой самой себя, поэтому ожидание здесь
 * снимается **с исходников**: у поля нет значения по умолчанию — оно обязательно; тип с
 * вопросом — значение бывает пустым.
 *
 * Настоящая находка теста — не расхождение по одному полю, а класс, до которого объявления не
 * дошли вовсе: поиск ходит по пакету `api`, и уехавший оттуда тип потерял бы их молча.
 */
@SpringBootTest
@ActiveProfiles("test")
class ContractFieldPresenceTest {

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
    fun `контракт объявляет обязательные поля`() {
        val schemas = publishedSchemas()
        val declared = wireTypes()

        assertEquals(
            declared.keys.sorted(),
            schemas.fields().keys.sorted(),
            "контракт публикует не те типы, что лежат в `api`: уехавший оттуда объявлений не получит"
        )

        declared.forEach { (name, fields) ->
            val schema = schemas.field(name)
            assertTrue(schema != null, "тип $name не попал в контракт")
            assertEquals(
                fields.filter { !it.hasDefault }.map { it.name }.sorted(),
                schema.field("required").items().mapNotNull { it.text() }.sorted(),
                "в схеме $name обязательные поля объявлены не те, что видит сериализатор"
            )
        }
    }

    @Test
    fun `контракт объявляет поля, которые бывают пустыми`() {
        val schemas = publishedSchemas()

        wireTypes().forEach { (name, fields) ->
            val properties = schemas.field(name).field("properties")
            fields.forEach { field ->
                val types = properties.field(field.name).field("type")
                    .let { it.items().mapNotNull { item -> item.text() }.ifEmpty { listOfNotNull(it.text()) } }
                // Поле, уехавшее в `$ref`, своего типа не имеет: обнуляемых ссылок в контракте нет.
                if (types.isEmpty()) return@forEach
                assertEquals(
                    field.nullable,
                    "null" in types,
                    "поле $name.${field.name} объявлено в контракте не так, как объявлен его тип"
                )
            }
        }
    }

    private fun publishedSchemas(): JsonElement? =
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray.toString(Charsets.UTF_8)
            .asJsonTree().field("components").field("schemas")

    /** Поле провода так, как оно написано в исходнике. */
    private data class Field(val name: String, val nullable: Boolean, val hasDefault: Boolean)

    /**
     * Типы контракта, разобранные по тексту.
     *
     * Разбирается только список параметров конструктора: `@Serializable` в проекте носят
     * исключительно `data class` контракта, и других форм здесь не бывает.
     */
    private fun wireTypes(): Map<String, List<Field>> =
        sourcesIn("api").flatMap { path ->
            val source = path.readText()
            DATA_CLASS.findAll(source).mapNotNull { match ->
                val opening = source.indexOf('(', match.range.last)
                val closing = matchingParenthesis(source, opening) ?: return@mapNotNull null
                match.groupValues[1] to parameters(source.substring(opening + 1, closing))
            }
        }.toMap()

    private fun parameters(text: String): List<Field> = splitTopLevel(text).mapNotNull { chunk ->
        val declaration = VAL.find(chunk) ?: return@mapNotNull null
        val tail = chunk.substring(declaration.range.last + 1)
        val default = tail.indexOfFirst { it == '=' }
        val type = (if (default == -1) tail else tail.substring(0, default)).trim()
        Field(declaration.groupValues[1], type.endsWith("?"), default != -1)
    }

    /** Режет по запятым верхнего уровня: внутри аннотаций, дженериков и строк запятые свои. */
    private fun splitTopLevel(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val chunk = StringBuilder()
        var depth = 0
        var inString = false
        text.forEach { symbol ->
            when {
                symbol == '"' -> inString = !inString
                inString -> Unit
                symbol == '(' || symbol == '[' || symbol == '<' -> depth++
                symbol == ')' || symbol == ']' || symbol == '>' -> depth--
                symbol == ',' && depth == 0 -> {
                    chunks += chunk.toString(); chunk.clear(); return@forEach
                }
            }
            chunk.append(symbol)
        }
        return (chunks + chunk.toString()).filter { it.isNotBlank() }
    }

    private fun matchingParenthesis(source: String, opening: Int): Int? {
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index
            }
        }
        return null
    }

    private companion object {
        val DATA_CLASS = Regex("""@Serializable\s+data class (\w+)""")

        // Дженерик в типе `Set<MedKitDTO>` кавычек не несёт, а вопрос принадлежит типу целиком.
        val VAL = Regex("""\bval (\w+)\s*:""")
    }
}
