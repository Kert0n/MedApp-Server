package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.QUANTITY_ROUNDING
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.api.DrugUpdateDTO
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

/**
 * Все изменения остатка препарата и согласование планов после них.
 *
 * Сюда стянуты три входа, которые раньше жили в модельных сервисах и оттуда звали этот
 * оркестратор снизу вверх: [consume] (внеплановый расход), [applyIntake] (приём по плану) и
 * [updateDrug] (правка карточки, где количество могли уменьшить). Каждый из них — это
 * координация двух сущностей, то есть работа оркестратора, а не сервиса одного агрегата.
 *
 * [applyIntake] лежит здесь, а не в [IntakeService], по одной технической причине:
 * `IntakeService.record` нетранзакционный (кеш идемпотентности заполняется после коммита), и
 * вызов транзакционного метода того же класса пошёл бы мимо прокси Spring — транзакция
 * молча исчезла бы. Разные бины эту границу сохраняют.
 */
@Service
class QuantityReductionService(
    private val drugRepository: DrugRepository,
    private val drugService: DrugService,
    private val usingService: UsingService,
    val logger: Logger = LoggerFactory.getLogger(QuantityReductionService::class.java)

) {
    /**
     * Внеплановый расход: списать количество мимо планов и подтянуть планы под новый остаток.
     *
     * Возвращает `null`, если препарат кончился и удалён.
     */
    @Transactional
    fun consume(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? {
        logger.debug("Consuming {} of drug {}", quantity, drugId)

        val drug = drugService.findByIdForUserForUpdate(drugId, userId)

        if (quantity > drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        drug.quantity = drug.quantity - quantity
        drugService.save(drug)
        return handleQuantityReduction(drug)
    }

    /**
     * Правка карточки препарата. Согласование планов запускается только при уменьшении
     * остатка — увеличение инвариант «сумма планов не больше остатка» нарушить не может.
     */
    @Transactional
    fun updateDrug(drugId: UUID, updateDTO: DrugUpdateDTO, userId: UUID): Drug {
        val previousQuantity = drugService.findByIdForUserForUpdate(drugId, userId).quantity
        val drug = drugService.update(drugId, updateDTO, userId)
        if (drug.quantity < previousQuantity) {
            handleQuantityReduction(drug)
        }
        return drug
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
        val using = usingService.findByUserAndDrug(userId, drugId)

        if (quantityConsumed > using.plannedAmount) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Consumed quantity exceeds planned amount"
            )
        }

        if (quantityConsumed > using.drug.quantity) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient drug quantity available")
        }

        // Порядок обязателен: план уменьшается до согласования, иначе оно посчитает сумму
        // планов по старому значению.
        using.plannedAmount = maxOf(BigDecimal.ZERO, using.plannedAmount - quantityConsumed)
        using.drug.quantity = using.drug.quantity - quantityConsumed
        // Дешевле, чем перезагружать препарат: формула сама не пересчитается в транзакции.
        using.drug.totalPlannedAmount = using.drug.totalPlannedAmount - quantityConsumed

        // null означает, что препарат кончился и удалён вместе со всеми планами. Продолжать
        // нельзя: save ниже вставил бы план на удалённый препарат, то есть нарушил бы внешний
        // ключ. Раньше это значение отбрасывалось, и корректность держалась на том, что
        // комбинация «остаток нулевой, план ненулевой» недостижима из-за проверок в других
        // методах — то есть на совпадении, а не на явном условии.
        if (handleQuantityReduction(using.drug) == null) return null

        if (using.plannedAmount.isZero()) {
            // Через коллекцию: orphanRemoval удалит строку сам, а Drug.usings остаётся
            // правдой до конца транзакции.
            using.drug.usings.remove(using)
            return null
        }
        return usingService.save(using)
    }

    fun handleQuantityReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        // isZero, а не == BigDecimal.ZERO: equals учитывает scale, и 0.000000 не равен ZERO.
        if (drug.quantity.isZero()) {
            // Планы удаляет каскад — так и объявлено у Drug.usings (CascadeType.ALL,
            // orphanRemoval). Единственное, что для этого нужно, — инициализированная
            // коллекция: по пустому набору каскад проходит впустую, и DELETE препарата
            // упирается в usings_drug_fkey. Поэтому препарат перечитывается с графом.
            //
            // На Double эта ветка почти не исполнялась: quantity == 0.0 после дробных
            // списаний было недостижимо, поэтому дефект и не проявлялся.
            drugRepository.delete(withUsings(drug))
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        // Drug id and amounts left out on purpose: together they describe someone's stock.
        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        // Делим с запасом знаков: частное обычно бесконечная периодическая дробь, и BigDecimal
        // без явного scale бросил бы ArithmeticException.
        val reduceFactor = drug.quantity.divide(
            drug.totalPlannedAmount,
            QUANTITY_SCALE + 4,
            QUANTITY_ROUNDING
        )
        handleUsingReduction(withUsings(drug), reduceFactor)
        drug.totalPlannedAmount = drug.quantity
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    /**
     * Возвращает тот же препарат с инициализированной коллекцией планов.
     *
     * Запрос по id внутри той же транзакции отдаёт ровно тот экземпляр, что уже в контексте
     * персистентности, — новых объектов не появляется, а коллекция заполняется одним
     * оператором вместо ленивой инициализации с отдельным SELECT на каждого пользователя
     * (`Using.user` объявлен EAGER).
     */
    private fun withUsings(drug: Drug): Drug =
        drugRepository.findWithUsingsById(drug.id) ?: drug

    /**
     * Уменьшает все планы пропорционально, сохраняя инвариант «сумма планов равна остатку».
     *
     * Каждое произведение округляется до scale базы, поэтому сумма округлённых значений почти
     * никогда не равна цели ровно. Разницу нельзя оставить: именно из таких копеек и вырастало
     * расхождение, из-за которого инвариант переставал держаться. Остаток отдаётся одному
     * плану — самому большому, чтобы поправка гарантированно не загнала его в минус; при равных
     * значениях выбор детерминирован по userId, иначе результат зависел бы от порядка выборки.
     */
    private fun handleUsingReduction(drug: Drug, factor: BigDecimal) {
        val usings = drug.usings
        if (usings.isEmpty()) return

        // Округление до scale базы делает сеттер plannedAmount, поэтому произведение здесь
        // записывается как есть, а сумма ниже читается уже округлённой.
        usings.forEach { it.plannedAmount = it.plannedAmount * factor }

        val rounded = usings.fold(BigDecimal.ZERO) { sum, using -> sum + using.plannedAmount }
        val residual = drug.quantity - rounded
        if (!residual.isZero()) {
            val adjusted = usings.maxWith(
                compareBy<Using>({ it.plannedAmount }, { it.usingKey.userId })
            )
            adjusted.plannedAmount = adjusted.plannedAmount + residual
        }
        // saveAll не нужен: планы — управляемые сущности, изменения уйдут при сбросе
        // контекста. Вызов repository здесь только создавал бы вид, что без него не запишется.
    }
}
