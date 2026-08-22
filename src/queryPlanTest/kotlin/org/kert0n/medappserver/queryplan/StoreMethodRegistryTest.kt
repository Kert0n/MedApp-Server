package org.kert0n.medappserver.queryplan

import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.db.store.CatalogueStore
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.db.store.ReservationStore
import org.kert0n.medappserver.db.store.UserStore

/**
 * Ни один метод хранилища не остаётся без решения.
 *
 * Набор измерений легко отстаёт от кода: метод добавили, план никто не посмотрел, а узнают об
 * этом на проде. Реестр не даёт этому случиться молча — новый метод обязан попасть либо в
 * измеренные, либо в исключённые, и исключение объясняется здесь же.
 *
 * Реестр перечисляет имена, а не проверяет покрытие по факту: сопоставлять измерение с методом
 * автоматически можно только по записанному SQL, а он у методов совпадает. Зато забыть про
 * новый метод нельзя.
 */
class StoreMethodRegistryTest {

    @Test
    fun `каждый публичный метод хранилища либо измерен, либо исключён с причиной`() {
        val unaccounted = STORES
            .flatMap { store ->
                store.declaredMemberFunctions
                    .filter { it.visibility == KVisibility.PUBLIC }
                    .map { "${store.simpleName}.${it.name}" }
            }
            .distinct()
            .filterNot { it in MEASURED || it in EXCLUDED }

        assertTrue(
            unaccounted.isEmpty(),
            "метод появился, а решения по нему нет — измерить или исключить с причиной: $unaccounted"
        )
    }

    /** Исключение — это решение, а не умолчание: оно перестаёт быть верным, и это видно. */
    @Test
    fun `исключённые методы не числятся заодно измеренными`() {
        val both = EXCLUDED.keys.filter { it in MEASURED }
        assertTrue(both.isEmpty(), "метод и измерен, и исключён сразу: $both")
    }

    private companion object {
        val STORES = listOf(
            CatalogueStore::class, DrugStore::class, MedKitStore::class,
            ReservationStore::class, UserStore::class
        )

        /** Планы этих методов сняты в наборе. */
        val MEASURED = setOf(
            "DrugStore.find",
            "DrugStore.findAllInMedKit",
            "DrugStore.save",
            "CatalogueStore.searchTemplates",
            "ReservationStore.findAllOfUser"
        )

        /**
         * Исключено осознанно, с причиной у каждого.
         *
         * Причины трёх видов: запрос идёт по первичному ключу и плану взяться неоткуда;
         * таблица словарная и полный проход по ней дешевле индекса; метод пишет одну строку,
         * найденную ключом, — там нечего выбирать.
         */
        val EXCLUDED = mapOf(
            "UserStore.findById" to "по первичному ключу",
            "UserStore.insert" to "вставка одной строки",
            "MedKitStore.find" to "по первичному ключу и членству, покрыто скоупом упаковок",
            "MedKitStore.findAllOfUser" to "то же соединение членства, покрыто скоупом упаковок",
            "MedKitStore.insert" to "вставка одной строки",
            "MedKitStore.save" to "запись по первичному ключу с предикатом версии",
            "MedKitStore.delete" to "удаление по первичному ключу",
            "DrugStore.findAllOfUser" to "то же соединение членства, что и содержимое аптечки",
            "DrugStore.insert" to "вставка одной строки",
            "DrugStore.delete" to "удаление по первичному ключу",
            "DrugStore.moveAllToMedKit" to "массовая запись по аптечке, план тот же, что у содержимого",
            "CatalogueStore.findTemplate" to "по первичному ключу",
            "CatalogueStore.findQuantityUnit" to "словарь из единиц измерения",
            "CatalogueStore.findFormType" to "словарь из форм выпуска",
            "CatalogueStore.quantityUnits" to "словарь целиком, десяток строк",
            "CatalogueStore.formTypes" to "словарь целиком, десяток строк",
            "ReservationStore.find" to "по первичному ключу",
            "ReservationStore.findAllOfDrugs" to "покрыто снимками броней",
            "ReservationStore.snapshotsOf" to "то же чтение плюс версии по первичному ключу",
            "ReservationStore.snapshotOf" to "то же для одной упаковки",
            "ReservationStore.insert" to "вставка одной строки",
            "ReservationStore.save" to "запись по первичному ключу",
            "ReservationStore.delete" to "удаление по первичному ключу",
            "ReservationStore.deleteOfDrug" to "удаление по внешнему ключу упаковки",
            "ReservationStore.deleteInMedKitExcept" to "массовое снятие, план тот же, что у чтения по аптечке",
            "ReservationStore.deleteOfDrugExcept" to "массовое снятие по одной упаковке"
        )
    }
}
