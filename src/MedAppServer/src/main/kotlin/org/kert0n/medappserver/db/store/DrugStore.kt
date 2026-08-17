package org.kert0n.medappserver.db.store

import java.util.UUID
import org.kert0n.medappserver.db.model.DrugData
import org.kert0n.medappserver.db.model.MedKitData
import org.kert0n.medappserver.db.model.UserData
import org.kert0n.medappserver.db.model.parsed.FormTypeData
import org.kert0n.medappserver.db.model.parsed.QuantityUnitData
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.FormTypeRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.QuantityUnitRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRepository
import org.kert0n.medappserver.db.repository.TreatmentPlanRow
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit
import org.kert0n.medappserver.domain.TreatmentPlan
import org.kert0n.medappserver.domain.TreatmentPlanEntry
import org.kert0n.medappserver.domain.UnknownFormType
import org.kert0n.medappserver.domain.UnknownQuantityUnit
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
    private val medKits: MedKitRepository,
    private val units: QuantityUnitRepository,
    private val forms: FormTypeRepository
) {

    // ── Чтение ───────────────────────────────────────────────────────────────────

    fun findAccessible(drugId: UUID, userId: UUID): Drug? =
        drugs.findAccessibleWithPlans(drugId, userId)?.toDomain()

    fun findAllInMedKit(medKitId: UUID): List<Drug> =
        drugs.findAllInMedKitWithPlans(medKitId).map { it.toDomain() }

    fun findAllAccessibleTo(userId: UUID): List<Drug> =
        drugs.findAllAccessibleWithPlans(userId).map { it.toDomain() }

    fun findById(drugId: UUID): Drug? = drugs.findByIdWithPlans(drugId)?.toDomain()

    fun findPlansOf(userId: UUID): List<TreatmentPlanEntry> = plans.findPlansOfUser(userId).map { it.toDomain() }

    fun findPlan(userId: UUID, drugId: UUID): TreatmentPlanEntry? = plans.findPlan(userId, drugId)?.toDomain()

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
     *
     * Версия продвигается ровно тогда, когда что-то изменилось, — и продвигается явно.
     * Полагаться на dirty checking нельзя: изменение только планов корень грязным не делает,
     * и версия осталась бы прежней. Присваивание сохраняет предикат `WHERE version = ?` по
     * прочитанному значению, поэтому оптимистичная блокировка работает как обычно; проверено
     * замером и тестами гонок.
     *
     * Второе следствие того же присваивания — новую версию видно сразу после flush, тогда как
     * `OPTIMISTIC_FORCE_INCREMENT` откладывает её до коммита и складывается с обычным
     * инкрементом в два шага вместо одного. Ответ команды несёт версию, поэтому «сразу» здесь
     * обязательно.
     */
    fun save(drug: Drug): Drug {
        val entity = managed(drug.id)
        if (drug.applyTo(entity, ::resolveUser, ::resolveMedKit, ::resolveUnit, ::resolveForm)) {
            entity.version = entity.version + 1
        }
        // Запись выталкивается сразу, а не на коммите: заодно конфликт всплывает здесь же, а
        // не при закрытии транзакции, где его уже некому объяснить.
        drugs.saveAndFlush(entity)
        return drug.copy(version = entity.version)
    }

    fun delete(drugId: UUID) {
        drugs.findByIdOrNull(drugId)?.let { drugs.delete(it) }
    }

    /**
     * Планы участника во всех препаратах аптечки — путь выхода из неё.
     *
     * Версии затронутых препаратов продвигаются до удаления: план — часть препарата, значит
     * его исчезновение обязано отменить команды, собранные по прежнему состоянию. После
     * удаления выбирать было бы уже нечего — строк планов нет.
     */
    fun deletePlansOfUserInMedKit(userId: UUID, medKitId: UUID) {
        drugs.bumpVersionsPlannedBy(medKitId, userId)
        plans.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    /**
     * Переносит все препараты аптечки в другую и убирает планы тех, кто её не видит.
     *
     * Тот же исход, что дал бы поштучный `Drug.moveTo`, но постоянным числом запросов.
     * Порядок важен: планы удаляются до переезда, пока препараты ещё привязаны к исходной
     * аптечке и их можно выбрать одним условием.
     *
     * Отдельного продвижения версий удаление планов здесь не требует: следом идёт переезд, а
     * он двигает версию каждого препарата аптечки — включая те, у которых планы только что
     * исчезли.
     */
    fun moveAllToMedKit(sourceMedKitId: UUID, targetMedKitId: UUID, accessibleUserIds: Set<UUID>) {
        plans.deleteInMedKitExcept(sourceMedKitId, accessibleUserIds)
        drugs.moveAllToMedKit(sourceMedKitId, resolveMedKit(targetMedKitId))
    }

    /** Строка плана несёт единицу своего препарата — из неё и собирается величина. */
    private fun TreatmentPlanRow.toDomain() = TreatmentPlanEntry(
        plan = TreatmentPlan(userId, drugId, Quantity(plannedAmount, QuantityUnit(unitId, unitName))),
        drugVersion = drugVersion
    )

    private fun managed(drugId: UUID): DrugData =
        drugs.findByIdOrNull(drugId) ?: error("Drug $drugId disappeared while it was locked")

    private fun resolveUser(userId: UUID): UserData =
        users.findByIdOrNull(userId) ?: error("User $userId disappeared while a plan was being written")

    private fun resolveMedKit(medKitId: UUID): MedKitData =
        medKits.findByIdOrNull(medKitId) ?: error("Medicine kit $medKitId disappeared while a drug was being written")

    private fun resolveUnit(unitId: UUID): QuantityUnitData =
        units.findByIdOrNull(unitId) ?: throw UnknownQuantityUnit()

    private fun resolveForm(formId: UUID): FormTypeData =
        forms.findByIdOrNull(formId) ?: throw UnknownFormType()
}
