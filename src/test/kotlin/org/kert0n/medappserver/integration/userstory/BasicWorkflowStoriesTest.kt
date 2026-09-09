package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.InvitationDTO
import org.kert0n.medappserver.api.MedKitCreateRequest
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.testutil.assertQty

@PostgresIntegrationTest
class BasicWorkflowStoriesTest : HttpUserStoryTest() {

    /** История 1: у человека появляются аптечка, содержимое и первый приём. */
    @Test
    fun `пользователь создаёт аптечку, меняет упаковку и отмечает приём`() {
        val anna = actor("anna-manages")
        val kitId = Uuid.random()

        val createdKit = anna.api.createMedKit(MedKitCreateRequest(kitId))
            .expectBody<MedKitCreatedDTO>(201)
        assertEquals(kitId, createdKit.id)

        val aspirin = anna.api.createDrug(kitId, drugRequest("Aspirin", "100"))
            .expectBody<DrugSnapshotDTO>(201)
        val ibuprofen = anna.api.createDrug(kitId, drugRequest("Ibuprofen", "50"))
            .expectBody<DrugSnapshotDTO>(201)

        val renamed = anna.api.patchDrug(
            aspirin.drug.id,
            DrugPatchRequest(name = "Aspirin cardio", version = aspirin.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertEquals("Aspirin cardio", renamed.drug.name)

        val afterIntake = anna.api.recordIntake(
            aspirin.drug.id,
            IntakeRequest(BigDecimal("2"), renamed.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertQty(98.0, afterIntake.drug.quantity)

        val kit = anna.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertEquals(setOf(aspirin.drug.id, ibuprofen.drug.id), kit.drugs.map { it.drug.id }.toSet())
        assertEquals("Aspirin cardio", kit.drugs.single { it.drug.id == aspirin.drug.id }.drug.name)
    }

    /** История 2: аптечка делится с соседом, и содержимое видно обоим. */
    @Test
    fun `приглашённый сосед видит общую аптечку`() {
        val anna = actor("anna-shares")
        val bob = actor("bob-joins")
        val kitId = Uuid.random()
        anna.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val vitamins = anna.api.createDrug(kitId, drugRequest("Vitamin C", "30"))
            .expectBody<DrugSnapshotDTO>(201)

        val invitation = anna.api.inviteTo(kitId).expectBody<InvitationDTO>(201)
        val joined = bob.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)
        assertEquals(kitId, joined.id)
        assertEquals(2L, joined.userCount)
        assertEquals(setOf(vitamins.drug.id), joined.drugs.map { it.drug.id }.toSet())

        val annaView = anna.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        val bobView = bob.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertEquals(2L, annaView.userCount)
        assertEquals(2L, bobView.userCount)
        assertEquals(setOf(vitamins.drug.id), annaView.drugs.map { it.drug.id }.toSet())
        assertEquals(setOf(vitamins.drug.id), bobView.drugs.map { it.drug.id }.toSet())
    }

    /** История 3: вышедший участник теряет доступ, но чужая аптечка остаётся. */
    @Test
    fun `выход соседа сохраняет аптечку оставшемуся участнику`() {
        val anna = actor("anna-stays")
        val bob = actor("bob-leaves")
        val kitId = Uuid.random()
        anna.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = anna.api.createDrug(kitId, drugRequest("Test Drug", "100"))
            .expectBody<DrugSnapshotDTO>(201)
        val invitation = anna.api.inviteTo(kitId).expectBody<InvitationDTO>(201)
        bob.api.join(MembershipCreateRequest(invitation.key)).expectBody<MedKitDTO>(201)

        bob.api.leave(kitId).expectEmpty(204)
        bob.api.getMedKit(kitId).expectStatus(404)
        assertTrue(
            bob.api.listMedKits().expectBody<Set<MedKitSummaryDTO>>(200).none { it.id == kitId }
        )

        val remaining = anna.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertEquals(1L, remaining.userCount)
        assertEquals(setOf(drug.drug.id), remaining.drugs.map { it.drug.id }.toSet())
    }

    /** История 4 и прежняя дублирующая проверка миграции. */
    @Test
    fun `удаление старой аптечки переносит упаковки в новую`() {
        val user = actor("migrates-kit")
        val oldKitId = Uuid.random()
        val newKitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(oldKitId)).expectBody<MedKitCreatedDTO>(201)
        user.api.createMedKit(MedKitCreateRequest(newKitId)).expectBody<MedKitCreatedDTO>(201)
        val drugA = user.api.createDrug(oldKitId, drugRequest("Drug A", "50"))
            .expectBody<DrugSnapshotDTO>(201)
        val drugB = user.api.createDrug(oldKitId, drugRequest("Drug B", "100"))
            .expectBody<DrugSnapshotDTO>(201)

        user.api.deleteMedKit(oldKitId, newKitId).expectEmpty(204)
        user.api.getMedKit(oldKitId).expectStatus(404)

        val target = user.api.getMedKit(newKitId).expectBody<MedKitDTO>(200)
        assertEquals(setOf(drugA.drug.id, drugB.drug.id), target.drugs.map { it.drug.id }.toSet())
        assertEquals(setOf("Drug A", "Drug B"), target.drugs.map { it.drug.name }.toSet())
        assertEquals(
            setOf(newKitId),
            user.api.listMedKits().expectBody<Set<MedKitSummaryDTO>>(200).map { it.id }.toSet()
        )
    }

    /** История 5: опустевшая от приёма пачка даёт пустой 200 и исчезает. */
    @Test
    fun `последний приём уничтожает упаковку и возвращает пустой 200`() {
        val user = actor("finishes-pack")
        val kitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val created = user.api.createDrug(kitId, drugRequest("Limited Drug", "30"))
            .expectBody<DrugSnapshotDTO>(201)

        val afterFirst = user.api.recordIntake(
            created.drug.id,
            IntakeRequest(BigDecimal("10"), created.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertQty(20.0, afterFirst.drug.quantity)

        val afterSecond = user.api.recordIntake(
            created.drug.id,
            IntakeRequest(BigDecimal("10"), afterFirst.drug.version)
        ).expectBody<DrugSnapshotDTO>(200)
        assertQty(10.0, afterSecond.drug.quantity)

        user.api.recordIntake(
            created.drug.id,
            IntakeRequest(BigDecimal("10"), afterSecond.drug.version)
        ).expectEmpty(200)

        user.api.getDrug(created.drug.id).expectStatus(404)
        val kit = user.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertTrue(kit.drugs.none { it.drug.id == created.drug.id })
    }
}
