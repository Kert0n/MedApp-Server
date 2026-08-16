package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.DrugDetails
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.DrugView
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*

/**
 * Единственный вход к агрегату `Drug` — вместе с его планами лечения.
 *
 * Правила здесь не живут: сервис проверяет доступ, загружает агрегат, вызывает его метод и
 * сохраняет результат. Планы обслуживаются отсюда же, потому что план не существует без
 * препарата и все проверки на нём — это проверки против остатка.
 */
@Service
class DrugService(
    private val drugRepository: DrugRepository,
    private val userService: UserService
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

    // ── Поиск сущности для команд ────────────────────────────────────────────────
    //
    // Имена говорят о поведении: `find` может не найти и возвращает `null`, `require`
    // считает отсутствие ошибкой вызывающего и даёт 404, `lock` вдобавок берёт блокировку
    // строки. Раньше все три назывались `find…`, и по имени нельзя было понять, что
    // вызываешь.

    @Transactional(readOnly = true)
    fun findById(drugId: UUID): Drug? = drugRepository.findByIdOrNull(drugId)

    @Transactional(readOnly = true)
    fun requireById(drugId: UUID): Drug = findById(drugId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun findAccessible(drugId: UUID, userId: UUID): Drug? =
        drugRepository.findAccessible(drugId, userId)

    @Transactional(readOnly = true)
    fun requireAccessible(drugId: UUID, userId: UUID): Drug =
        findAccessible(drugId, userId) ?: throw notFound()

    /** То же плюс блокировка строки на время транзакции. */
    @Transactional(readOnly = true)
    fun lockAccessible(drugId: UUID, userId: UUID): Drug =
        drugRepository.lockAccessible(drugId, userId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun findAllByMedKit(medKitId: UUID): List<Drug> {
        logger.debug("Finding all drugs for medkit: {}", medKitId)
        return drugRepository.findAllByMedKitId(medKitId)
    }

    /**
     * Недоступный препарат и несуществующий отвечают одинаково: иначе по коду ответа можно
     * было бы узнать, что такой препарат существует в чужой аптечке.
     */
    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")

    // ── Команды препарата ────────────────────────────────────────────────────────
    //
    // Все они устроены одинаково: блокировка доступного препарата, вызов метода агрегата,
    // сохранение. Планы загружаются вторым запросом, когда команда до них дотрагивается;
    // одним запросом вместе с корнем их брать нельзя, пока стоит `FOR UPDATE`.

    @Transactional
    fun create(createDTO: DrugCreateRequest, medKit: MedKit, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", createDTO.name, userId)

        val drug = Drug.create(
            medKit = medKit,
            name = createDTO.name,
            quantity = createDTO.quantity,
            quantityUnit = createDTO.quantityUnit,
            formType = createDTO.formType,
            category = createDTO.category,
            manufacturer = createDTO.manufacturer,
            country = createDTO.country,
            description = createDTO.description
        )

        return drugRepository.save(drug)
    }

    @Transactional
    fun update(drugId: UUID, updateDTO: DrugPatchRequest, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        val drug = lockAccessible(drugId, userId)

        updateDTO.quantity?.let { drug.increaseQuantityTo(it) }
        drug.describe(
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

        return drugRepository.save(drug)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) {
        logger.debug("Deleting drug: {}", drugId)

        val drug = requireAccessible(drugId, userId)
        drugRepository.delete(drug)
    }

    /**
     * Списывает количество вне плана лечения.
     *
     * `null` здесь означает «препарат кончился и удалён этим списанием», а не «не найден»:
     * недоступный препарат отвергается 404 ещё до списания.
     */
    @Transactional
    fun consumeDrug(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = lockAccessible(drugId, userId)

        if (drug.consume(quantity)) {
            drugRepository.delete(drug)
            return null
        }
        return drugRepository.save(drug)
    }

    // ── Команды планов лечения ───────────────────────────────────────────────────
    //
    // План — часть агрегата, поэтому меняется через его корень: и проверка против остатка,
    // и запрет второго плана того же пользователя формулируются только там, где известны
    // остаток и все остальные планы разом.

    @Transactional
    fun createPlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Creating treatment plan for user {} and drug {}", userId, drugId)

        val user = userService.findById(userId)
        val drug = lockAccessible(drugId, userId)

        val plan = drug.createPlan(user, plannedAmount)
        drugRepository.save(drug)
        return plan
    }

    @Transactional
    fun changePlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan {
        logger.debug("Updating treatment plan for user {} and drug {}", userId, drugId)

        val drug = lockAccessible(drugId, userId)

        val plan = drug.changePlan(userId, plannedAmount)
        drugRepository.save(drug)
        return plan
    }

    @Transactional
    fun cancelPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting treatment plan for user {} and drug {}", userId, drugId)

        val drug = lockAccessible(drugId, userId)

        drug.cancelPlan(userId)
        drugRepository.save(drug)
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

        val drug = lockAccessible(drugId, userId)

        val outcome = drug.applyIntake(userId, quantityConsumed)
        if (outcome.drugExhausted) {
            drugRepository.delete(drug)
            return null
        }
        drugRepository.save(drug)
        return outcome.plan
    }
}
