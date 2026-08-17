package org.kert0n.medappserver.services.models

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugDetails
import org.kert0n.medappserver.domain.IntakeOutcome
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.Quantity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату препарата — вместе с его планами лечения.
 *
 * Каждая команда устроена одинаково: взять состояние, проверить предусловие, вызвать метод
 * домена, отдать результат хранилищу. Правил здесь нет — они в `domain.Drug`; строк и запросов
 * тоже нет — они за `DrugStore`.
 *
 * `expectedVersion` — версия, которую клиент предъявил заголовком `If-Match`. Совпадение
 * проверяется до применения правил: команда, собранная по устаревшему состоянию, не должна
 * выполниться даже тогда, когда по новым данным она допустима.
 */
@Service
class DrugService(
    private val drugs: DrugStore,
    private val catalogue: CatalogueService
) {

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

    /**
     * Недоступный препарат и несуществующий отвечают одинаково: иначе по коду ответа можно
     * было бы узнать, что такой препарат существует в чужой аптечке.
     */
    private fun notFound() = NotAMember()

    /**
     * Состояние, по которому команда будет собрана, вместе с проверкой предусловия.
     *
     * Блокировки здесь больше нет: конкуренцию держит версия. Порядок проверок — доступ,
     * потом версия: несуществующий и недоступный препарат обязаны отвечать одинаково, и
     * узнать по коду ответа, что чужой препарат существует, нельзя даже угадав его версию.
     */
    private fun loadForCommand(drugId: UUID, userId: UUID, expectedVersion: Long): Drug =
        (drugs.findAccessible(drugId, userId) ?: throw notFound()).requireVersion(expectedVersion)

    // ── Команды препарата ────────────────────────────────────────────────────────

    @Transactional
    fun create(request: DrugCreateRequest, medKitId: UUID, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", request.name, userId)

        val drug = Drug(
            medKitId = medKitId,
            name = request.name,
            quantity = Quantity(request.quantity, catalogue.requireQuantityUnit(request.quantityUnitId)),
            formType = request.formTypeId?.let { catalogue.requireFormType(it) },
            category = request.category,
            manufacturer = request.manufacturer,
            country = request.country,
            description = request.description
        )

        drugs.insert(drug)
        return drug
    }

    @Transactional
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID, expectedVersion: Long): Drug {
        logger.debug("Updating drug: {}", drugId)

        var drug = loadForCommand(drugId, userId, expectedVersion)
        // Единица перевешивается первой: количество ниже собирается уже в ней.
        request.quantityUnitId?.let { drug = drug.relabelUnitTo(catalogue.requireQuantityUnit(it)) }
        request.quantity?.let { drug = drug.changeQuantityTo(Quantity(it, drug.quantity.unit)) }
        drug = drug.describe(
            DrugDetails(
                name = request.name,
                formType = request.formTypeId?.let { catalogue.requireFormType(it) },
                category = request.category,
                manufacturer = request.manufacturer,
                country = request.country,
                description = request.description
            )
        )

        return drugs.save(drug)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID, expectedVersion: Long) {
        logger.debug("Deleting drug: {}", drugId)

        val drug = loadForCommand(drugId, userId, expectedVersion)
        drugs.delete(drug.id)
    }

    /**
     * Списывает количество вне плана лечения.
     *
     * `null` означает «препарат кончился и удалён этим списанием», а не «не найден»:
     * недоступный препарат отвергается 404 ещё до списания.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID, expectedVersion: Long): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = loadForCommand(drugId, userId, expectedVersion)
        val left = drug.consume(Quantity(quantity, drug.quantity.unit))
        if (left == null) {
            drugs.delete(drugId)
            return null
        }
        return drugs.save(left)
    }

    /** Переезд препарата в другую аптечку вместе с судьбой планов. */
    @Transactional
    fun moveTo(
        drugId: UUID,
        targetMedKitId: UUID,
        accessibleUserIds: Set<UUID>,
        userId: UUID,
        expectedVersion: Long
    ): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)

        val moved = loadForCommand(drugId, userId, expectedVersion).moveTo(targetMedKitId, accessibleUserIds)
        return drugs.save(moved)
    }

    // ── Команды планов лечения ───────────────────────────────────────────────────

    /**
     * Команды планов возвращают препарат, а не план.
     *
     * План — часть агрегата, и меняется вместе с ним: и версия, которую клиент предъявит в
     * следующий раз, и остаток после резервирования принадлежат препарату. Отдав один план,
     * пришлось бы вторым запросом добирать то, что уже посчитано.
     */
    @Transactional
    fun createPlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal, expectedVersion: Long): Drug {
        logger.debug("Creating treatment plan for user {} and drug {}", userId, drugId)

        val loaded = loadForCommand(drugId, userId, expectedVersion)
        return drugs.save(loaded.createPlan(userId, Quantity(plannedAmount, loaded.quantity.unit)))
    }

    @Transactional
    fun changePlan(userId: UUID, drugId: UUID, plannedAmount: BigDecimal, expectedVersion: Long): Drug {
        logger.debug("Updating treatment plan for user {} and drug {}", userId, drugId)

        val loaded = loadForCommand(drugId, userId, expectedVersion)
        return drugs.save(loaded.changePlan(userId, Quantity(plannedAmount, loaded.quantity.unit)))
    }

    @Transactional
    fun cancelPlan(userId: UUID, drugId: UUID, expectedVersion: Long) {
        logger.debug("Deleting treatment plan for user {} and drug {}", userId, drugId)

        drugs.save(loadForCommand(drugId, userId, expectedVersion).cancelPlan(userId))
    }

    /**
     * Списывает приём с плана и с остатка препарата.
     *
     * В исходе оба поля обнуляемы: `plan == null` — план исчерпан, `drug == null` — кончился
     * сам препарат и удалён этим приёмом. Ни то ни другое не означает «не найдено»:
     * отсутствующий план отвергается 404 ещё до списания.
     */
    @Transactional
    fun recordIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal, expectedVersion: Long): IntakeOutcome {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)

        val loaded = loadForCommand(drugId, userId, expectedVersion)
        val outcome = loaded.applyIntake(userId, Quantity(quantityConsumed, loaded.quantity.unit))
        val left = outcome.drug
        if (left == null) {
            drugs.delete(drugId)
            return outcome
        }
        return outcome.copy(drug = drugs.save(left))
    }
}
