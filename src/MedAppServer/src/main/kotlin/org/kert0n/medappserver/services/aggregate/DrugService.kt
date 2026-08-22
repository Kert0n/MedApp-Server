package org.kert0n.medappserver.services.aggregate

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugDetails
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.Quantity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату упаковки.
 *
 * Команды однотипны: прочитать состояние, вызвать метод домена, отдать результат хранилищу.
 * Правила — в `domain.Drug`, запросы — за `DrugStore`, брони — в своём агрегате.
 */
@Service
class DrugService(
    private val drugs: DrugStore,
    private val catalogue: CatalogueService
) {

    private val logger = LoggerFactory.getLogger(DrugService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /**
     * Упаковка вызывающего. Чужой для него не существует — так устроен запрос.
     *
     * Единственный способ получить `Drug`: отдельной проверки доступа нет и не нужно, само
     * чтение ею и является. Отсюда и правило команд — они принимают то, что здесь получено.
     */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun get(drugId: Uuid, userId: Uuid): Drug {
        logger.debug("Reading drug {} for user {}", drugId, userId)
        return drugs.find(drugId, userId) ?: throw notFound()
    }

    @Transactional(propagation = MANDATORY, readOnly = true)
    fun ofMedKit(medKitId: Uuid, userId: Uuid): List<Drug> {
        logger.debug("Reading drugs of medkit {} for user {}", medKitId, userId)
        return drugs.findAllInMedKit(medKitId, userId)
    }

    /** Упаковки всех аптечек участника — одним запросом. */
    @Transactional(propagation = MANDATORY, readOnly = true)
    fun allOf(userId: Uuid): List<Drug> {
        logger.debug("Reading all drugs available to user {}", userId)
        return drugs.findAllOfUser(userId)
    }

    /** Недоступная и несуществующая упаковка отвечают одинаково: иначе чужая обнаружится. */
    private fun notFound() = NotAMember()

    // ── Команды препарата ────────────────────────────────────────────────────────

    @Transactional(propagation = MANDATORY)
    fun create(request: NewDrug, medKit: MedKit): Drug {
        logger.debug("Creating drug {} in medkit {}", request.name, medKit.id)

        val drug = Drug(
            medKitId = medKit.id,
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

    /** По идентификатору — то же самое плюс своё чтение, в котором и проверяется доступ. */
    @Transactional(propagation = MANDATORY)
    fun update(drugId: Uuid, request: DrugEdit, userId: Uuid): Drug =
        update(get(drugId, userId), request)

    @Transactional(propagation = MANDATORY)
    fun update(drug: Drug, request: DrugEdit): Drug {
        logger.debug("Updating drug: {}", drug.id)

        var drug = drug
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

    @Transactional(propagation = MANDATORY)
    fun delete(drugId: Uuid, userId: Uuid) = delete(get(drugId, userId))

    @Transactional(propagation = MANDATORY)
    fun delete(drug: Drug) {
        logger.debug("Deleting drug: {}", drug.id)
        drugs.delete(drug)
    }

    /**
     * Списывает съеденное.
     *
     * `null` — «пачка кончилась и уничтожена», а не «не найдена»: недоступная отвергается 404
     * ещё до списания.
     */
    @Transactional(propagation = MANDATORY)
    fun consume(drugId: Uuid, quantity: BigDecimal, userId: Uuid): Drug? =
        consume(get(drugId, userId), quantity)

    @Transactional(propagation = MANDATORY)
    fun consume(drug: Drug, quantity: BigDecimal): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drug.id)

        // `null` — приём опустошил пачку. Уничтожать её отсюда нельзя: вместе с пачкой
        // исчезают брони, а это чужой агрегат. Решение принимает `DrugDisposal`.
        val left = drug.consume(Quantity(quantity, drug.quantity.unit)) ?: return null
        return drugs.save(left)
    }

    /**
     * Все упаковки аптечки — в другую, одним запросом.
     *
     * Поштучный переезд честнее по слоям, но стоит команды на пачку: сотня пачек — сотня
     * загрузок. Судьбу броней решает вызывающий: они в чужом агрегате.
     */
    @Transactional(propagation = MANDATORY)
    fun moveAll(source: MedKit, target: MedKit) {
        logger.debug("Moving all drugs of medkit {} to {}", source.id, target.id)
        drugs.moveAllToMedKit(source.id, target.id)
    }

    /** Брони, потерявшие доступ, убирает межагрегатный сценарий: они в чужом агрегате. */
    @Transactional(propagation = MANDATORY)
    fun moveTo(drug: Drug, target: MedKit): Drug {
        logger.debug("Moving drug {} to medkit {}", drug.id, target.id)

        val moved = drug.moveTo(target.id)
        return drugs.save(moved)
    }
}
