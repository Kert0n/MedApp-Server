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

        return drugs.save(drug, request.stated)
    }

    @Transactional(propagation = MANDATORY)
    fun delete(drug: Drug, stated: Long) {
        logger.debug("Deleting drug: {}", drug.id)
        drugs.delete(drug, stated)
    }

    /**
     * Списывает съеденное.
     *
     * `null` — «пачка кончилась и уничтожена», а не «не найдена»: недоступная отвергается 404
     * ещё до списания.
     */
    @Transactional(propagation = MANDATORY)
    fun consume(drug: Drug, quantity: BigDecimal, stated: Long): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drug.id)

        // `null` — приём опустошил пачку. Уничтожать её отсюда нельзя: вместе с пачкой
        // исчезают брони, а это чужой агрегат. Решение принимает `DrugDisposal`.
        val left = drug.consume(Quantity(quantity, drug.quantity.unit)) ?: return null
        return drugs.save(left, stated)
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
    fun moveTo(drug: Drug, target: MedKit, stated: Long): Drug {
        logger.debug("Moving drug {} to medkit {}", drug.id, target.id)

        val moved = drug.moveTo(target.id)
        return drugs.save(moved, stated)
    }
}
