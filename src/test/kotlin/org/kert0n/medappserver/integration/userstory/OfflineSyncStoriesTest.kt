package org.kert0n.medappserver.integration.userstory

import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.DrugSnapshotDTO
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.IntakeRequest
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

    /**
     * Пока клиент был офлайн, пачку тронули, а ответ на применённое потерялся.
     *
     * Устаревшая версия отвечает 412: не применено, перечитай. Подготовленный заново запрос под
     * тем же идентификатором применяется один раз, и его повтор — уже со снова устаревшей
     * версией — отвечает тем же снимком: журнал спрашивают раньше версии. Другое тело под тем же
     * идентификатором — 409, дефект клиента, и повторять его незачем.
     */
    @Test
    fun `устаревшая версия отвечает предусловием, а подготовленное заново применяется один раз`() {
        val user = actor("offline-stale")
        val kitId = Uuid.random()
        user.api.createMedKit(MedKitCreateRequest(kitId)).expectBody<MedKitCreatedDTO>(201)
        val drug = user.api.createDrug(kitId, drugRequest("Stale pills", "20"))
            .expectBody<DrugSnapshotDTO>(201)
        val syncId = Uuid.random()
        val offline = DrugSyncRequest(consumed = BigDecimal("2"), drugVersion = drug.drug.version)

        // Приём с другого входа сдвинул версию, пока синхронизация копилась офлайн.
        user.api.recordIntake(drug.drug.id, IntakeRequest(BigDecimal("1"), drug.drug.version))
            .expectBody<DrugSnapshotDTO>(200)

        user.api.synchronise(drug.drug.id, syncId, offline).expectStatus(412)

        val fresh = user.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200)
        val prepared = offline.copy(drugVersion = fresh.drug.version)
        val applied = user.api.synchronise(drug.drug.id, syncId, prepared).expectBody<DrugSnapshotDTO>(200)
        assertQty(17.0, applied.drug.quantity)

        val repeated = user.api.synchronise(drug.drug.id, syncId, prepared).expectBody<DrugSnapshotDTO>(200)
        assertEquals(applied, repeated)

        user.api.synchronise(drug.drug.id, syncId, prepared.copy(consumed = BigDecimal("3"))).expectStatus(409)
        assertQty(17.0, user.api.getDrug(drug.drug.id).expectBody<DrugSnapshotDTO>(200).drug.quantity)
    }
}
