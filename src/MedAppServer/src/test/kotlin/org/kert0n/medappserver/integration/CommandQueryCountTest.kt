package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.application.DrugApplicationService
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.RecordedSql
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Команда стоит одинаково при любом объёме — включая ту, что уносит целую аптечку.
 *
 * Команды опаснее чтений: у них есть каскады и массовые правки, а неудачный код там пишет по
 * строке за раз и выглядит при этом совершенно обычно. Именно так в #93 уехало удвоенное чтение
 * состава при удалении.
 *
 * Размер задаётся тем, чего в команде много: числом упаковок в аптечке и числом чужих броней,
 * которые команда обязана снять.
 */
@PostgresIntegrationTest
class CommandQueryCountTest {

    @Autowired private lateinit var medKits: MedKitApplicationService
    @Autowired private lateinit var drugs: DrugApplicationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    /**
     * Удаление аптечки с переносом: упаковки переезжают, чужие брони снимаются.
     *
     * Самая тяжёлая команда системы — и единственная, где число задетых пачек заранее не
     * ограничено ничем.
     */
    @Test
    fun `удаление аптечки с переносом не растёт с числом упаковок`() {
        val forOne = countDeleteWithTransfer("transfer-1", drugs = 1)
        val forMany = countDeleteWithTransfer("transfer-100", drugs = 100)

        assertEquals(
            forOne, forMany,
            "перенос при удалении обязан стоить одинаково: одна пачка — $forOne запросов, сто — $forMany"
        )
    }

    /** Удаление без переноса: содержимое уносит каскад, и его цена от размера не зависит. */
    @Test
    fun `удаление аптечки без переноса не растёт с числом упаковок`() {
        val forOne = countPlainDelete("plain-1", drugs = 1)
        val forMany = countPlainDelete("plain-100", drugs = 100)

        assertEquals(forOne, forMany, "каскад стоит одинаково: $forOne против $forMany")
    }

    /** Выход участника: его брони внутри аптечки уносит ключ членства, а не цикл по ним. */
    @Test
    fun `выход участника не растёт с числом его броней`() {
        val forOne = countLeave("leave-1", claims = 1)
        val forMany = countLeave("leave-100", claims = 100)

        assertEquals(forOne, forMany, "выход стоит одинаково: $forOne против $forMany")
    }

    /** Переезд одной пачки: брони тех, кто цель не видит, снимаются одним запросом. */
    @Test
    fun `переезд упаковки не растёт с числом чужих броней`() {
        val forOne = countMove("move-1", strangers = 1)
        val forMany = countMove("move-30", strangers = 30)

        assertEquals(forOne, forMany, "переезд стоит одинаково: $forOne против $forMany")
    }

    /** Уничтожение пачки: брони на неё уходят одним запросом, сколько бы их ни было. */
    @Test
    fun `удаление упаковки не растёт с числом броней на неё`() {
        val forOne = countDrugDelete("drop-1", claims = 1)
        val forMany = countDrugDelete("drop-30", claims = 30)

        assertEquals(forOne, forMany, "уничтожение стоит одинаково: $forOne против $forMany")
    }

    // ── Сценарии ─────────────────────────────────────────────────────────────────

    /**
     * Аптечка на двоих, у постороннего бронь на каждой пачке, и всё это переезжает.
     *
     * Брони Боба обязаны исчезнуть: в целевую аптечку он не входит. Именно их снятие и заставляет
     * пересчитать снимки по всем задетым пачкам.
     */
    private fun countDeleteWithTransfer(name: String, drugs: Int): Int {
        val alice = dbHelper.freshUser("$name-a").id
        val bob = dbHelper.freshUser("$name-b").id
        val source = dbHelper.freshMedKit(alice).id
        dbHelper.join(source, alice, bob)
        val target = dbHelper.freshMedKit(alice).id
        repeat(drugs) {
            val drug = dbHelper.freshDrug(source, 10.0)
            dbHelper.reserve(bob, drug.id, qty(1.0))
        }

        return count("удаление с переносом, упаковок — $drugs") {
            medKits.delete(source, dbHelper.medKitVersion(source), alice, target)
        }
    }

    private fun countPlainDelete(name: String, drugs: Int): Int {
        val owner = dbHelper.freshUser(name).id
        val kit = dbHelper.freshMedKit(owner).id
        repeat(drugs) { dbHelper.freshDrug(kit, 10.0) }

        return count("удаление без переноса, упаковок — $drugs") {
            medKits.delete(kit, dbHelper.medKitVersion(kit), owner)
        }
    }

    private fun countLeave(name: String, claims: Int): Int {
        val alice = dbHelper.freshUser("$name-a").id
        val bob = dbHelper.freshUser("$name-b").id
        val kit = dbHelper.freshMedKit(alice).id
        dbHelper.join(kit, alice, bob)
        repeat(claims) {
            val drug = dbHelper.freshDrug(kit, 10.0)
            dbHelper.reserve(bob, drug.id, qty(1.0))
        }

        return count("выход участника, броней — $claims") {
            medKits.leave(kit, dbHelper.medKitVersion(kit), bob)
        }
    }

    private fun countMove(name: String, strangers: Int): Int {
        val alice = dbHelper.freshUser("$name-a").id
        val source = dbHelper.freshMedKit(alice).id
        val target = dbHelper.freshMedKit(alice).id
        val drug = dbHelper.freshDrug(source, 100.0)
        repeat(strangers) {
            val stranger = dbHelper.freshUser("$name-s$it").id
            dbHelper.join(source, alice, stranger)
            dbHelper.reserve(stranger, drug.id, qty(1.0))
        }

        return count("переезд упаковки, чужих броней — $strangers") {
            drugs.moveToMedKit(drug.id, target, dbHelper.drugVersion(drug.id), alice)
        }
    }

    private fun countDrugDelete(name: String, claims: Int): Int {
        val alice = dbHelper.freshUser("$name-a").id
        val kit = dbHelper.freshMedKit(alice).id
        val drug = dbHelper.freshDrug(kit, 100.0)
        repeat(claims) {
            val member = dbHelper.freshUser("$name-m$it").id
            dbHelper.join(kit, alice, member)
            dbHelper.reserve(member, drug.id, qty(1.0))
        }

        return count("уничтожение упаковки, броней — $claims") {
            drugs.delete(drug.id, dbHelper.drugVersion(drug.id), alice)
        }
    }

    /** Число операторов одной команды. Пустой замер — провал: проверку делает сам помощник. */
    private fun count(what: String, command: () -> Unit): Int {
        val statements = RecordedSql.inTransaction(transactionManager, command)
        println("запросов — $what: ${statements.size}")
        return statements.size
    }
}
