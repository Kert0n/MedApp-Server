package org.kert0n.medappserver.services.orchestrators

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.services.models.DrugPatch
import org.kert0n.medappserver.testutil.MedKitFixture
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DrugCommandServiceTest {

    @Autowired private lateinit var commands: DrugCommandService
    @Autowired private lateinit var medKits: MedKitFixture
    @Autowired private lateinit var treatmentPlans: TreatmentPlanService
    @Autowired private lateinit var drugs: DrugRepository
    @Autowired private lateinit var db: DatabaseTestHelper

    @Test
    fun `create requires access and positive stock`() {
        val owner = db.freshUser("owner")
        val outsider = db.freshUser("outsider")
        val medKit = medKits.createNew(owner.id)

        assertThrows<ResponseStatusException> {
            commands.create(outsider.id, medKit.id, creation(qty(10.0)))
        }
        assertThrows<ResponseStatusException> {
            commands.create(owner.id, medKit.id, creation(qty(0.0)))
        }

        val created = commands.create(owner.id, medKit.id, creation(qty(10.0)))
        assertEquals(medKit.id, created.medKitId)
        assertQty(10.0, created.quantity)
    }

    @Test
    fun `patch treats null as no change and quantity as increase only`() {
        val owner = db.freshUser("owner")
        val medKit = medKits.createNew(owner.id)
        val drug = db.freshDrug(medKit, 10.0)
        db.flushAndClear()

        commands.patch(owner.id, drug.id, DrugPatch(name = "Renamed"))
        assertThrows<ResponseStatusException> {
            commands.patch(owner.id, drug.id, DrugPatch(quantity = qty(10.0)))
        }
        assertThrows<ResponseStatusException> {
            commands.patch(owner.id, drug.id, DrugPatch(quantity = qty(5.0)))
        }

        val increased = commands.patch(owner.id, drug.id, DrugPatch(quantity = qty(20.0)))
        assertEquals("Renamed", increased.name)
        assertQty(20.0, increased.quantity)
    }

    @Test
    fun `consume reconciles plans and database cascade deletes exhausted drug`() {
        val alice = db.freshUser("alice")
        val bob = db.freshUser("bob")
        val medKit = medKits.createNew(alice.id)
        medKits.addUserToMedKit(medKit.id, bob.id)
        val drug = db.freshDrug(medKit, 100.0)
        db.flushAndClear()
        treatmentPlans.create(alice.id, drug.id, qty(60.0))
        treatmentPlans.create(bob.id, drug.id, qty(40.0))
        db.flushAndClear()

        val remaining = commands.consume(alice.id, drug.id, qty(50.0))
        db.flushAndClear()
        assertQty(50.0, remaining?.quantity)
        assertQty(30.0, db.userPlan(alice.id, drug.id))
        assertQty(20.0, db.userPlan(bob.id, drug.id))

        assertNull(commands.consume(alice.id, drug.id, qty(50.0)))
        db.flushAndClear()
        assertNull(drugs.findByIdOrNull(drug.id))
    }

    @Test
    fun `move keeps only plans whose users belong to target medkit`() {
        val alice = db.freshUser("alice")
        val bob = db.freshUser("bob")
        val source = medKits.createNew(alice.id)
        medKits.addUserToMedKit(source.id, bob.id)
        val target = medKits.createNew(alice.id)
        val drug = db.freshDrug(source, 100.0)
        db.flushAndClear()
        treatmentPlans.create(alice.id, drug.id, qty(20.0))
        treatmentPlans.create(bob.id, drug.id, qty(20.0))
        db.flushAndClear()

        val moved = commands.move(alice.id, drug.id, target.id)
        assertEquals(target.id, moved.medKitId)
        assertQty(20.0, db.userPlan(alice.id, drug.id))
        assertNull(db.userPlan(bob.id, drug.id))
    }

    @Test
    fun `empty patch is a no-op and keeps nullable fields`() {
        val owner = db.freshUser("owner")
        val medKit = medKits.createNew(owner.id)
        val drug = db.freshDrug(medKit, 10.0).also {
            it.description = "keep"
            it.manufacturer = "maker"
        }
        db.flushAndClear()

        val result = commands.patch(owner.id, drug.id, DrugPatch())
        db.flushAndClear()

        assertEquals("keep", result.description)
        assertEquals("maker", result.manufacturer)
        assertQty(10.0, result.quantity)
    }

    @Test
    fun `rejected consumption rolls back stock and plans`() {
        val owner = db.freshUser("owner")
        val medKit = medKits.createNew(owner.id)
        val drug = db.freshDrug(medKit, 10.0)
        db.flushAndClear()
        treatmentPlans.create(owner.id, drug.id, qty(5.0))
        db.flushAndClear()

        assertThrows<ResponseStatusException> {
            commands.consume(owner.id, drug.id, qty(11.0))
        }
        assertThrows<ResponseStatusException> {
            commands.consume(owner.id, drug.id, qty(0.0))
        }
        db.flushAndClear()

        val stored = drugs.findByIdOrNull(drug.id)
        assertNotNull(stored)
        assertQty(10.0, stored.quantity)
        assertQty(5.0, db.userPlan(owner.id, drug.id))
    }

    private fun creation(quantity: java.math.BigDecimal) =
        DrugCreation(name = "Drug", quantity = quantity, quantityUnit = "таб")
}
