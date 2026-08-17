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
 * Команды однотипны: прочитать состояние, вызвать метод домена, отдать результат хранилищу.
 * Правила — в `domain.Drug`, запросы — за `DrugStore`, брони — в своём агрегате.
 *
 * Блокировок при чтении нет: одновременную запись ловит версия строки, и проигравшая
 * транзакция откатывается вместо того, чтобы ждать победившую.
 */
@Service
class DrugService(
    private val drugs: DrugStore,
    private val catalogue: CatalogueService
) {

    private val logger = LoggerFactory.getLogger(DrugService::class.java)

    // ── Чтение ───────────────────────────────────────────────────────────────────

    /** `null`, если упаковки нет или она недоступна вызывающему. */
    @Transactional(readOnly = true)
    fun find(drugId: UUID, userId: UUID): Drug? {
        logger.debug("Reading drug {} for user {}", drugId, userId)
        return drugs.findAccessible(drugId, userId)
    }

    /** Упаковка или 404. */
    @Transactional(readOnly = true)
    fun require(drugId: UUID, userId: UUID): Drug = find(drugId, userId) ?: throw notFound()

    @Transactional(readOnly = true)
    fun ofMedKit(medKitId: UUID): List<Drug> {
        logger.debug("Reading drugs of medkit {}", medKitId)
        return drugs.findAllInMedKit(medKitId)
    }

    /** Упаковки всех аптечек участника — одним запросом. */
    @Transactional(readOnly = true)
    fun accessibleTo(userId: UUID): List<Drug> {
        logger.debug("Reading all drugs available to user {}", userId)
        return drugs.findAllAccessibleTo(userId)
    }

    /** Недоступная и несуществующая упаковка отвечают одинаково: иначе чужая обнаружится. */
    private fun notFound() = NotAMember()

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

        var drug = require(drugId, userId)
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
     * `null` — «пачка кончилась и уничтожена», а не «не найдена»: недоступная отвергается 404
     * ещё до списания.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = require(drugId, userId)
        val left = drug.consume(Quantity(quantity, drug.quantity.unit))
        if (left == null) {
            drugs.delete(drugId)
            return null
        }
        drugs.save(left)
        return left
    }

    /** Брони, потерявшие доступ, убирает межагрегатный сценарий: они в чужом агрегате. */
    @Transactional
    fun moveTo(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)

        val moved = require(drugId, userId).moveTo(targetMedKitId)
        drugs.save(moved)
        return moved
    }
}
