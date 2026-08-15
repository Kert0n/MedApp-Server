package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.QUANTITY_ROUNDING
import org.kert0n.medappserver.db.model.QUANTITY_SCALE
import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.*

@Service
class QuantityReductionService(
    private val usingRepository: UsingRepository,
    private val drugRepository: DrugRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java)

) {
    fun handleQuantityReduction(drug: Drug): Drug? {
        logger.debug("Handling quantity reduction for drug: {}", drug.id)

        // isZero, а не == BigDecimal.ZERO: equals учитывает scale, и 0.000000 не равен ZERO.
        if (drug.quantity.isZero()) {
            // Планы удаляются запросом, а не каскадом по drug.usings.
            //
            // Каскад объявлен (CascadeType.ALL, orphanRemoval), но коллекция ленивая и в этом
            // пути не синхронизирована: createTreatmentPlan сохраняет план через
            // usingRepository.save и в drug.usings его не добавляет. Каскад тогда проходит по
            // пустому набору, а удаление препарата упирается в внешний ключ usings_drug_fkey.
            //
            // На Double это не проявлялось: quantity == 0.0 после дробных списаний было
            // недостижимо, поэтому ветка удаления практически не исполнялась.
            val plans = usingRepository.findAllByKeyDrugId(drug.id)
            if (plans.isNotEmpty()) {
                usingRepository.deleteAll(plans)
                // Явный flush: порядок удалений между разными сущностями иначе определяется
                // Hibernate, и препарат может уйти в DELETE раньше своих планов.
                usingRepository.flush()
            }
            drugRepository.delete(drug)
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
        handleUsingReduction(drug.id, reduceFactor, drug.quantity)
        drug.totalPlannedAmount = drug.quantity
        return drugRepository.save(drug)
        // TODO FIREBASE NOTIFICATION
    }

    /**
     * Уменьшает все планы пропорционально, сохраняя инвариант «сумма планов равна остатку».
     *
     * Каждое произведение округляется до scale базы, поэтому сумма округлённых значений почти
     * никогда не равна цели ровно. Разницу нельзя оставить: именно из таких копеек и вырастало
     * расхождение, из-за которого инвариант переставал держаться. Остаток отдаётся одному
     * плану — самому большому, чтобы поправка гарантированно не загнала его в минус; при равных
     * значениях выбор детерминирован по userId, иначе результат зависел бы от порядка выборки.
     */
    private fun handleUsingReduction(drugId: UUID, factor: BigDecimal, targetTotal: BigDecimal) {
        val usings = usingRepository.findAllByKeyDrugId(drugId)
        if (usings.isEmpty()) return

        // Округление до scale базы делает сеттер plannedAmount, поэтому произведение здесь
        // записывается как есть, а сумма ниже читается уже округлённой.
        usings.forEach { it.plannedAmount = it.plannedAmount * factor }

        val rounded = usings.fold(BigDecimal.ZERO) { sum, using -> sum + using.plannedAmount }
        val residual = targetTotal - rounded
        if (!residual.isZero()) {
            val adjusted = usings.maxWith(
                compareBy<TreatmentPlan>({ it.plannedAmount }, { it.key.userId })
            )
            adjusted.plannedAmount = adjusted.plannedAmount + residual
        }

        usingRepository.saveAll(usings)
    }
}
