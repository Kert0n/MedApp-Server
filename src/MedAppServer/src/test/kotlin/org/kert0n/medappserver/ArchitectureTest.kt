package org.kert0n.medappserver

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.application.service.DrugService
import org.kert0n.medappserver.application.service.MedKitAccessService
import org.kert0n.medappserver.application.service.MedKitService
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ArchitectureTest {
    private val main = Path.of("src/main/kotlin/org/kert0n/medappserver")

    @Test
    fun `orchestrators and controllers do not bypass application services`() {
        assertImportsAbsent(
            main.resolve("application/orchestrator"),
            "org.kert0n.medappserver.persistence.repository",
            "org.kert0n.medappserver.db.repository",
            "org.kert0n.medappserver.db.model"
        )
        assertImportsAbsent(
            main.resolve("controller"),
            "org.kert0n.medappserver.persistence.repository",
            "org.kert0n.medappserver.db.repository",
            "org.kert0n.medappserver.db.model"
        )
    }

    @Test
    fun `domain and application service have no web dependencies`() {
        assertImportsAbsent(main.resolve("domain"), "org.springframework", "jakarta.persistence")
        assertImportsAbsent(
            main.resolve("application/service"),
            "org.springframework.http",
            "org.springframework.web",
            "org.kert0n.medappserver.controller",
            "org.kert0n.medappserver.api"
        )
    }

    @Test
    fun `aggregate services can only join an orchestrator transaction`() {
        listOf(DrugService::class.java, MedKitService::class.java, MedKitAccessService::class.java)
            .flatMap { type ->
                type.declaredMethods
                    .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
                    .map { type.simpleName to it }
            }
            .forEach { (type, method) ->
                val annotation = assertNotNull(
                    method.getAnnotation(Transactional::class.java),
                    "$type.${method.name} must declare its transaction contract"
                )
                assertEquals(
                    Propagation.MANDATORY,
                    annotation.propagation,
                    "$type.${method.name} must not create an independent transaction"
                )
            }
    }

    @Test
    fun `persistence model has no reverse aggregate collections or formula`() {
        val user = Files.readString(main.resolve("db/model/User.kt"))
        val medKit = Files.readString(main.resolve("db/model/MedKit.kt"))
        val drug = Files.readString(main.resolve("db/model/Drug.kt"))

        listOf(user, medKit, drug).forEach { source ->
            assertFalse("@OneToMany" in source)
            assertFalse("@ManyToMany" in source)
            assertFalse("@Formula" in source)
        }
        assertTrue(Files.exists(main.resolve("db/model/MedKitMembership.kt")))
    }

    @Test
    fun `legacy command surface cannot return`() {
        listOf(
            "services/orchestrators/MedKitDrugServices.kt",
            "services/orchestrators/QuantityReductionService.kt",
            "services/orchestrators/IntakeService.kt",
            "services/models/DrugService.kt",
            "services/models/UsingService.kt",
            "services/models/MedKitService.kt",
            "controller/LegacyDtos.kt"
        ).forEach { relative -> assertFalse(Files.exists(main.resolve(relative)), relative) }
    }

    private fun assertImportsAbsent(root: Path, vararg forbidden: String) {
        kotlinSources(root).forEach { path ->
            val source = Files.readString(path)
            forbidden.forEach { dependency ->
                assertFalse(
                    source.lineSequence().any { it.startsWith("import $dependency") },
                    "$path imports forbidden dependency $dependency"
                )
            }
        }
    }

    private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
    }
}
