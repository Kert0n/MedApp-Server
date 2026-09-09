package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreateRequest
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.UserSnapshotDTO
import org.kert0n.medappserver.testutil.assertQty

@PostgresIntegrationTest
class DrugMovementStoriesTest : HttpUserStoryTest() {

    /** История 11: переезд сохраняет бронь того, кто видит обе аптечки. */
    @Test
    fun `упаковка переезжает между аптечками вместе с доступной бронью`() {
        val user = actor("moves-pack")
        val homeKitId = Uuid.random()
        val travelKitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(homeKitId)).expectBody<MedKitCreatedDTO>(201)
        user.api.createMedKit(MedKitCreateRequest(travelKitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = user.api.createDrug(homeKitId, drugRequest("Ibuprofen", "60"))
            .expectBody<DrugSnapshotDTO>(201)
        user.api.createReservation(
            ReservationCreateRequest(drug.drug.id, BigDecimal("20"), drug.reservations.version)
        ).expectBody<ReservationDTO>(201)

        val moved = user.api.moveDrug(drug.drug.id, travelKitId, drug.drug.version)
            .expectBody<DrugSnapshotDTO>(200)
        assertEquals(travelKitId, moved.drug.medKitId)
        assertQty(20.0, moved.reservations.total)
        assertQty(20.0, moved.reservations.mine)

        val home = user.api.getMedKit(homeKitId).expectBody<MedKitDTO>(200)
        val travel = user.api.getMedKit(travelKitId).expectBody<MedKitDTO>(200)
        assertEquals(emptySet(), home.drugs.map { it.drug.id }.toSet())
        assertEquals(setOf(drug.drug.id), travel.drugs.map { it.drug.id }.toSet())
        assertQty(20.0, user.api.getReservation(drug.drug.id).expectBody<ReservationDTO>(200).amount)
    }

    /** История 13: уничтоженная упаковка уносит брони с собой. */
    @Test
    fun `удаление упаковки удаляет доступ к ней и её броням`() {
        val user = actor("deletes-pack")
        val kitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = user.api.createDrug(kitId, drugRequest("Expired Drug", "50"))
            .expectBody<DrugSnapshotDTO>(201)
        user.api.createReservation(
            ReservationCreateRequest(drug.drug.id, BigDecimal("25"), drug.reservations.version)
        ).expectBody<ReservationDTO>(201)

        user.api.deleteDrug(drug.drug.id, drug.drug.version).expectEmpty(204)
        user.api.getDrug(drug.drug.id).expectStatus(404)
        user.api.getReservation(drug.drug.id).expectStatus(404)

        val kit = user.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertEquals(emptySet(), kit.drugs.map { it.drug.id }.toSet())
        assertEquals(
            emptyList(),
            user.api.listReservations().expectBody<List<ReservationDTO>>(200)
        )
    }

    /** История 14 и прежняя дублирующая проверка очистки броней при потере доступа. */
    @Test
    fun `перенос в более узкую аптечку оставляет только доступные брони`() {
        val anna = actor("narrow-anna")
        val bob = actor("narrow-bob")
        val charlie = actor("narrow-charlie")
        val sourceKitId = Uuid.random()
        val targetKitId = Uuid.random()
        anna.api.createMedKit(MedKitCreateRequest(sourceKitId)).expectBody<MedKitCreatedDTO>(201)
        anna.api.createMedKit(MedKitCreateRequest(targetKitId)).expectBody<MedKitCreatedDTO>(201)

        listOf(bob, charlie).forEach { participant ->
            val invitation = anna.api.inviteTo(sourceKitId).expectBody<InvitationDTO>(201)
            participant.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        }
        val targetInvitation = anna.api.inviteTo(targetKitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(targetInvitation.key)).expectBody<MedKitDTO>(201)

        val drug = anna.api.createDrug(sourceKitId, drugRequest("Special Meds", "90"))
            .expectBody<DrugSnapshotDTO>(201)
        listOf(anna, bob, charlie).forEach { participant ->
            val visible = participant.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
            participant.api.createReservation(
                ReservationCreateRequest(drug.drug.id, BigDecimal("30"), visible.reservations.version)
            ).expectBody<ReservationDTO>(201)
        }

        anna.api.deleteMedKit(sourceKitId, targetKitId).expectEmpty(204)
        anna.api.getMedKit(sourceKitId).expectStatus(404)

        val migrated = anna.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
        assertEquals(targetKitId, migrated.drug.medKitId)
        assertQty(60.0, migrated.reservations.total)
        assertQty(30.0, migrated.reservations.mine)
        assertQty(30.0, bob.api.getReservation(drug.drug.id).expectBody<ReservationDTO>(200).amount)
        charlie.api.getDrug(drug.drug.id).expectStatus(404)
        charlie.api.getReservation(drug.drug.id).expectStatus(404)
        assertEquals(
            emptyList(),
            charlie.api.listReservations().expectBody<List<ReservationDTO>>(200)
        )
    }

    /** История 16: переезд одной пачки не трогает соседнюю. */
    @Test
    fun `переезд одной упаковки сохраняет другую в исходной аптечке`() {
        val user = actor("moves-one-pack")
        val sourceKitId = Uuid.random()
        val targetKitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(sourceKitId)).expectBody<MedKitCreatedDTO>(201)
        user.api.createMedKit(MedKitCreateRequest(targetKitId)).expectBody<MedKitCreatedDTO>(201)
        val moving = user.api.createDrug(sourceKitId, drugRequest("Moving Pill", "10"))
            .expectBody<DrugSnapshotDTO>(201)
        val staying = user.api.createDrug(sourceKitId, drugRequest("Staying Pill", "10"))
            .expectBody<DrugSnapshotDTO>(201)

        user.api.moveDrug(moving.drug.id, targetKitId, moving.drug.version)
            .expectBody<DrugSnapshotDTO>(200)

        val source = user.api.getMedKit(sourceKitId).expectBody<MedKitDTO>(200)
        val target = user.api.getMedKit(targetKitId).expectBody<MedKitDTO>(200)
        assertEquals(setOf(staying.drug.id), source.drugs.map { it.drug.id }.toSet())
        assertEquals(setOf(moving.drug.id), target.drugs.map { it.drug.id }.toSet())

        val wholeSnapshot = user.api.snapshot().expectBody<UserSnapshotDTO>(200)
        assertEquals(setOf(sourceKitId, targetKitId), wholeSnapshot.medKits.map { it.id }.toSet())
        assertEquals(
            setOf(moving.drug.id, staying.drug.id),
            wholeSnapshot.medKits.flatMap { it.drugs }.map { it.drug.id }.toSet()
        )
    }
}
