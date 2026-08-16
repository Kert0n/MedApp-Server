package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.DrugView
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.domain.drug.Drug
import org.kert0n.medappserver.domain.drug.DrugDetails
import org.kert0n.medappserver.domain.drug.TreatmentPlan
import org.kert0n.medappserver.domain.drug.applyTo
import org.kert0n.medappserver.domain.drug.toDomain
import org.kert0n.medappserver.domain.drug.toNewEntity
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.db.model.Drug as DrugEntity

/**
 * Единственный вход к агрегату препарата — вместе с его планами лечения.
 *
 * Каждая команда устроена одинаково: загрузить строку под блокировкой, поднять из неё
 * доменное состояние, вызвать метод домена, записать результат обратно в ту же строку.
 * Правил здесь нет — они в `domain.drug.Drug`.
 */
@Service
class DrugService(
    private val drugRepository: DrugRepository,
    private val userRepository: UserRepository,
    private val medKitRepository: MedKitRepository
) {

    private val logger = LoggerFactory.getLogger(DrugService::class.java)

    // ── Чтение: проекции, суммы планов считает база ──────────────────────────────

    /** Препарат для показа или `null`, если его нет или он недоступен вызывающему. */
    @Transactional(readOnly = true)
    fun findView(drugId: UUID, userId: UUID): DrugView? {
        logger.debug("Reading drug {} for user {}", drugId, userId)
        return drugRepository.findViewAccessible(drugId, userId)
    }

    /** Препарат для показа или 404. */
    @Transactional(readOnly = true)
    fun requireView(drugId: UUID, userId: UUID): DrugView =
        findView(drugId, userId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun viewsOfMedKit(medKitId: UUID): List<DrugView> {
        logger.debug("Reading drugs of medkit {}", medKitId)
        return drugRepository.findViewsByMedKit(medKitId)
    }

    /** Препараты всех аптечек пользователя — одним запросом, для снимка. */
    @Transactional(readOnly = true)
    fun viewsAccessibleTo(userId: UUID): List<DrugView> {
        logger.debug("Reading all drugs available to user {}", userId)
        return drugRepository.findViewsAccessibleTo(userId)
    }

    // ── Поиск строки для команд ──────────────────────────────────────────────────
    //
    // Имена говорят о поведении: `find` может не найти и возвращает `null`, `require`
    // считает отсутствие ошибкой вызывающего и даёт 404, `lock` вдобавок берёт блокировку
    // строки.

    @Transactional(readOnly = true)
    fun findById(drugId: UUID): DrugEntity? = drugRepository.findByIdOrNull(drugId)

    @Transactional(readOnly = true)
    fun requireById(drugId: UUID): DrugEntity = findById(drugId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun findAccessible(drugId: UUID, userId: UUID): DrugEntity? =
        drugRepository.findAccessible(drugId, userId)

    @Transactional(readOnly = true)
    fun requireAccessible(drugId: UUID, userId: UUID): DrugEntity =
        findAccessible(drugId, userId) ?: throw notFound()

    /** То же плюс блокировка строки на время транзакции. */
    @Transactional(readOnly = true)
    fun lockAccessible(drugId: UUID, userId: UUID): DrugEntity =
        drugRepository.lockAccessible(drugId, userId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun findAllByMedKit(medKitId: UUID): List<DrugEntity> {
        logger.debug("Finding all drugs for medkit: {}", medKitId)
        return drugRepository.findAllByMedKitId(medKitId)
    }

    /**
     * Недоступный препарат и несуществующий отвечают одинаково: иначе по коду ответа можно
     * было бы узнать, что такой препарат существует в чужой аптечке.
     */
    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")

    // ── Обратная запись ──────────────────────────────────────────────────────────

    private fun resolveUser(userId: UUID): User =
        userRepository.findByIdOrNull(userId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

    private fun resolveMedKit(medKitId: UUID): MedKit =
        medKitRepository.findByIdOrNull(medKitId) ?: throw notFound()

    private fun save(entity: DrugEntity, state: Drug): DrugEntity {
        state.applyTo(entity, ::resolveUser, ::resolveMedKit)
        return drugRepository.save(entity)
    }

    // ── Команды препарата ────────────────────────────────────────────────────────

    @Transactional
    fun create(createDTO: DrugCreateRequest, medKit: MedKit, userId: UUID): DrugEntity {
        logger.debug("Creating drug: {} for user: {}", createDTO.name, userId)

        val state = Drug.create(
            medKitId = medKit.id,
            name = createDTO.name,
            quantity = createDTO.quantity,
            quantityUnit = createDTO.quantityUnit,
            formType = createDTO.formType,
            category = createDTO.category,
            manufacturer = createDTO.manufacturer,
            country = createDTO.country,
            description = createDTO.description
        )

        return drugRepository.save(state.toNewEntity(medKit))
    }

    @Transactional
    fun update(drugId: UUID, updateDTO: DrugPatchRequest, userId: UUID): DrugEntity {
        logger.debug("Updating drug: {}", drugId)

        val entity = lockAccessible(drugId, userId)
        var state = entity.toDomain()

        updateDTO.quantity?.let { state = state.increaseQuantityTo(it) }
        state = state.describe(
            DrugDetails(
                name = updateDTO.name,
                quantityUnit = updateDTO.quantityUnit,
                formType = updateDTO.formType,
                category = updateDTO.category,
                manufacturer = updateDTO.manufacturer,
                country = updateDTO.country,
                description = updateDTO.description
            )
        )

        return save(entity, state)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) {
        logger.debug("Deleting drug: {}", drugId)

        val entity = requireAccessible(drugId, userId)
        drugRepository.delete(entity)
    }

    /**
     * Списывает количество вне плана лечения.
     *
     * `null` здесь означает «препарат кончился и удалён этим списанием», а не «не найден»:
     * недоступный препарат отвергается 404 ещё до списания.
     */
    @Transactional
    fun consumeDrug(drugId: UUID, quantity: BigDecimal, userId: UUID): DrugEntity? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val entity = lockAccessible(drugId, userId)
        val outcome = entity.toDomain().consume(quantity)

        if (outcome.exhausted) {
            drugRepository.delete(entity)
            return null
        }
        return save(entity, outcome.drug)
    }

    // ── Команды планов лечения ───────────────────────────────────────────────────

    @Transactional
    fun createPlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Creating treatment plan for user {} and drug {}", userId, drugId)

        val entity = lockAccessible(drugId, userId)
        val state = entity.toDomain().createPlan(userId, plannedAmount)
        save(entity, state)

        return state.requirePlanOf(userId)
    }

    @Transactional
    fun changePlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Updating treatment plan for user {} and drug {}", userId, drugId)

        val entity = lockAccessible(drugId, userId)
        val state = entity.toDomain().changePlan(userId, plannedAmount)
        save(entity, state)

        return state.requirePlanOf(userId)
    }

    @Transactional
    fun cancelPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting treatment plan for user {} and drug {}", userId, drugId)

        val entity = lockAccessible(drugId, userId)
        save(entity, entity.toDomain().cancelPlan(userId))
    }

    /**
     * Списывает приём с плана и с остатка препарата.
     *
     * `null` означает «план исчерпан и удалён» либо «препарат кончился», а не «план не
     * найден»: отсутствующий план отвергается 404 ещё до списания.
     */
    @Transactional
    fun recordIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): TreatmentPlan? {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)

        val entity = lockAccessible(drugId, userId)
        val outcome = entity.toDomain().applyIntake(userId, quantityConsumed)

        if (outcome.drugExhausted) {
            drugRepository.delete(entity)
            return null
        }
        save(entity, outcome.drug)
        return outcome.plan
    }

    /** Переезд препарата в другую аптечку вместе с судьбой планов. */
    @Transactional
    fun moveTo(drugId: UUID, targetMedKit: MedKit, accessibleUserIds: Set<UUID>, userId: UUID): DrugEntity {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKit.id)

        val entity = lockAccessible(drugId, userId)
        val state = entity.toDomain().moveTo(targetMedKit.id, accessibleUserIds)

        return save(entity, state)
    }

    /** Убирает план участника, если он есть: используется при выходе из аптечки. */
    @Transactional
    fun revokePlanOf(entity: DrugEntity, userId: UUID) {
        save(entity, entity.toDomain().revokePlanOf(userId))
    }
}
