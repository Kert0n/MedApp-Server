package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.DrugCreateDTO
import org.kert0n.medappserver.api.DrugUpdateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.repository.DrugRepository
import org.hibernate.Hibernate
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.isZero
import java.math.BigDecimal
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
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
     * Правка полей препарата с согласованием планов.
     *
     * Согласование запускается только при уменьшении остатка: увеличение инвариант «сумма
     * планов не больше остатка» нарушить не может.
     *
     * Раньше правка и согласование были разнесены по двум бинам — `DrugService.update` и
     * `QuantityReductionService.updateDrug`, который его же и вызывал, взяв блокировку
     * заранее. Блокировка при этом бралась дважды: `SELECT ... FOR UPDATE` в оркестраторе
     * ради предыдущего количества и он же внутри `update`.
     */
    @Transactional
    fun update(drugId: UUID, updateDTO: DrugUpdateDTO, userId: UUID): Drug {
        logger.debug("Updating drug: {}", drugId)

        val drug = findByIdForUserForUpdate(drugId, userId)
        val previousQuantity = drug.quantity

        updateDTO.name?.let { drug.name = it }
        updateDTO.quantity?.let { drug.quantity = it }
        updateDTO.quantityUnit?.let { drug.quantityUnit = it }
        updateDTO.formType?.let { drug.formType = it }
        updateDTO.category?.let { drug.category = it }
        updateDTO.manufacturer?.let { drug.manufacturer = it }
        updateDTO.country?.let { drug.country = it }
        updateDTO.description?.let { drug.description = it }

        drugRepository.save(drug)
        if (drug.quantity < previousQuantity) {
            reconcileAfterReduction(drug)
        }
        return drug
    }

    /**
     * Внеплановый расход: списать количество мимо планов и подтянуть планы под новый остаток.
     *
     * Возвращает `null`, если препарат кончился и удалён.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = findByIdForUserForUpdate(drugId, userId)

        if (quantity > drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        drug.consumeUnplanned(quantity)
        drugRepository.save(drug)
        return reconcileAfterReduction(drug)
    }

    /**
     * Приём по плану: уменьшить план и остаток, затем согласовать.
     *
     * Возвращает `null`, когда плана больше нет — он обнулился приёмом либо исчез вместе с
     * кончившимся препаратом.
     */
    @Transactional
    fun applyIntake(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): Using? {
        logger.debug("Recording intake for user {} and drug {}, quantity: {}", userId, drugId, quantityConsumed)
        // Блокировка первым действием. Приём — самый горячий мутирующий путь, и он
        // единственный из трёх шёл без неё: consume и правка плана лочили, а здесь остаток
        // читался и уменьшался на живую. Двое из общей аптечки, принимающие одновременно,
        // гонялись за одним значением, и одно списание терялось.
        val locked = findByIdForUserForUpdate(drugId, userId)

        // План берётся из коллекции агрегата, а не отдельным запросом в UsingRepository:
        // препарат с планами всё равно нужен согласованию ниже, а Drug владеет ими через
        // CascadeType.ALL и orphanRemoval. Так этому сервису хватает своего репозитория.
        val drug = withUsings(locked)
        val using = drug.usings.firstOrNull { it.usingKey.userId == userId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such using")

        if (quantityConsumed > using.plannedAmount) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Consumed quantity exceeds planned amount")
        }
        if (quantityConsumed > drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient drug quantity available")
        }

        // Порядок обязателен: план уменьшается до согласования, иначе оно посчитает сумму
        // планов по старому значению.
        using.reduceBy(quantityConsumed)
        drug.consumePlanned(quantityConsumed)

        // null означает, что препарат кончился и удалён вместе со всеми планами. Продолжать
        // нельзя: возврат плана на удалённый препарат — ссылка в никуда. Раньше это значение
        // отбрасывалось, и корректность держалась на том, что комбинация «остаток нулевой,
        // план ненулевой» недостижима из-за проверок в других методах, то есть на совпадении.
        if (reconcileAfterReduction(drug) == null) return null

        if (using.plannedAmount.isZero()) {
            // Через коллекцию: orphanRemoval удалит строку сам, а Drug.usings остаётся
            // правдой до конца транзакции.
            drug.usings.remove(using)
            return null
        }
        // Явное сохранение не нужно: план — управляемая сущность, изменение уйдёт при сбросе.
        return using
    }

    /**
     * Приводит планы в соответствие с уменьшившимся остатком.
     *
     * Возвращает `null`, если препарат кончился и был удалён.
     */
    private fun reconcileAfterReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        // isZero, а не == BigDecimal.ZERO: equals учитывает scale, и 0.000000 не равен ZERO.
        if (drug.quantity.isZero()) {
            // Планы удаляет каскад — так и объявлено у Drug.usings (CascadeType.ALL,
            // orphanRemoval). Единственное, что для этого нужно, — инициализированная
            // коллекция: по пустому набору каскад проходит впустую, и DELETE препарата
            // упирается в usings_drug_fkey. Поэтому препарат берётся с графом.
            //
            // На Double эта ветка почти не исполнялась: quantity == 0.0 после дробных
            // списаний было недостижимо, поэтому дефект и не проявлялся.
            drugRepository.delete(withUsings(drug))
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        // Drug id and amounts left out on purpose: together they describe someone's stock.
        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        withUsings(drug).shrinkPlansToStock()
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    /**
     * Препарат с инициализированной коллекцией планов.
     *
     * Запрос по id внутри той же транзакции отдаёт ровно тот экземпляр, что уже в контексте
     * персистентности, — новых объектов не появляется, а коллекция заполняется одним
     * оператором вместо ленивой инициализации с отдельным SELECT на каждого пользователя
     * (`Using.user` объявлен EAGER).
     *
     * Повторный вызов внутри одной транзакции запроса не делает: если коллекция уже
     * загружена, возвращается тот же управляемый экземпляр. Без этой проверки согласование
     * после приёма выполняло бы тот же SELECT второй раз подряд.
     */
    private fun withUsings(drug: Drug): Drug =
        if (Hibernate.isInitialized(drug.usings)) drug
        else drugRepository.findWithUsingsById(drug.id) ?: drug

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
}
