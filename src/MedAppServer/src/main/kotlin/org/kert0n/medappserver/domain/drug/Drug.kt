package org.kert0n.medappserver.domain.drug

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.domain.quantity.QUANTITY_ROUNDING
import org.kert0n.medappserver.domain.quantity.QUANTITY_SCALE
import org.kert0n.medappserver.domain.quantity.isPositive
import org.kert0n.medappserver.domain.quantity.isZero
import org.kert0n.medappserver.domain.quantity.toQuantityScale
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.IntakeExceedsPlan
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.NoSuchTreatmentPlan
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.error.QuantityNotIncreased
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists

/**
 * Препарат вместе со своими планами лечения — корень агрегата.
 *
 * Состояние неизменяемо: команда не меняет объект, а возвращает следующее состояние. Поэтому
 * тут нет ни `var`, ни присваиваний снаружи — правило «менять препарат можно только его
 * методами» держит компилятор, а не договорённость.
 *
 * Отображением занимается `db.model.Drug`; здесь про базу не известно ничего, а связи с
 * другими агрегатами выражены идентификаторами.
 */
@ConsistentCopyVisibility
data class Drug private constructor(
    val id: UUID,
    val medKitId: UUID,
    val name: String,
    val quantity: BigDecimal,
    val quantityUnit: String,
    val formType: String?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val plans: List<TreatmentPlan>
) {

    /** Сколько препарата разобрано планами. */
    val plannedTotal: BigDecimal
        get() = plans.fold(BigDecimal.ZERO) { sum, plan -> sum + plan.plannedAmount }

    /** Остаток, не занятый ни одним планом. */
    val availableQuantity: BigDecimal
        get() = quantity - plannedTotal

    fun planOf(userId: UUID): TreatmentPlan? = plans.find { it.userId == userId }

    fun requirePlanOf(userId: UUID): TreatmentPlan = planOf(userId) ?: throw NoSuchTreatmentPlan()

    // ── Планы ────────────────────────────────────────────────────────────────────

    /**
     * Резервирует количество за пользователем.
     *
     * Один пользователь — один план на препарат: составной ключ этого не допускает, и
     * повторная попытка означает, что клиент хотел изменить существующий план.
     */
    fun createPlan(userId: UUID, amount: BigDecimal): Drug {
        val planned = requirePositive(amount)
        if (planOf(userId) != null) throw TreatmentPlanAlreadyExists()
        if (planned > availableQuantity) throw PlannedAmountExceedsStock()

        return copy(plans = plans + TreatmentPlan(userId, planned))
    }

    /** Меняет размер плана. Свой прежний размер план при проверке не занимает. */
    fun changePlan(userId: UUID, amount: BigDecimal): Drug {
        val planned = requirePositive(amount)
        val plan = requirePlanOf(userId)
        if (planned > availableQuantity + plan.plannedAmount) throw PlannedAmountExceedsStock()

        return copy(plans = plans.map { if (it.userId == userId) it.copy(plannedAmount = planned) else it })
    }

    /** Пользователь отказывается от своего плана. */
    fun cancelPlan(userId: UUID): Drug {
        requirePlanOf(userId)
        return copy(plans = plans.filterNot { it.userId == userId })
    }

    /**
     * Убирает план пользователя, если он есть.
     *
     * Отличается от [cancelPlan] тем, что отсутствие плана здесь не ошибка: это не решение
     * владельца плана, а следствие того, что доступ к препарату пропал.
     */
    fun revokePlanOf(userId: UUID): Drug = copy(plans = plans.filterNot { it.userId == userId })

    // ── Остаток ──────────────────────────────────────────────────────────────────

    /** Описательные поля; `null` означает «оставить как есть». */
    fun describe(details: DrugDetails): Drug = copy(
        name = details.name ?: name,
        quantityUnit = details.quantityUnit ?: quantityUnit,
        formType = details.formType ?: formType,
        category = details.category ?: category,
        manufacturer = details.manufacturer ?: manufacturer,
        country = details.country ?: country,
        description = details.description ?: description
    )

    /**
     * Пополнение запаса.
     *
     * Уменьшать остаток этим методом нельзя: расход выражается списанием, и только оно
     * говорит, сколько именно ушло. Присвоение меньшего числа под конкурентным доступом
     * теряет чужие списания, случившиеся между чтением и записью, а планы пришлось бы
     * пересчитывать по неизвестно чему.
     */
    fun increaseQuantityTo(newQuantity: BigDecimal): Drug {
        val increased = requirePositive(newQuantity)
        if (increased <= quantity) throw QuantityNotIncreased()
        return copy(quantity = increased)
    }

    /** Списание вне плана лечения. */
    fun consume(amount: BigDecimal): ConsumptionOutcome {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        val left = (quantity - consumed).toQuantityScale()
        if (left.isZero()) {
            return ConsumptionOutcome(copy(quantity = left, plans = emptyList()), exhausted = true)
        }
        return ConsumptionOutcome(copy(quantity = left).reconcilePlansToStock(), exhausted = false)
    }

    /**
     * Приём по плану: уменьшает и план, и остаток.
     *
     * Планы других участников трогать не приходится — приём не может увести сумму планов за
     * остаток, поскольку убавляет обе величины одинаково.
     */
    fun applyIntake(userId: UUID, amount: BigDecimal): IntakeOutcome {
        val consumed = requirePositive(amount)
        val plan = requirePlanOf(userId)
        if (consumed > plan.plannedAmount) throw IntakeExceedsPlan()
        if (consumed > quantity) throw InsufficientStock()

        val left = (quantity - consumed).toQuantityScale()
        val reducedPlan = plan.copy(plannedAmount = (plan.plannedAmount - consumed).toQuantityScale())

        if (left.isZero()) {
            return IntakeOutcome(copy(quantity = left, plans = emptyList()), plan = null, drugExhausted = true)
        }
        if (reducedPlan.plannedAmount.isZero()) {
            val without = copy(quantity = left, plans = plans.filterNot { it.userId == userId })
            return IntakeOutcome(without, plan = null, drugExhausted = false)
        }
        val updated = copy(
            quantity = left,
            plans = plans.map { if (it.userId == userId) reducedPlan else it }
        )
        return IntakeOutcome(updated, plan = reducedPlan, drugExhausted = false)
    }

    /**
     * Переезд в другую аптечку.
     *
     * Планы тех, кто к целевой аптечке доступа не имеет, исчезают вместе с доступом: иначе
     * препарат уносил бы с собой чужие резервы в аптечку, которую эти люди не видят.
     */
    fun moveTo(targetMedKitId: UUID, accessibleUserIds: Set<UUID>): Drug = copy(
        medKitId = targetMedKitId,
        plans = plans.filter { it.userId in accessibleUserIds }
    )

    /**
     * Сжимает планы до остатка пропорционально их размеру.
     *
     * Умножение идёт до деления: отдельный коэффициент `остаток / запланировано` пришлось бы
     * округлить, и деление 60 на 90 превратило бы план 30 в 19.999999 вместо 20. При таком
     * порядке точное частное получается там, где оно вообще существует.
     *
     * Округление вниз на каждом плане оставляет инвариант в силе: сумма точных долей равна
     * остатку, значит сумма округлённых вниз его не превышает.
     */
    private fun reconcilePlansToStock(): Drug {
        val planned = plannedTotal
        if (planned <= quantity) return this

        return copy(
            plans = plans.map { plan ->
                plan.copy(
                    plannedAmount = plan.plannedAmount
                        .multiply(quantity)
                        .divide(planned, QUANTITY_SCALE, QUANTITY_ROUNDING)
                )
            }
        )
    }

    companion object {
        /**
         * Новый препарат: количество проверяется здесь, поэтому препарата с нулевым или
         * отрицательным остатком не бывает.
         */
        fun create(
            medKitId: UUID,
            name: String,
            quantity: BigDecimal,
            quantityUnit: String,
            formType: String? = null,
            category: String? = null,
            manufacturer: String? = null,
            country: String? = null,
            description: String? = null,
            id: UUID = UUID.randomUUID()
        ): Drug = Drug(
            id = id,
            medKitId = medKitId,
            name = name,
            quantity = requirePositive(quantity),
            quantityUnit = quantityUnit,
            formType = formType,
            category = category,
            manufacturer = manufacturer,
            country = country,
            description = description,
            plans = emptyList()
        )

        /**
         * Восстановление уже существующего препарата из хранилища.
         *
         * Отдельно от [create] и намеренно без проверок: сохранённое состояние не обязано
         * проходить входной контроль заново, а требование «остаток строго положителен»
         * относится к заведению препарата, а не к его чтению.
         */
        fun fromStored(
            id: UUID,
            medKitId: UUID,
            name: String,
            quantity: BigDecimal,
            quantityUnit: String,
            formType: String?,
            category: String?,
            manufacturer: String?,
            country: String?,
            description: String?,
            plans: List<TreatmentPlan>
        ): Drug = Drug(
            id, medKitId, name, quantity, quantityUnit,
            formType, category, manufacturer, country, description, plans
        )

        private fun requirePositive(amount: BigDecimal): BigDecimal {
            if (!amount.isPositive()) throw InvalidQuantity()
            return amount.toQuantityScale()
        }
    }
}

/** План лечения: сколько препарата участник зарезервировал под себя. */
data class TreatmentPlan(
    val userId: UUID,
    val plannedAmount: BigDecimal
)

/** Описательные поля препарата; `null` — «не менять». */
data class DrugDetails(
    val name: String? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

/** Чем закончилось списание. `exhausted` — препарат кончился и подлежит удалению. */
data class ConsumptionOutcome(
    val drug: Drug,
    val exhausted: Boolean
)

/**
 * Чем закончился приём.
 *
 * Оба исхода наблюдаемы снаружи: приём может исчерпать план, а может исчерпать и сам
 * препарат, и это разные события.
 */
data class IntakeOutcome(
    val drug: Drug,
    /** Оставшийся план или `null`, если приём его исчерпал. */
    val plan: TreatmentPlan?,
    /** Препарат кончился этим приёмом; строку удаляет вызывающий. */
    val drugExhausted: Boolean
)
