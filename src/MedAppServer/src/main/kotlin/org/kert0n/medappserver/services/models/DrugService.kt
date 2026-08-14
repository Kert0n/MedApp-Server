package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.DrugCreateDTO
import org.kert0n.medappserver.api.DrugDTO
import org.kert0n.medappserver.api.DrugUpdateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.repository.DrugRepository
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
    private val drugRepository: DrugRepository
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

    /**
     * Правка полей препарата.
     *
     * Согласование планов после уменьшения количества сюда не входит: это координация двух
     * сущностей, ею занимается `QuantityReductionService.updateDrug`, который этот метод и
     * вызывает. Здесь только свой агрегат.
     */
    @Transactional
    fun update(drugId: UUID, updateDTO: DrugUpdateDTO, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        val drug = findByIdForUserForUpdate(drugId, userId)

        updateDTO.name?.let { drug.name = it }
        updateDTO.quantity?.let { drug.quantity = it }
        updateDTO.quantityUnit?.let { drug.quantityUnit = it }
        updateDTO.formType?.let { drug.formType = it }
        updateDTO.category?.let { drug.category = it }
        updateDTO.manufacturer?.let { drug.manufacturer = it }
        updateDTO.country?.let { drug.country = it }
        updateDTO.description?.let { drug.description = it }

        return drugRepository.save(drug)
    }

    /** Сохранить препарат. Нужен оркестраторам: репозиторий им напрямую недоступен. */
    @Transactional
    fun save(drug: Drug): Drug = drugRepository.save(drug)

    /** Удалить препарат. Планы уходят каскадом — при условии, что коллекция загружена. */
    @Transactional
    fun delete(drug: Drug) = drugRepository.delete(drug)

    /**
     * Препарат с загруженными планами и проверкой доступа.
     *
     * Граф обязателен там, где дальше правится коллекция планов: `Drug.usings` объявлена с
     * `orphanRemoval`, и по неинициализированной коллекции удаление проходит впустую.
     */
    @Transactional(readOnly = true)
    fun findByIdForUserWithPlans(drugId: UUID, userId: UUID): Drug =
        drugRepository.findByIdAndMedKitUsersIdWithUsings(drugId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Drug not found or access denied")

    /**
     * Тот же препарат, но с инициализированной коллекцией планов и без проверки доступа.
     *
     * Для путей, где доступ уже проверен выборкой под блокировку: повторять проверку значит
     * повторять join к аптечке и её участникам ни за чем.
     */
    @Transactional(readOnly = true)
    fun findWithPlans(drugId: UUID): Drug? = drugRepository.findWithUsingsById(drugId)

    /** Все препараты аптечки вместе с планами — одним запросом. */
    @Transactional(readOnly = true)
    fun findAllWithPlansByMedKit(medKitId: UUID): List<Drug> =
        drugRepository.findAllWithUsingsByMedKitId(medKitId)

    /**
     * То же для нескольких аптечек сразу.
     *
     * Пустой список отсекается: `IN ()` в SQL невыразим, а запрос ради заведомо пустого
     * ответа не нужен.
     */
    @Transactional(readOnly = true)
    fun findAllWithPlansByMedKits(medKitIds: Collection<UUID>): List<Drug> =
        if (medKitIds.isEmpty()) emptyList()
        else drugRepository.findAllWithUsingsByMedKitIdIn(medKitIds)

    @Transactional
    fun delete(drugId: UUID, userId: UUID) {
        logger.debug("Deleting drug: {}", drugId)

        val drug = findByIdForUser(drugId, userId)
        drugRepository.delete(drug)
    }




    //    @Transactional(readOnly = true)
//    fun getPlannedQuantity(drugId: UUID): Double {
//        return drugRepository.sumPlannedAmount(drugId)
//    }


}
