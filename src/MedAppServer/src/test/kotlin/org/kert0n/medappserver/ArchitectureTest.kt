package org.kert0n.medappserver

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Слои приложения и разрешённые связи между ними.
 *
 * Правила ниже — не документация, а проверка: нарушение роняет сборку. Заведено потому, что
 * прозой они уже были записаны и всё равно протекли. К моменту этого рефакторинга
 * репозиторий возвращал api-DTO через строку JPQL, кеш идемпотентности хранил тело ответа,
 * модельные сервисы звали друг друга, а бизнес-арифметика жила в контроллере — и ни одна из
 * этих утечек не была видна ни компилятору, ни 238 тестам.
 *
 * Konsist, а не ArchUnit: последний работает с байткодом, где top-level функции и расширения
 * Kotlin размазаны по синтетическим классам вроде `MappersKt`. Здесь их достаточно —
 * `api/Mappers.kt`, `controller/AuthenticationSupport.kt`, — и правило по байткоду читалось
 * бы неверно.
 *
 * Каждое правило начинается с проверки, что набор файлов непуст. Правило по пустому набору
 * зелёное всегда: переименуй пакет — и проверка перестанет что-либо значить, никак об этом
 * не сообщив.
 */
class ArchitectureTest {

    private val root = "org.kert0n.medappserver"

    /**
     * Разрешённые связи. Ключ — пакет слоя, значение — то, что ему позволено импортировать
     * из своего же проекта.
     *
     * Обоснования, которые не видны из таблицы:
     *
     *  * `services.models` → `services.security` оставлено сознательно: `hashPassword`,
     *    `generateKey` и `hashToken` — криптографические утилиты, а не чужая бизнес-логика.
     *  * `api` → `services.models` нужно мапперам команд (`toCommand`, `toPatch`):
     *    представление вправе знать прикладной слой, обратное запрещено отдельным правилом.
     *  * `config` → `services.orchestrators` — composition root объявляет бины кешей, в
     *    сигнатурах которых стоит `IntakeOutcome`.
     */
    private val allowedEdges: Map<String, Set<String>> = mapOf(
        "db.model" to setOf("db.model"),
        "db.repository" to setOf("db.model"),
        "services.security" to setOf("db.model"),
        "services.models" to setOf("db.model", "db.repository", "services.security", "services.models"),
        "services.orchestrators" to setOf(
            "db.model",
            "db.repository",
            "services.models",
            "services.security"
        ),
        "api" to setOf("db.model", "db.repository", "services.models"),
        "controller" to setOf("api", "config", "db.repository", "services.models", "services.orchestrators", "services.security"),
        "config" to setOf("api", "db.model", "db.repository", "services.models", "services.orchestrators", "services.security")
    )

    /**
     * Только `src/main`. Тесты сознательно ходят через слои — они на то и тесты, и
     * запрещать им это значило бы запретить проверять что-либо, кроме HTTP.
     */
    private fun productionFiles(): List<KoFileDeclaration> = Konsist.scopeFromProduction().files

    private fun filesOf(pkg: String): List<KoFileDeclaration> =
        productionFiles()
            .filter { it.packagee?.name?.startsWith("$root.$pkg") == true }
            .also { assertTrue(it.isNotEmpty(), "в пакете $pkg нет файлов — правило проверяло бы пустоту") }

    /** К какому слою относится импорт, или `null`, если импорт не наш. */
    private fun layerOf(import: String): String? {
        if (!import.startsWith("$root.")) return null
        val tail = import.removePrefix("$root.")
        return allowedEdges.keys
            .filter { tail.startsWith("$it.") }
            // db.model.parsed — часть db.model; самое длинное совпадение даёт верный слой.
            .maxByOrNull { it.length }
    }

    @Test
    fun `слои импортируют только разрешённое`() {
        val violations = mutableListOf<String>()
        allowedEdges.forEach { (pkg, allowed) ->
            filesOf(pkg).forEach { file ->
                file.imports.forEach { import ->
                    val target = layerOf(import.name) ?: return@forEach
                    if (target != pkg && target !in allowed) {
                        violations += "${file.name}: $pkg -> $target (${import.name})"
                    }
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "запрещённые связи между слоями:\n" + violations.sorted().joinToString("\n") { "  $it" }
        )
    }

    @Test
    fun `контроллеры никому не видны`() {
        val offenders = productionFiles()
            .filter { it.packagee?.name?.startsWith("$root.controller") != true }
            .filter { file -> file.imports.any { it.name.startsWith("$root.controller.") } }
            .map { it.name }
        assertTrue(
            offenders.isEmpty(),
            "controller — верхний слой, его не импортирует никто: $offenders"
        )
    }

    @Test
    fun `хранилище и сервисы не знают про api`() {
        // Дублирует таблицу выше, но падает с говорящим именем: именно эта утечка была самой
        // дорогой — JPQL с конструктором api-DTO ломался в рантайме, а не при компиляции.
        val offenders = productionFiles()
            .filter {
                val p = it.packagee?.name ?: return@filter false
                p.startsWith("$root.db") || p.startsWith("$root.services")
            }
            .filter { file -> file.imports.any { it.name.startsWith("$root.api.") } }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "api-типы в слое хранилища или сервисов: $offenders")
    }

    @Test
    fun `модельные сервисы не зависят друг от друга`() {
        // Правило про конструкторы, а не импорты: командные типы из services.models
        // (DrugCreation, PlanSnapshot) сервисам друг у друга брать можно, а бины — нет.
        // Именно это ребро в UsingService прятало оркестрацию: он брал через DrugService
        // блокировку чужого агрегата.
        val services = filesOf("services.models").flatMap { it.classes() }
            .filter { it.name.endsWith("Service") }
        assertTrue(services.isNotEmpty(), "в services.models нет классов *Service")

        val offenders = services.flatMap { service ->
            service.primaryConstructor?.parameters.orEmpty()
                .filter { it.type.name.endsWith("Service") && it.type.name != "SecurityService" }
                .map { "${service.name}(${it.name}: ${it.type.name})" }
        }
        assertTrue(
            offenders.isEmpty(),
            "модельный сервис принимает другой модельный сервис — координация нескольких " +
                "агрегатов принадлежит оркестратору: $offenders"
        )
    }

    @Test
    fun `сущности не знают ни о чём выше себя`() {
        val offenders = filesOf("db.model")
            .filter { file ->
                file.imports.any {
                    it.name.startsWith("$root.") && !it.name.startsWith("$root.db.model")
                }
            }
            .map { it.name }
        assertTrue(offenders.isEmpty(), "сущность импортирует что-то выше слоя модели: $offenders")
    }
}
