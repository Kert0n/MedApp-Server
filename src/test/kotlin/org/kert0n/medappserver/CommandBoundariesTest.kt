package org.kert0n.medappserver

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import kotlin.reflect.KClass
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.jvm.jvmErasure
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Service
import org.kert0n.medappserver.architecturefixture.services.aggregate.WrongDrugStoreOwner
import org.kert0n.medappserver.architecturefixture.services.application.ApplicationMutatesAggregate
import org.kert0n.medappserver.architecturefixture.services.application.ApplicationUsesRootAccess
import org.kert0n.medappserver.architecturefixture.services.orchestrator.CommandCallingCommand
import org.kert0n.medappserver.architecturefixture.services.orchestrator.CommandTakingAggregate

/**
 * Командная граница проверяется по типам и вызовам, а не по тексту исходников.
 *
 * Предыдущая проверка искала в списке параметров имя типа перед двоеточием. В Kotlin тип стоит
 * после двоеточия, поэтому она проверяла наличие параметра с именем `Drug` — такого не бывает, и
 * упасть она не могла ни при каком нарушении. Отсюда правило этого файла: **каждое утверждение
 * обязано иметь отрицательную фикстуру**, на которой видно, что оно вообще способно сработать.
 *
 * Про видимость: `internal` в JVM публичен и отличается только манглингом имени
 * (`moveAllUnderAccess$org_kert0n_MedAppServer`). Там, где важна именно Kotlin-видимость,
 * проверка идёт через `kotlin-reflect`, а не через ArchUnit.
 */
class CommandBoundariesTest {

    private val production: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("org.kert0n.medappserver")

    @Test
    fun `прикладной слой не управляет корневыми блокировками`() {
        APPLICATION_CANNOT_HOLD_ACCESS.check(production)
    }

    @Test
    fun `прикладной слой не пишет мимо сценария`() {
        APPLICATION_CANNOT_MUTATE_AGGREGATES.check(production)
    }

    @Test
    fun `каждое хранилище принадлежит своему агрегатному сервису`() {
        STORE_OWNERSHIP.forEach { it.check(production) }
    }

    @Test
    fun `сценарий не вызывает чужой публичный сценарий`() {
        COMMANDS_DO_NOT_NEST.check(production)
    }

    /**
     * Публичная команда принимает идентификаторы, а не снимок чтения.
     *
     * `Drug`, `MedKit` и `Reservation` — результат прошлого `SELECT`; они не доказывают, что
     * доступ есть сейчас. Команда обязана удержать его сама и перечитать состояние под ним.
     */
    @Test
    fun `публичная команда принимает идентификатор а не снимок агрегата`() {
        val commands = production.filter { it.packageName.endsWith(".services.orchestrator") }
        assertTrue(commands.isNotEmpty(), "сценарные сервисы не найдены — тест смотрит не туда")

        val offenders = commands.flatMap { aggregateTakingCommandsOf(it.reflect().kotlin) }

        assertEquals(
            emptyList(), offenders,
            "публичная команда приняла снимок чтения; она обязана сама получить доступ и перечитать состояние"
        )
    }

    /** Правило без отрицательной фикстуры ничего не гарантирует — см. KDoc класса. */
    @Test
    fun `отрицательные фикстуры доказывают различимость правил`() {
        assertRejects(APPLICATION_CANNOT_HOLD_ACCESS, ApplicationUsesRootAccess::class)
        assertRejects(APPLICATION_CANNOT_MUTATE_AGGREGATES, ApplicationMutatesAggregate::class)
        assertRejects(COMMANDS_DO_NOT_NEST, CommandCallingCommand::class)
        assertRejects(STORE_OWNERSHIP.single { it.description.contains("DrugStore") }, WrongDrugStoreOwner::class)

        assertEquals(
            listOf("CommandTakingAggregate.place"),
            aggregateTakingCommandsOf(CommandTakingAggregate::class),
            "проверка сигнатур не увидела команду, принимающую снимок агрегата"
        )
    }

    // ── Внутреннее ──────────────────────────────────────────────────────────────

    /** Публичные по Kotlin функции класса, среди параметров которых есть корень агрегата. */
    private fun aggregateTakingCommandsOf(type: KClass<*>): List<String> =
        type.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .filter { function ->
                function.parameters.any { it.type.jvmErasure.simpleName in AGGREGATE_ROOTS }
            }
            .map { "${type.simpleName}.${it.name}" }
            .sorted()

    private fun assertRejects(rule: ArchRule, fixture: KClass<*>) {
        assertFailsWith<AssertionError>("правило не заметило нарушения в ${fixture.simpleName}") {
            rule.check(ClassFileImporter().importClasses(fixture.java))
        }
    }

    private companion object {
        val AGGREGATE_ROOTS = setOf("Drug", "MedKit", "Reservation")

        /**
         * Сценарный сервис — это Spring-бин пакета `orchestrator`, а не всё, что там лежит.
         *
         * Иначе под правило попадают вложенные типы запросов: у `SyncRequest` тоже публичные
         * методы, но вход сценария он не открывает.
         */
        fun JavaClass.isCommandSurface() =
            packageName.endsWith(".services.orchestrator") && isAnnotatedWith(Service::class.java)

        val APPLICATION_CANNOT_HOLD_ACCESS: ArchRule = noClasses()
            .that().resideInAPackage("..services.application..")
            .should().dependOnClassesThat().haveSimpleName("MedKitAccessService")
            .because("режим доступа принадлежит сценарию, а не HTTP-фасаду")

        /**
         * Фасад читает агрегаты и переводит DTO, но не пишет.
         *
         * `MedKitService.create` — единственное исключение и единственная запись без блокировки:
         * у новой аптечки ещё нет ни участников, ни содержимого, и удерживать нечего.
         */
        val APPLICATION_CANNOT_MUTATE_AGGREGATES: ArchRule = noClasses()
            .that().resideInAPackage("..services.application..")
            .should().callMethodWhere(
                com.tngtech.archunit.base.DescribedPredicate.describe("мутирующий метод сервиса агрегата") { call ->
                    val owner = call.targetOwner
                    owner.packageName.endsWith(".services.aggregate") &&
                        AGGREGATE_MUTATIONS[owner.simpleName].orEmpty().contains(call.name)
                }
            )
            .because("запись принадлежит сценарию, который удерживает доступ на всё её время")

        val AGGREGATE_MUTATIONS: Map<String, Set<String>> = mapOf(
            "DrugService" to setOf("create", "update", "consume", "delete", "moveAll", "moveTo"),
            "ReservationService" to setOf(
                "create", "changeTo", "cancel",
                "dropOnDrug", "dropOnDrugExcept", "dropInMedKitExcept", "dropOfMember"
            ),
            "MedKitService" to setOf("addMembership", "removeMembership", "deleteRoot")
        )

        val COMMANDS_DO_NOT_NEST: ArchRule = noClasses()
            .that().resideInAPackage("..services.orchestrator..")
            .should().callMethodWhere(
                com.tngtech.archunit.base.DescribedPredicate.describe("публичный метод чужого сценария") { call ->
                    call.targetOwner.isCommandSurface() &&
                        call.originOwner.isCommandSurface() &&
                        call.targetOwner != call.originOwner &&
                        // Манглинга нет — значит метод публичен и по Kotlin, то есть это вход
                        // сценария, а не его форма «доступ уже удержан».
                        !call.name.contains('$')
                }
            )
            .because("вложенный вход сценария означал бы повторную блокировку или повышение режима")

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
