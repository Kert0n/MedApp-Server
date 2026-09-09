package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.MedKitCreateRequest
import org.kert0n.medappserver.api.MedKitCreatedDTO
import org.kert0n.medappserver.api.MedKitDTO
import org.kert0n.medappserver.api.ReservationSyncRequest
import org.kert0n.medappserver.testutil.assertQty

@PostgresIntegrationTest
class OfflineSyncStoriesTest : HttpUserStoryTest() {

    @Test
    fun `офлайн изменения повторяются безопасно и могут закончить упаковку`() {
        val user = actor("offline-sync")
        val kitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = user.api.createDrug(kitId, drugRequest("Offline pills", "20"))
            .expectBody<DrugSnapshotDTO>(201)
        val syncId = Uuid.random()
        val offlineChanges = DrugSyncRequest(
            consumed = BigDecimal("5"),
            drugVersion = drug.drug.version,
            reservation = ReservationSyncRequest(BigDecimal("7"))
        )

        val applied = user.api.synchronise(drug.drug.id, syncId, offlineChanges)
            .expectBody<DrugSnapshotDTO>(200)
        assertQty(15.0, applied.drug.quantity)
        assertQty(7.0, applied.reservations.total)
        assertQty(7.0, applied.reservations.mine)

        val repeated = user.api.synchronise(drug.drug.id, syncId, offlineChanges)
            .expectBody<DrugSnapshotDTO>(200)
        assertEquals(applied, repeated)

        user.api.synchronise(
            drug.drug.id,
            Uuid.random(),
            DrugSyncRequest(
                consumed = BigDecimal("15"),
                drugVersion = repeated.drug.version,
                reservation = ReservationSyncRequest(BigDecimal("2"))
            )
        ).expectEmpty(200)

        user.api.getDrug(drug.drug.id).expectStatus(404)
        val kit = user.api.getMedKit(kitId).expectBody<MedKitDTO>(200)
        assertTrue(kit.drugs.none { it.drug.id == drug.drug.id })
    }
}
