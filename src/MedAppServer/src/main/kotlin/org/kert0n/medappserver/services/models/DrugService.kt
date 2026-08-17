package org.kert0n.medappserver.services.models

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugDetails
import org.kert0n.medappserver.domain.TreatmentPlan
import org.kert0n.medappserver.domain.NotAMember
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату препарата — вместе с его планами лечения.
 *
 * Каждая команда устроена одинаково: взять состояние под блокировкой, вызвать метод домена,
 * отдать результат хранилищу. Правил здесь нет — они в `domain.drug.Drug`; строк и запросов
 * тоже нет — они за `DrugStore`.
 */
@Service
class DrugService(private val drugs: DrugStore) {

    private val logger = LoggerFactory.getLogger(DrugService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /** Препарат или `null`, если его нет или он недоступен вызывающему. */
    @Transactional(readOnly = true)
    fun find(drugId: UUID, userId: UUID): Drug? {
        logger.debug("Reading drug {} for user {}", drugId, userId)
        return drugs.findAccessible(drugId, userId)
    }

    /** Препарат или 404. */
    @Transactional(readOnly = true)
    fun require(drugId: UUID, userId: UUID): Drug = find(drugId, userId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun ofMedKit(medKitId: UUID): List<Drug> {
        logger.debug("Reading drugs of medkit {}", medKitId)
        return drugs.findAllInMedKit(medKitId)
    }

    /** Препараты всех аптечек участника — одним запросом, для снимка. */
    @Transactional(readOnly = true)
    fun accessibleTo(userId: UUID): List<Drug> {
        logger.debug("Reading all drugs available to user {}", userId)
        return drugs.findAllAccessibleTo(userId)
    }

    @Transactional(readOnly = true)
    fun findById(drugId: UUID): Drug? = drugs.findById(drugId)

    @Transactional(readOnly = true)
    fun requireById(drugId: UUID): Drug = findById(drugId) ?: throw notFound()

    /**
     * Недоступный препарат и несуществующий отвечают одинаково: иначе по коду ответа можно
     * было бы узнать, что такой препарат существует в чужой аптечке.
     */
    private fun notFound() = NotAMember()

    private fun lock(drugId: UUID, userId: UUID): Drug =
        drugs.lockAccessible(drugId, userId) ?: throw notFound()

    // ── Команды препарата ────────────────────────────────────────────────────────

    @Transactional
    fun create(request: DrugCreateRequest, medKitId: UUID, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", request.name, userId)

        val drug = Drug(
            medKitId = medKitId,
            name = request.name,
            quantity = request.quantity,
            quantityUnit = request.quantityUnit,
            formType = request.formType,
            category = request.category,
            manufacturer = request.manufacturer,
            country = request.country,
            description = request.description
        )

        drugs.insert(drug)
        return drug
    }

    @Transactional
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        var drug = lock(drugId, userId)
        request.quantity?.let { drug = drug.changeQuantityTo(it) }
        drug = drug.describe(
            DrugDetails(
                name = request.name,
                quantityUnit = request.quantityUnit,
                formType = request.formType,
                category = request.category,
                manufacturer = request.manufacturer,
                country = request.country,
                description = request.description
            )
        )

        drugs.save(drug)
        return drug
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) {
        logger.debug("Deleting drug: {}", drugId)

        val drug = require(drugId, userId)
        drugs.delete(drug.id)
    }

    /**
     * Списывает количество вне плана лечения.
     *
     * `null` означает «препарат кончился и удалён этим списанием», а не «не найден»:
     * недоступный препарат отвергается 404 ещё до списания.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val left = lock(drugId, userId).consume(quantity)
        if (left == null) {
            drugs.delete(drugId)
            return null
        }
        drugs.save(left)
        return left
    }

    /** Переезд препарата в другую аптечку вместе с судьбой планов. */
    @Transactional
    fun moveTo(drugId: UUID, targetMedKitId: UUID, accessibleUserIds: Set<UUID>, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)

        val moved = lock(drugId, userId).moveTo(targetMedKitId, accessibleUserIds)
        drugs.save(moved)
        return moved
    }

    // ── Команды планов лечения ───────────────────────────────────────────────────

    @Transactional
    fun createPlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Creating treatment plan for user {} and drug {}", userId, drugId)

        val drug = lock(drugId, userId).createPlan(userId, plannedAmount)
        drugs.save(drug)
        return drug.requirePlanOf(userId)
    }

    @Transactional
    fun changePlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Updating treatment plan for user {} and drug {}", userId, drugId)

        val drug = lock(drugId, userId).changePlan(userId, plannedAmount)
        drugs.save(drug)
        return drug.requirePlanOf(userId)
    }

    @Transactional
    fun cancelPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting treatment plan for user {} and drug {}", userId, drugId)

        drugs.save(lock(drugId, userId).cancelPlan(userId))
    }

    /**
     * Списывает приём с плана и с остатка препарата.
     *
     * `null` означает «план исчерпан» либо «препарат кончился», а не «план не найден»:
     * отсутствующий план отвергается 404 ещё до списания.
     */
    @Transactional
    fun recordIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): TreatmentPlan? {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)

        val outcome = lock(drugId, userId).applyIntake(userId, quantityConsumed)
        val left = outcome.drug
        if (left == null) {
            drugs.delete(drugId)
            return null
        }
        drugs.save(left)
        return outcome.plan
    }
}
