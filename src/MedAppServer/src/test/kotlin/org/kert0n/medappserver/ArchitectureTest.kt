package org.kert0n.medappserver

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.orchestrators.DrugCommandService
import org.kert0n.medappserver.services.orchestrators.TreatmentPlanService
import java.lang.reflect.Modifier
import kotlin.test.assertTrue

/**
 * Исполняемые границы слоёв. Непустые выборки защищают правила от тихого отключения
 * после переименования пакета.
 */
class ArchitectureTest {

    private val root = "org.kert0n.medappserver"

    /** Ключ — слой, значение — разрешённые зависимости на другие слои проекта. */
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
        "controller" to setOf("api", "config", "services.models", "services.orchestrators", "services.security"),
        "config" to setOf("api", "db.model", "db.repository", "services.models", "services.orchestrators", "services.security")
    )

    /** Архитектурные ограничения относятся только к production-коду. */
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
        // Командные типы могут быть общими, но сервисные бины друг друга не координируют.
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
    fun `модельный сервис использует репозиторий только своего агрегата`() {
        val services = filesOf("services.models").flatMap { it.classes() }
            .filter { it.name.endsWith("Service") }
        val offenders = services.flatMap { service ->
            val aggregate = service.name.removeSuffix("Service")
            service.primaryConstructor?.parameters.orEmpty()
                .filter { it.type.name.endsWith("Repository") }
                .filter { !it.type.name.startsWith(aggregate) }
                .map { "${service.name}(${it.name}: ${it.type.name})" }
        }
        assertTrue(
            offenders.isEmpty(),
            "read/model-сервис не координирует чужой агрегат через его репозиторий: $offenders"
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

    @Test
    fun `командные сервисы не возвращают JPA сущности`() {
        val offenders = listOf(DrugCommandService::class.java, TreatmentPlanService::class.java)
            .flatMap { service ->
                service.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) }
                    .filter { it.returnType.packageName == "$root.db.model" }
                    .map { "${service.simpleName}.${it.name}: ${it.returnType.simpleName}" }
            }
        assertTrue(offenders.isEmpty(), "JPA-сущности в публичном результате команды: $offenders")
    }
}
