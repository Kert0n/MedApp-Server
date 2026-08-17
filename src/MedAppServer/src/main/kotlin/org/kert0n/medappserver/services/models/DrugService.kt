package org.kert0n.medappserver.services.models

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.store.DrugStore
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.DrugDetails
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.Quantity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Единственный вход к агрегату упаковки.
 *
 * Каждая команда устроена одинаково: взять состояние под блокировкой, вызвать метод домена,
 * отдать результат хранилищу. Правил здесь нет — они в `domain.Drug`; строк и запросов тоже
 * нет — они за `DrugStore`. Броней здесь нет вовсе: ими распоряжается их владелец через
 * `ReservationService`.
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

    private fun lock(drugId: UUID, userId: UUID): Drug =
        drugs.lockAccessible(drugId, userId) ?: throw notFound()

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
    fun update(drugId: UUID, request: DrugPatchRequest, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        var drug = lock(drugId, userId)
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
     * Списывает съеденное.
     *
     * `null` означает «упаковка кончилась и уничтожена этим списанием», а не «не найдена»:
     * недоступная упаковка отвергается 404 ещё до списания. Различия «приём по плану» и
     * «расход вне плана» больше нет: съеденное уменьшает пачку, и всё.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = lock(drugId, userId)
        val left = drug.consume(Quantity(quantity, drug.quantity.unit))
        if (left == null) {
            drugs.delete(drugId)
            return null
        }
        drugs.save(left)
        return left
    }

    /**
     * Переезд упаковки в другую аптечку.
     *
     * Судьба броней решается не здесь: они в чужом агрегате, и убрать те, что потеряли доступ,
     * — работа межагрегатного сценария.
     */
    @Transactional
    fun moveTo(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)

        val moved = lock(drugId, userId).moveTo(targetMedKitId)
        drugs.save(moved)
        return moved
    }
}
