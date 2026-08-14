package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.QUANTITY_ROUNDING
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.api.DrugUpdateDTO
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
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

        drug.consumeUnplanned(quantity)
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
        // Блокировка первым действием. Приём — самый горячий мутирующий путь, и он
        // единственный из трёх шёл без неё: consume и updateTreatmentPlan лочили, а здесь
        // остаток читался и уменьшался на живую. Двое из общей аптечки, принимающие
        // одновременно, гонялись за одним значением, и одно списание терялось.
        drugService.findByIdForUserForUpdate(drugId, userId)
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
        using.reduceBy(quantityConsumed)
        using.drug.consumePlanned(quantityConsumed)

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
            drugService.delete(withUsings(drug))
            return null
        }
        if (drug.totalPlannedAmount <= drug.quantity) return drug

        // Drug id and amounts left out on purpose: together they describe someone's stock.
        logger.warn("Planned quantity exceeded current stock; treatment plans were reduced")

        // Частное обычно бесконечная периодическая дробь, и BigDecimal без явного scale
        // бросил бы ArithmeticException.
        //
        // Запас в десять знаков и округление к ближайшему, а не вниз: коэффициент, укороченный
        // вниз, портит ровные случаи — 30 планов при сжатии до двух третей давали 19.999999
        // вместо 20. Вниз округляются произведения, и этого достаточно: погрешность
        // коэффициента здесь порядка 1e-16, то есть на десять порядков меньше младшего разряда
        // количества, и перекрыть её потерей от округления произведений она не может.
        val reduceFactor = drug.quantity.divide(
            drug.totalPlannedAmount,
            QUANTITY_SCALE + 10,
            QUANTITY_ROUNDING
        )
        // Сумма присваивается настоящая, а не остаток. Поле производное (@Formula считает
        // SUM(planned_amount)), и приписывать ему значение quantity значит врать себе же:
        // до ближайшей перезагрузки клиент видел бы в plannedQuantity завышенное число.
        drug.totalPlannedAmount = shrinkPlans(withUsings(drug), reduceFactor)
        return drugService.save(drug)
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
        drugService.findWithPlans(drug.id) ?: drug

    /**
     * Сжимает планы пропорционально и возвращает их новую сумму.
     *
     * Инвариант, который нужен коду, — «сумма планов **не больше** остатка», а не точное
     * равенство: так он и проверяется выше. Округление вниз даёт его по построению — каждый
     * план не превышает свою точную долю, а доли в сумме дают остаток.
     *
     * Прежняя версия округляла HALF_UP и потому могла получить сумму **больше** остатка, а
     * следом компенсировала разницу, отдавая её самому большому плану. То есть третий шаг
     * чинил то, что натворил первый. Округление вниз убирает и причину, и лечение: цена —
     * несколько миллионных долей таблетки, потерянных при сжатии резерва, что для операции
     * «планов больше, чем осталось» единственно уместное направление.
     *
     * saveAll не нужен: планы — управляемые сущности, изменения уйдут при сбросе контекста.
     */
    private fun shrinkPlans(drug: Drug, factor: BigDecimal): BigDecimal {
        val usings = drug.usings
        if (usings.isEmpty()) return BigDecimal.ZERO

        // setScale здесь явный: сеттер plannedAmount округляет HALF_UP, и без этого
        // произведение приехало бы к нему уже вверх.
        usings.forEach {
            it.plannedAmount = (it.plannedAmount * factor).setScale(QUANTITY_SCALE, RoundingMode.DOWN)
        }
        return usings.fold(BigDecimal.ZERO) { sum, using -> sum + using.plannedAmount }
    }
}
