package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.FormTypeRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.repository.ReservationRepository
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.UnknownFormType
import org.kert0n.medappserver.domain.UnknownQuantityUnit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата упаковки.
 *
 * Наружу — только доменные типы: сервисы не видят ни сущностей, ни репозиториев. Броней здесь
 * нет: упаковка ими не владеет, за ними ходят в `ReservationStore`.
 */
@Component
class DrugStore(
    private val drugs: DrugRepository,
    private val medKits: MedKitRepository,
    private val units: QuantityUnitRepository,
    private val forms: FormTypeRepository,
    private val reservations: ReservationRepository
) {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun findAccessible(drugId: UUID, userId: UUID): Drug? = drugs.findAccessible(drugId, userId)?.toDomain()

    fun findAllInMedKit(medKitId: UUID): List<Drug> = drugs.findAllInMedKit(medKitId).map { it.toDomain() }

    fun findAllAccessibleTo(userId: UUID): List<Drug> = drugs.findAllAccessible(userId).map { it.toDomain() }

    fun findById(drugId: UUID): Drug? = drugs.findFullById(drugId)?.toDomain()

    // ── Команды ──────────────────────────────────────────────────────────────────

    fun insert(drug: Drug) {
        drugs.save(
            drug.toNewEntity(
                medKit = resolveMedKit(drug.medKitId),
                unit = resolveUnit(drug.quantity.unit.id),
                form = drug.formType?.let { resolveForm(it.id) }
            )
        )
    }

    /**
     * Записывает состояние в уже загруженную строку.
     *
     * Лишнего запроса тут нет: внутри той же транзакции сущность лежит в persistence context,
     * и поиск по идентификатору берёт её оттуда.
     */
    fun save(drug: Drug) {
        val entity = managed(drug.id)
        drug.applyTo(entity, ::resolveMedKit, ::resolveUnit, ::resolveForm)
        drugs.save(entity)
    }

    /**
     * Уничтожение пачки.
     *
     * Брони уносит каскад внешнего ключа, но Hibernate о нём не знает: загруженные строки
     * остались бы ссылаться на удалённую пачку и уронили бы ближайший flush. Это persistence, а
     * не решение агрегата — сама упаковка про брони не знает.
     */
    fun delete(drugId: UUID) {
        val entity = drugs.findByIdOrNull(drugId) ?: return
        reservations.deleteOfDrug(drugId)
        drugs.delete(entity)
    }

    /** Все упаковки аптечки — в другую, одним запросом. Брони убирает вызывающий. */
    fun moveAllToMedKit(sourceMedKitId: UUID, targetMedKitId: UUID) {
        drugs.moveAllToMedKit(sourceMedKitId, resolveMedKit(targetMedKitId))
    }

    private fun managed(drugId: UUID): DrugData =
        drugs.findByIdOrNull(drugId) ?: error("Упаковка $drugId исчезла во время записи")

    private fun resolveMedKit(medKitId: UUID): MedKitData =
        medKits.findByIdOrNull(medKitId) ?: error("Аптечка $medKitId исчезла во время записи упаковки")

    private fun resolveUnit(unitId: UUID): QuantityUnitData =
        units.findByIdOrNull(unitId) ?: throw UnknownQuantityUnit()

    private fun resolveForm(formId: UUID): FormTypeData =
        forms.findByIdOrNull(formId) ?: throw UnknownFormType()
}
