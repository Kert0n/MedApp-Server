package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.QUANTITY_ROUNDING
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class QuantityReductionService(
    private val drugRepository: DrugRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java)

) {
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
