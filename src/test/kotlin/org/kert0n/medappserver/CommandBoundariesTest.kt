package org.kert0n.medappserver

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.architecturefixture.services.application.ApplicationUsesRootAccess
import org.kert0n.medappserver.architecturefixture.services.aggregate.WrongDrugStoreOwner
import org.kert0n.medappserver.testutil.sourcesIn

/** Командная граница проверяет вызовы и типы, а не соглашения об именах параметров. */
class CommandBoundariesTest {

    private val production: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("org.kert0n.medappserver")

    @Test
    fun `прикладной слой не управляет корневыми блокировками`() {
        APPLICATION_CANNOT_USE_ACCESS.check(production)
    }

    @Test
    fun `каждое хранилище принадлежит своему агрегатному сервису`() {
        STORE_OWNERSHIP.forEach { it.check(production) }
    }

    @Test
    fun `публичная сценарная команда принимает идентификатор а не снимок агрегата`() {
        val sources: List<Path> = sourcesIn("services/orchestrator")
        assertTrue(sources.isNotEmpty(), "сценарные сервисы не найдены — тест смотрит не туда")

        val offenders = sources.flatMap { file ->
            Regex(
                "@Transactional\\(propagation = MANDATORY\\)\\s*\\n\\s*fun (\\w+)\\(([^)]*)\\)",
                RegexOption.MULTILINE
            ).findAll(Files.readString(file))
                .filter { AGGREGATE_PARAMETER.containsMatchIn(it.groupValues[2]) }
                .map { "${file.name}.${it.groupValues[1]}" }
                .toList()
        }

        assertEquals(
            emptyList(), offenders,
            "публичная команда приняла снимок чтения; она обязана сама получить доступ и перечитать состояние"
        )
    }

    @Test
    fun `отрицательные фикстуры доказывают различимость правил`() {
        assertFailsWith<AssertionError> {
            APPLICATION_CANNOT_USE_ACCESS.check(ClassFileImporter().importClasses(ApplicationUsesRootAccess::class.java))
        }
        assertFailsWith<AssertionError> {
            STORE_OWNERSHIP.single { it.description.contains("DrugStore") }
                .check(ClassFileImporter().importClasses(WrongDrugStoreOwner::class.java))
        }
    }

    private companion object {
        val AGGREGATE_PARAMETER = Regex("\\b(Drug|MedKit|Reservation)\\s*:")

        val APPLICATION_CANNOT_USE_ACCESS: ArchRule = noClasses()
            .that().resideInAPackage("..services.application..")
            .should().dependOnClassesThat().haveSimpleName("MedKitAccessService")
            .because("режим доступа принадлежит сценарию, а не HTTP-фасаду")

        val STORE_OWNERSHIP: List<ArchRule> = listOf(
            noClasses().that().resideOutsideOfPackage("..db.store..").and().doNotHaveSimpleName("DrugService")
                .should().dependOnClassesThat().haveSimpleName("DrugStore"),
            noClasses().that().resideOutsideOfPackage("..db.store..").and().doNotHaveSimpleName("ReservationService")
                .should().dependOnClassesThat().haveSimpleName("ReservationStore"),
            noClasses().that().resideOutsideOfPackage("..db.store..").and().doNotHaveSimpleName("MedKitService")
                .and().doNotHaveSimpleName("MedKitAccessService")
                .should().dependOnClassesThat().haveSimpleName("MedKitStore"),
            noClasses().that().resideOutsideOfPackage("..db.store..").and().doNotHaveSimpleName("CatalogueService")
                .should().dependOnClassesThat().haveSimpleName("CatalogueStore"),
            noClasses().that().resideOutsideOfPackage("..db.store..").and().doNotHaveSimpleName("UserService")
                .should().dependOnClassesThat().haveSimpleName("UserStore")
        )
    }
}
