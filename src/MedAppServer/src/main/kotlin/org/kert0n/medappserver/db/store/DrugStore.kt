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
 * Наружу отдаёт и принимает только доменные типы: ни один сервис не видит ни сущностей, ни
 * репозиториев. Броней здесь нет вовсе — упаковка ими не владеет, и за ними ходят в
 * `ReservationStore`.
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

    /** Загрузка под блокировкой строки: с неё начинается любая команда над упаковкой. */
    fun lockAccessible(drugId: UUID, userId: UUID): Drug? = drugs.lockAccessible(drugId, userId)?.toDomain()

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
     * Брони уносит внешний ключ с каскадом, но сказать об этом Hibernate приходится явно:
     * загруженные строки броней иначе остались бы ссылаться на удалённую упаковку и уронили бы
     * ближайший flush. Это persistence-половина правила «бронь не переживает пачку», а не
     * решение агрегата — сама упаковка про брони по-прежнему не знает.
     */
    fun delete(drugId: UUID) {
        val entity = drugs.findByIdOrNull(drugId) ?: return
        reservations.deleteOfDrug(drugId)
        drugs.delete(entity)
    }

    /**
     * Переносит все упаковки аптечки в другую — одним запросом.
     *
     * Судьба броней здесь не решается: они в чужом агрегате, и убирает их вызывающий. Раньше
     * это делалось тут же, потому что планы лежали внутри препарата.
     */
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
