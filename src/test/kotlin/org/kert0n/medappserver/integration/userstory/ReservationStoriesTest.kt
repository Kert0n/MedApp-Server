package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
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
class ReservationStoriesTest : HttpUserStoryTest() {

    /** История 6: бронь и приём — независимые публичные действия. */
    @Test
    fun `пользователь бронирует долю и принимает лекарство`() {
        val user = actor("reserves-and-takes")
        val kitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val created = user.api.createDrug(kitId, drugRequest("Treatment Drug", "100"))
            .expectBody<DrugSnapshotDTO>(201)

        val reservation = user.api.createReservation(
            ReservationCreateRequest(created.drug.id, BigDecimal("30"), created.reservations.version)
        ).expectBody<ReservationDTO>(201)
        assertQty(30.0, reservation.amount)

        val beforeIntake = user.api.getDrug(created.drug.id).expectBody<DrugSnapshotDTO>(200)
        val afterFirst = user.api.recordIntake(
            created.drug.id,
            IntakeRequest(BigDecimal("5"), beforeIntake.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        val afterSecond = user.api.recordIntake(
            created.drug.id,
            IntakeRequest(BigDecimal("5"), afterFirst.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)

        assertQty(90.0, afterSecond.drug.quantity)
        assertQty(30.0, afterSecond.reservations.total)
        assertQty(30.0, afterSecond.reservations.mine)
    }

    /** Истории 7 и 12: у участников свои доли, а общая сумма не ограничена остатком. */
    @Test
    fun `участники видят свои брони и могут заявить больше остатка`() {
        val anna = actor("anna-reserves")
        val bob = actor("bob-reserves")
        val kitId = Uuid.random()
        anna.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val created = anna.api.createDrug(kitId, drugRequest("Vitamin C", "100"))
            .expectBody<DrugSnapshotDTO>(201)
        val invitation = anna.api.inviteTo(kitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)

        anna.api.createReservation(
            ReservationCreateRequest(created.drug.id, BigDecimal("40"), created.reservations.version)
        ).expectBody<ReservationDTO>(201)
        val bobView = bob.api.getDrug(created.drug.id).expectBody<DrugSnapshotDTO>(200)
        bob.api.createReservation(
            ReservationCreateRequest(created.drug.id, BigDecimal("30"), bobView.reservations.version)
        ).expectBody<ReservationDTO>(201)

        val beforeRaise = anna.api.getDrug(created.drug.id).expectBody<DrugSnapshotDTO>(200)
        anna.api.patchReservation(
            created.drug.id,
            ReservationPatchRequest(BigDecimal("200"), beforeRaise.reservations.version)
        ).expectBody<ReservationDTO>(200)

        val annaView = anna.api.getDrug(created.drug.id).expectBody<DrugSnapshotDTO>(200)
        val finalBobView = bob.api.getDrug(created.drug.id).expectBody<DrugSnapshotDTO>(200)
        assertQty(230.0, annaView.reservations.total)
        assertQty(200.0, annaView.reservations.mine)
        assertQty(230.0, finalBobView.reservations.total)
        assertQty(30.0, finalBobView.reservations.mine)
        assertEquals(
            listOf(created.drug.id),
            bob.api.listReservations().expectBody<List<ReservationDTO>>(200).map { it.drugId }
        )
    }

    /** История 10: семейная аптечка от приглашений до выхода ребёнка. */
    @Test
    fun `семья совместно бронирует и принимает лекарства`() {
        val mom = actor("mom")
        val dad = actor("dad")
        val child = actor("child")
        val kitId = Uuid.random()
        mom.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        mom.api.createDrug(kitId, drugRequest("Children's Aspirin", "200"))
            .expectBody<DrugSnapshotDTO>(201)
        val vitamins = mom.api.createDrug(kitId, drugRequest("Multivitamins", "90"))
            .expectBody<DrugSnapshotDTO>(201)

        listOf(dad, child).forEach { relative ->
            val invitation = mom.api.inviteTo(kitId).expectBody<InvitationDTO>(201)
            relative.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        }

        listOf(mom, dad, child).forEach { relative ->
            val snapshot = relative.api.getDrug(vitamins.drug.id).expectBody<DrugSnapshotDTO>(200)
            relative.api.createReservation(
                ReservationCreateRequest(vitamins.drug.id, BigDecimal("30"), snapshot.reservations.version)
            ).expectBody<ReservationDTO>(201)
        }

        var current = mom.api.getDrug(vitamins.drug.id).expectBody<DrugSnapshotDTO>(200)
        listOf(mom, dad, child).forEach { relative ->
            current = relative.api.recordIntake(
                vitamins.drug.id,
                IntakeRequest(BigDecimal.ONE, current.drug.version)
            ).expectBody(200)
        }

        assertQty(87.0, current.drug.quantity)
        assertQty(90.0, current.reservations.total)
        child.api.leave(kitId).expectEmpty(204)
        child.api.getMedKit(kitId).expectStatus(404)

        val remainingFamily = mom.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertEquals(2L, remainingFamily.userCount)
        val remainingVitamins = remainingFamily.drugs.single { it.drug.id == vitamins.drug.id }
        assertQty(60.0, remainingVitamins.reservations.total)
        assertQty(30.0, remainingVitamins.reservations.mine)
    }
}
