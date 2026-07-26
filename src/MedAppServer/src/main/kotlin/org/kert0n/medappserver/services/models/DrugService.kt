package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.controller.DrugCreateDTO
import org.kert0n.medappserver.controller.DrugDTO
import org.kert0n.medappserver.controller.DrugUpdateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.toQuantityScale
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.services.orchestrators.QuantityReductionService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*

@Service
class DrugService(
    private val drugRepository: DrugRepository,
    private val quantityReductionService: QuantityReductionService
) {

    private val logger = LoggerFactory.getLogger(DrugService::class.java)

    @Transactional(readOnly = true)
    fun findById(drugId: UUID): Drug {
        logger.debug("Finding drug by ID: {}", drugId)
        return drugRepository.findByIdOrNull(drugId) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Drug not found: $drugId"
        )
    }

    @Transactional(readOnly = true)
    fun findByIdForUser(drugId: UUID, userId: UUID): Drug {
        logger.debug("Finding drug {} for user {}", drugId, userId)
        return drugRepository.findByIdAndMedKitUsersId(drugId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")
    }


    /**
     * Берёт препарат под блокировку на запись.
     *
     * Без readOnly сознательно: метод выполняет `SELECT ... FOR UPDATE`, а readOnly переводит
     * соединение в режим только для чтения и ставит flush-режим MANUAL. Не падало это лишь
     * потому, что метод всегда вызывается из внешней read-write транзакции, и при
     * распространении REQUIRED внутренний readOnly игнорируется. То есть аннотация вводила в
     * заблуждение и сломалась бы при первом вызове напрямую.
     */
    @Transactional
    fun findByIdForUserForUpdate(drugId: UUID, userId: UUID): Drug {
        logger.debug("Finding locked drug {} for user {}", drugId, userId)
        return drugRepository.findByIdAndMedKitUsersIdForUpdate(drugId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")
    }

    @Transactional(readOnly = true)
    fun findAllByMedKit(medKitId: UUID): List<Drug> {
        logger.debug("Finding all drugs for medkit: {}", medKitId)
        return drugRepository.findAllByMedKitId(medKitId)
    }

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<Drug> {
        logger.debug("Finding all drugs for user: {}", userId)
        return drugRepository.findByUsingsUserId(userId)
    }


    @Transactional
    fun create(createDTO: DrugCreateDTO, medKit: MedKit, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", createDTO.name, userId)

        val drug = Drug(
            name = createDTO.name,
            quantity = createDTO.quantity,
            quantityUnit = createDTO.quantityUnit,
            formType = createDTO.formType,
            category = createDTO.category,
            manufacturer = createDTO.manufacturer,
            country = createDTO.country,
            description = createDTO.description,
            medKit = medKit
        )

        return drugRepository.save(drug)
    }

    @Transactional
    fun update(drugId: UUID, updateDTO: DrugUpdateDTO, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        val drug = findByIdForUserForUpdate(drugId, userId)

        updateDTO.name?.let { drug.name = it }
        updateDTO.quantity?.let {
            val oldQuantity = drug.quantity
            drug.quantity = it
            // Handle quantity reduction - may need to adjust treatment plans
            if (it < oldQuantity) {
                quantityReductionService.handleQuantityReduction(drug)
            }
        }
        updateDTO.quantityUnit?.let { drug.quantityUnit = it }
        updateDTO.formType?.let { drug.formType = it }
        updateDTO.category?.let { drug.category = it }
        updateDTO.manufacturer?.let { drug.manufacturer = it }
        updateDTO.country?.let { drug.country = it }
        updateDTO.description?.let { drug.description = it }

        return drugRepository.save(drug)
    }

    @Transactional
    fun delete(drugId: UUID, userId: UUID) {
        logger.debug("Deleting drug: {}", drugId)

        val drug = findByIdForUser(drugId, userId)
        drugRepository.delete(drug)
    }


    @Transactional
    fun consumeDrug(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = findByIdForUserForUpdate(drugId, userId)

        if (quantity > drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        drug.quantity = (drug.quantity - quantity).toQuantityScale()
        drugRepository.save(drug)
        return quantityReductionService.handleQuantityReduction(drug)

    }


    //    @Transactional(readOnly = true)
//    fun getPlannedQuantity(drugId: UUID): Double {
//        return drugRepository.sumPlannedAmount(drugId)
//    }
    @Transactional(readOnly = true)
    fun toDrugDTO(drug: Drug): DrugDTO {
        return DrugDTO(
            id = drug.id,
            name = drug.name,
            quantity = drug.quantity,
            plannedQuantity = drug.totalPlannedAmount,
            quantityUnit = drug.quantityUnit,
            formType = drug.formType,
            category = drug.category,
            manufacturer = drug.manufacturer,
            country = drug.country,
            description = drug.description,
            medKitId = drug.medKit.id
        )
    }


}
