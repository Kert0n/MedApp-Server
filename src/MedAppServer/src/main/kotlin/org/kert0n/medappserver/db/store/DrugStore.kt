package org.kert0n.medappserver.db.store

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.UserData
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.TreatmentPlan
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component

/**
 * Хранилище агрегата препарата.
 *
 * Наружу отдаёт и принимает только доменные типы: ни один сервис не видит ни сущностей, ни
 * репозиториев. Это и есть граница, ради которой раскладку разделили — за ней остаются и
 * отображение, и блокировки, и то, что план лечения хранится строкой с составным ключом.
 */
@Component
class DrugStore(
    private val drugs: DrugRepository,
    private val plans: TreatmentPlanRepository,
    private val users: UserRepository,
    private val medKits: MedKitRepository
) {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun findAccessible(drugId: UUID, userId: UUID): Drug? =
        drugs.findAccessibleWithPlans(drugId, userId)?.toDomain()

    fun findAllInMedKit(medKitId: UUID): List<Drug> =
        drugs.findAllInMedKitWithPlans(medKitId).map { it.toDomain() }

    fun findAllAccessibleTo(userId: UUID): List<Drug> =
        drugs.findAllAccessibleWithPlans(userId).map { it.toDomain() }

    fun findById(drugId: UUID): Drug? = drugs.findByIdOrNull(drugId)?.toDomain()

    fun findPlansOf(userId: UUID): List<TreatmentPlan> = plans.findPlansOfUser(userId)

    fun findPlan(userId: UUID, drugId: UUID): TreatmentPlan? = plans.findPlan(userId, drugId)

    fun plannedTotalOf(drugId: UUID): BigDecimal =
        plans.findAllByPlanKeyDrugId(drugId).fold(BigDecimal.ZERO) { sum, row -> sum + row.plannedAmount }

    // ── Команды ──────────────────────────────────────────────────────────────────

    /** Загрузка под блокировкой строки: с неё начинается любая команда над препаратом. */
    fun lockAccessible(drugId: UUID, userId: UUID): Drug? =
        drugs.lockAccessible(drugId, userId)?.toDomain()

    fun insert(drug: Drug) {
        drugs.save(drug.toNewEntity(resolveMedKit(drug.medKitId)))
    }

    /**
     * Записывает состояние в уже загруженную строку.
     *
     * Лишнего запроса тут нет: внутри той же транзакции сущность лежит в persistence context,
     * и поиск по идентификатору берёт её оттуда.
     */
    fun save(drug: Drug) {
        val entity = managed(drug.id)
        drug.applyTo(entity, ::resolveUser, ::resolveMedKit)
        drugs.save(entity)
    }

    fun delete(drugId: UUID) {
        drugs.findByIdOrNull(drugId)?.let { drugs.delete(it) }
    }

    fun deletePlansOfUserInMedKit(userId: UUID, medKitId: UUID) {
        plans.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    private fun managed(drugId: UUID): DrugData =
        drugs.findByIdOrNull(drugId) ?: error("Drug $drugId disappeared while it was locked")

    private fun resolveUser(userId: UUID): UserData =
        users.findByIdOrNull(userId) ?: error("User $userId disappeared while a plan was being written")

    private fun resolveMedKit(medKitId: UUID): MedKitData =
        medKits.findByIdOrNull(medKitId) ?: error("Medicine kit $medKitId disappeared while a drug was being written")
}
