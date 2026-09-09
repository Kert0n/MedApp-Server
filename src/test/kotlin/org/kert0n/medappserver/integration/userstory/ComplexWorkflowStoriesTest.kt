package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreateRequest
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.testutil.assertQty

@PostgresIntegrationTest
class ComplexWorkflowStoriesTest : HttpUserStoryTest() {

    /** История 17: общая аптечка от первого приглашения до выхода последнего участника. */
    @Test
    fun `соседи проходят полный жизненный цикл общей аптечки`() {
        val alice = actor("saga-alice")
        val bob = actor("saga-bob")
        val charlie = actor("saga-charlie")
        val homeKitId = Uuid.random()
        alice.api.createMedKit(MedKitCreateRequest(homeKitId)).expectBody<MedKitCreatedDTO>(201)
        listOf(bob, charlie).forEach { neighbour ->
            val invitation = alice.api.inviteTo(homeKitId).expectBody<InvitationDTO>(201)
            neighbour.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        }

        val allergyMeds = alice.api.createDrug(homeKitId, drugRequest("Allergy Meds", "60"))
            .expectBody<DrugSnapshotDTO>(201)
        val painkillers = alice.api.createDrug(homeKitId, drugRequest("Painkillers", "100"))
            .expectBody<DrugSnapshotDTO>(201)

        listOf(alice, bob, charlie).forEach { neighbour ->
            val visible = neighbour.api.getDrug(allergyMeds.drug.id).expectBody<DrugSnapshotDTO>(200)
            neighbour.api.createReservation(
                ReservationCreateRequest(allergyMeds.drug.id, BigDecimal("20"), visible.reservations.version)
            ).expectBody<ReservationDTO>(201)
        }
        listOf(bob, charlie).forEach { neighbour ->
            val visible = neighbour.api.getDrug(painkillers.drug.id).expectBody<DrugSnapshotDTO>(200)
            neighbour.api.createReservation(
                ReservationCreateRequest(painkillers.drug.id, BigDecimal("30"), visible.reservations.version)
            ).expectBody<ReservationDTO>(201)
        }

        val allergyBeforeIntake = bob.api.getDrug(allergyMeds.drug.id).expectBody<DrugSnapshotDTO>(200)
        val allergyAfterIntake = bob.api.recordIntake(
            allergyMeds.drug.id,
            IntakeRequest(BigDecimal("30"), allergyBeforeIntake.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertQty(30.0, allergyAfterIntake.drug.quantity)
        assertQty(60.0, allergyAfterIntake.reservations.total)
        assertQty(20.0, allergyAfterIntake.reservations.mine)

        val travelKitId = Uuid.random()
        alice.api.createMedKit(MedKitCreateRequest(travelKitId)).expectBody<MedKitCreatedDTO>(201)
        val painkillersBeforeMove = alice.api.getDrug(painkillers.drug.id).expectBody<DrugSnapshotDTO>(200)
        val movedPainkillers = alice.api.moveDrug(
            painkillers.drug.id,
            travelKitId,
            painkillersBeforeMove.drug.version
        ).expectBody<DrugSnapshotDTO>(200)
        assertEquals(travelKitId, movedPainkillers.drug.medKitId)
        assertQty(0.0, movedPainkillers.reservations.total)
        bob.api.getReservation(painkillers.drug.id).expectStatus(404)
        charlie.api.getReservation(painkillers.drug.id).expectStatus(404)

        val duoKitId = Uuid.random()
        alice.api.createMedKit(MedKitCreateRequest(duoKitId)).expectBody<MedKitCreatedDTO>(201)
        val duoInvitation = alice.api.inviteTo(duoKitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(duoInvitation.key)).expectBody<MedKitDTO>(201)

        alice.api.deleteMedKit(homeKitId, duoKitId).expectEmpty(204)
        alice.api.getMedKit(homeKitId).expectStatus(404)
        val migratedAllergy = alice.api.getDrug(allergyMeds.drug.id).expectBody<DrugSnapshotDTO>(200)
        assertEquals(duoKitId, migratedAllergy.drug.medKitId)
        assertQty(40.0, migratedAllergy.reservations.total)
        assertQty(20.0, migratedAllergy.reservations.mine)
        assertQty(20.0, bob.api.getReservation(allergyMeds.drug.id).expectBody<ReservationDTO>(200).amount)
        charlie.api.getReservation(allergyMeds.drug.id).expectStatus(404)

        bob.api.leave(duoKitId).expectEmpty(204)
        val aliceAlone = alice.api.getMedKit(duoKitId).expectBody<MedKitDTO>(200)
        assertEquals(1L, aliceAlone.userCount)
        assertQty(20.0, aliceAlone.drugs.single().reservations.total)

        alice.api.leave(duoKitId).expectEmpty(204)
        alice.api.getMedKit(duoKitId).expectStatus(404)
        alice.api.getDrug(allergyMeds.drug.id).expectStatus(404)
    }

    /** История 18: правка, приём, сужение доступа и уничтожение одной упаковки. */
    @Test
    fun `изменения упаковки и броней остаются согласованными при переносе и удалении`() {
        val alice = actor("lifecycle-alice")
        val bob = actor("lifecycle-bob")
        val sourceKitId = Uuid.random()
        val targetKitId = Uuid.random()
        alice.api.createMedKit(MedKitCreateRequest(sourceKitId)).expectBody<MedKitCreatedDTO>(201)
        alice.api.createMedKit(MedKitCreateRequest(targetKitId)).expectBody<MedKitCreatedDTO>(201)
        val invitation = alice.api.inviteTo(sourceKitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        val drug = alice.api.createDrug(sourceKitId, drugRequest("LifePill", "100"))
            .expectBody<DrugSnapshotDTO>(201)

        listOf(alice, bob).forEach { participant ->
            val visible = participant.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
            participant.api.createReservation(
                ReservationCreateRequest(drug.drug.id, BigDecimal("40"), visible.reservations.version)
            ).expectBody<ReservationDTO>(201)
        }
        val bobBeforeRaise = bob.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
        val raised = bob.api.patchReservation(
            drug.drug.id,
            ReservationPatchRequest(BigDecimal("60"), bobBeforeRaise.reservations.version)
        ).expectBody<ReservationDTO>(200)
        assertQty(60.0, raised.amount)

        val beforeIntake = alice.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
        val afterIntake = alice.api.recordIntake(
            drug.drug.id,
            IntakeRequest(BigDecimal("50"), beforeIntake.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertQty(50.0, afterIntake.drug.quantity)
        assertQty(100.0, afterIntake.reservations.total)
        assertQty(40.0, afterIntake.reservations.mine)

        val moved = alice.api.moveDrug(drug.drug.id, targetKitId, afterIntake.drug.version)
            .expectBody<DrugSnapshotDTO>(200)
        assertEquals(targetKitId, moved.drug.medKitId)
        assertQty(40.0, moved.reservations.total)
        assertQty(40.0, moved.reservations.mine)
        bob.api.getDrug(drug.drug.id).expectStatus(404)
        bob.api.getReservation(drug.drug.id).expectStatus(404)

        alice.api.deleteDrug(drug.drug.id, moved.drug.version).expectEmpty(204)
        alice.api.getDrug(drug.drug.id).expectStatus(404)
        alice.api.getReservation(drug.drug.id).expectStatus(404)
    }

    /** История 19: право переноса даёт membership, а не собственная бронь. */
    @Test
    fun `сосед без собственной брони переносит общую упаковку в свою аптечку`() {
        val alice = actor("unreserved-alice")
        val bob = actor("unreserved-bob")
        val sharedKitId = Uuid.random()
        val bobKitId = Uuid.random()
        alice.api.createMedKit(MedKitCreateRequest(sharedKitId)).expectBody<MedKitCreatedDTO>(201)
        val invitation = alice.api.inviteTo(sharedKitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        bob.api.createMedKit(MedKitCreateRequest(bobKitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = alice.api.createDrug(sharedKitId, drugRequest("Shared Meds", "10"))
            .expectBody<DrugSnapshotDTO>(201)

        val bobView = bob.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
        assertNull(bobView.reservations.mine)
        val moved = bob.api.moveDrug(drug.drug.id, bobKitId, bobView.drug.version)
            .expectBody<DrugSnapshotDTO>(200)
        assertEquals(bobKitId, moved.drug.medKitId)
        assertQty(0.0, moved.reservations.total)
        assertNull(moved.reservations.mine)

        val bobKit = bob.api.getMedKit(bobKitId).expectBody<MedKitDTO>(200)
        assertEquals(setOf(drug.drug.id), bobKit.drugs.map { it.drug.id }.toSet())
        alice.api.getDrug(drug.drug.id).expectStatus(404)
    }
}
