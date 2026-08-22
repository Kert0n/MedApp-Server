package org.kert0n.medappserver

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * Кто принимает версию, тот объявляет её коды отказа.
 *
 * Проверяется опубликованный документ, а не намерение: клиент читает контракт, и «мы же
 * проставляем коды кастомайзером» ему не поможет, если кастомайзер не отработал.
 *
 * Утверждается **равенство двух множеств**, выведенных из самого документа: операции, принимающие
 * версию, и операции, объявившие 428 с 412. Список имён не годится — новая операция с версией
 * прошла бы мимо него молча; равенство ловит обе стороны, включая лишнее объявление там, где
 * версии нет.
 */
@SpringBootTest
@ActiveProfiles("test")
class PreconditionContractTest {

    @Autowired private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    @Test
    fun `операция с версией объявляет 428 и 412`() {
        val contract = contract()
        val schemas = contract.path("components").path("schemas")

        val stated = sortedSetOf<String>()
        val declared = sortedSetOf<String>()
        forEachOperation(contract) { name, operation ->
            if (statesVersion(operation, schemas)) stated += name
            val responses = operation.path("responses")
            if (responses.has("428") && responses.has("412")) declared += name
        }

        assertTrue(stated.isNotEmpty(), "операций с версией не нашлось — тест смотрит не туда")
        assertEquals(
            stated, declared,
            "объявленные коды предусловия разошлись с теми, кто версию принимает"
        )
    }

    /**
     * Синхронизация версии принимает, но отвечает 409 — и правило это знает.
     *
     * Её версии едут телом и предусловием запроса не были, поэтому 412 ей не полагается. Проверка
     * стоит рядом, чтобы «синхронизация выпала из правила» и «синхронизацию забыли» не выглядели
     * одинаково.
     */
    @Test
    fun `синхронизация отвечает конфликтом, а не предусловием`() {
        val sync = contract().path("paths")
            .path("/v1/drugs/{drugId}/sync/{syncId}").path("put").path("responses")

        assertTrue(sync.has("409"), "версия из тела синхронизации отвечает 409")
        assertTrue(!sync.has("412"), "предусловием запроса версия синхронизации не была")
        assertTrue(!sync.has("428"), "того же и про 428: версии в теле необязательны")
    }

    private fun contract(): JsonNode {
        val json = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn().response.contentAsByteArray
            .toString(Charsets.UTF_8)
        return ObjectMapper().readTree(json)
    }

    private fun forEachOperation(contract: JsonNode, visit: (String, JsonNode) -> Unit) {
        contract.path("paths").fields().forEach { (path, item) ->
            item.fields().forEach { (method, operation) -> visit("$method $path", operation) }
        }
    }

    /** То же определение, что и в контракте: параметр `version` или поле `version` в теле. */
    private fun statesVersion(operation: JsonNode, schemas: JsonNode): Boolean {
        val inQuery = operation.path("parameters").any {
            it.path("name").asText() == "version" && it.path("in").asText() == "query"
        }
        return inQuery || "version" in bodyProperties(operation, schemas)
    }

    private fun bodyProperties(operation: JsonNode, schemas: JsonNode): Set<String> {
        val schema = operation.path("requestBody").path("content").fields().asSequence()
            .firstOrNull()?.value?.path("schema") ?: return emptySet()
        val resolved = schema.path("\$ref").asText("").substringAfterLast('/')
            .takeIf { it.isNotEmpty() }?.let { schemas.path(it) } ?: schema
        return resolved.path("properties").fieldNames().asSequence().toSet()
    }
}
