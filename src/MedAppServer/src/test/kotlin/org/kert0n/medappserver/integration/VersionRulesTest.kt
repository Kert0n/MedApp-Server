package org.kert0n.medappserver.integration

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.StaleAggregateVersion
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.createPlanLatest
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Когда версия двигается, а когда нет.
 *
 * Правило одно: ровно на единицу за фактическое изменение состояния. Отсюда три вещи, которые
 * приходится проверять отдельно, потому что каждая ломается по-своему: команда, ничего не
 * изменившая, версию не трогает; упавшая команда не оставляет её продвинутой; изменение,
 * лежащее не в строке препарата, а в его планах, всё равно продвигает версию корня.
 *
 * Последнее — не теория. Hibernate считает изменившейся свою строку, а план лежит в другой
 * таблице, и до явного продвижения версия после создания плана оставалась прежней; это и
 * позволило бы команде, собранной до появления плана, выполниться после него.
 */
@PostgresIntegrationTest
class VersionRulesTest {

    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var drugService: DrugService
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var orchestrator: MedKitDrugOrchestrator
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private val tx: TransactionTemplate by lazy { TransactionTemplate(transactionManager) }

    private fun scenario(): Triple<UUID, UUID, UUID> = tx.execute {
        val alice = dbHelper.freshUser("versions")
        val kit = medKitService.create(alice.id)
        val drug = dbHelper.freshDrug(kit.id, 100.0)
        Triple(alice.id, kit.id, drug.id)
    }!!

    private fun versionOf(drugId: UUID): Long = dbHelper.requireDrug(drugId).version

    @Test
    fun `новый препарат начинается с нулевой версии`() {
        val (_, _, drugId) = scenario()
        assertEquals(0L, versionOf(drugId))
    }

    @Test
    fun `успешная команда продвигает версию ровно на единицу`() {
        val (alice, _, drugId) = scenario()

        tx.execute { drugService.consume(drugId, qty(1.0), alice, 0) }
        assertEquals(1L, versionOf(drugId))

        tx.execute { drugService.consume(drugId, qty(1.0), alice, 1) }
        assertEquals(2L, versionOf(drugId))
    }

    /** План — часть препарата: его появление, изменение и отмена двигают версию корня. */
    @Test
    fun `команды плана продвигают версию препарата`() {
        val (alice, _, drugId) = scenario()

        tx.execute { drugService.createPlan(alice, drugId, qty(10.0), 0) }
        assertEquals(1L, versionOf(drugId))

        tx.execute { drugService.changePlan(alice, drugId, qty(20.0), 1) }
        assertEquals(2L, versionOf(drugId))

        tx.execute { drugService.cancelPlan(alice, drugId, 2) }
        assertEquals(3L, versionOf(drugId))
    }

    /**
     * PATCH, ничего не меняющий, версию не двигает.
     *
     * Иначе клиент, повторивший запрос от плохой связи, обесценивал бы тег, который сам же
     * держит в руках, — и следующая его команда получала бы 409 на ровном месте.
     */
    @Test
    fun `команда без изменений версию не двигает`() {
        val (alice, _, drugId) = scenario()
        val before = dbHelper.requireDrug(drugId)

        tx.execute { drugService.update(drugId, DrugPatchRequest(name = before.name), alice, 0) }

        assertEquals(0L, versionOf(drugId), "переписали то же самое — состояние не изменилось")
    }

    @Test
    fun `изменение части полей двигает версию один раз, а не по полю`() {
        val (alice, _, drugId) = scenario()

        tx.execute {
            drugService.update(
                drugId,
                DrugPatchRequest(name = "Renamed", category = "vitamins", quantity = qty(80.0)),
                alice,
                0
            )
        }

        assertEquals(1L, versionOf(drugId))
    }

    @Test
    fun `отвергнутая команда версию не продвигает`() {
        val (alice, _, drugId) = scenario()

        assertFailsWith<DomainRuleViolated> {
            tx.execute { drugService.consume(drugId, qty(500.0), alice, 0) }
        }

        assertEquals(0L, versionOf(drugId))
        assertEquals(qty(100.0), dbHelper.drugQuantity(drugId))
    }

    @Test
    fun `устаревшая версия отвергается до применения правил`() {
        val (alice, _, drugId) = scenario()
        tx.execute { drugService.consume(drugId, qty(1.0), alice, 0) }

        // Само по себе списание допустимо: остатка хватает. Отвергается оно за версию.
        assertFailsWith<StaleAggregateVersion> {
            tx.execute { drugService.consume(drugId, qty(1.0), alice, 0) }
        }

        assertEquals(1L, versionOf(drugId))
        assertEquals(qty(99.0), dbHelper.drugQuantity(drugId))
    }

    // ── Аптечка ──────────────────────────────────────────────────────────────────

    @Test
    fun `вступление и выход двигают версию аптечки`() {
        val (alice, kitId, _) = scenario()
        val bob = tx.execute { dbHelper.freshUser("bob-versions") }!!
        assertEquals(0L, medKitService.requireById(kitId).version)

        tx.execute { medKitService.joinByInvitation(medKitService.invite(kitId, alice), bob.id) }
        assertEquals(1L, medKitService.requireById(kitId).version)

        tx.execute { orchestrator.leaveMedKit(kitId, bob.id, 1) }
        assertEquals(2L, medKitService.requireById(kitId).version)
    }

    /**
     * Массовые операции идут мимо dirty checking, поэтому версию в них двигает явный
     * `version = version + 1`. Без него команда, собранная до выхода участника, выполнилась бы
     * после — уже по другому набору планов.
     */
    @Test
    fun `выход участника двигает версии препаратов, где у него был план`() {
        val (alice, kitId, plannedDrugId) = scenario()
        val bob = tx.execute { dbHelper.freshUser("bulk-versions") }!!
        tx.execute { medKitService.joinByInvitation(medKitService.invite(kitId, alice), bob.id) }
        val untouched = tx.execute { dbHelper.freshDrug(kitId, 50.0) }!!
        tx.execute { drugService.createPlanLatest(bob.id, plannedDrugId, qty(10.0)) }

        val plannedBefore = versionOf(plannedDrugId)
        val untouchedBefore = versionOf(untouched.id)

        tx.execute { orchestrator.leaveMedKit(kitId, bob.id, medKitService.requireById(kitId).version) }

        assertEquals(plannedBefore + 1, versionOf(plannedDrugId), "план исчез — препарат изменился")
        assertEquals(
            untouchedBefore,
            versionOf(untouched.id),
            "препарат без плана выходящего не менялся, и отменять команды по нему незачем"
        )
    }
}
