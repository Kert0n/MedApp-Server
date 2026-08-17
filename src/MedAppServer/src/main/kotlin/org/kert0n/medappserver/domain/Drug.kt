package org.kert0n.medappserver.domain

import java.math.BigDecimal
import java.util.UUID

/**
 * Препарат вместе со своими планами лечения — корень агрегата.
 *
 * Состояние неизменяемо: команда не меняет объект, а возвращает следующее состояние. Поэтому
 * тут нет ни `var`, ни присваиваний снаружи — правило «менять препарат можно только его
 * методами» держит компилятор.
 *
 * Отображением занимается `db.model.DrugData`; здесь про базу не известно ничего, а связь с
 * аптечкой выражена идентификатором.
 *
 * Проверки входа стоят в [init], а не в фабрике: конструктор и так возвращает препарат, и
 * второй способ его получить ничего не добавлял. Состояний, которые этим проверкам
 * противоречат, команды не строят — исчерпание возвращает `null`, а не препарат с нулём.
 */
data class Drug(
    val id: UUID = UUID.randomUUID(),
    val medKitId: UUID,
    val name: String,
    val quantity: BigDecimal,
    // TODO: единица измерения и форма — свободный текст, тогда как в справочнике те же
    //  величины уже ссылки на quantity_units и form_types. Перевод на сильные типы задевает
    //  схему и публичный контракт и делается отдельным PR.
    val quantityUnit: String,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    val plans: List<TreatmentPlan> = emptyList()
) {

    init {
        if (!quantity.isPositive()) throw InvalidQuantity()
    }

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

        return copy(plans = plans + TreatmentPlan(userId, id, planned))
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
     * Новое значение остатка — в любую сторону.
     *
     * Вверх это пополнение, вниз — исправление учёта: пользователь пересчитал упаковку и
     * увидел меньше, чем числилось. Планы при уменьшении сжимаются тем же правилом, что и
     * при списании, поэтому отдельного запрета тут не нужно: агрегат загружен целиком, и
     * решение принимается по всем планам сразу.
     */
    fun changeQuantityTo(newQuantity: BigDecimal): Drug {
        val changed = requirePositive(newQuantity)
        return copy(quantity = changed).reconcilePlansToStock()
    }

    /**
     * Списание вне плана лечения.
     *
     * `null` означает, что препарат кончился: строку удаляет вызывающий, потому что удаление
     * — работа хранилища. Препарат с нулевым остатком не собирается вовсе: такого состояния
     * не бывает, и конструктор его не пропустит.
     */
    fun consume(amount: BigDecimal): Drug? {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        val left = (quantity - consumed).toQuantityScale()
        if (left.isZero()) return null

        return copy(quantity = left).reconcilePlansToStock()
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
        if (left.isZero()) return IntakeOutcome(drug = null, plan = null)

        // Остаток плана считается числом, а не планом: исчерпанного плана не существует, и
        // собрать его нечем — то же правило, по которому не собирается препарат с нулём.
        val leftInPlan = (plan.plannedAmount - consumed).toQuantityScale()
        if (leftInPlan.isZero()) {
            return IntakeOutcome(copy(quantity = left, plans = plans.filterNot { it.userId == userId }), null)
        }

        val reduced = plan.copy(plannedAmount = leftInPlan)
        return IntakeOutcome(
            drug = copy(quantity = left, plans = plans.map { if (it.userId == userId) reduced else it }),
            plan = reduced
        )
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

    private fun requirePositive(amount: BigDecimal): BigDecimal {
        if (!amount.isPositive()) throw InvalidQuantity()
        return amount.toQuantityScale()
    }

    /**
     * Препарат — сущность, а не значение: два его состояния с разными остатками остаются
     * одним и тем же препаратом. Поэтому сравнение по идентификатору, а не по всем полям, —
     * иначе версия, которая появится вместе с оптимистичной блокировкой, начнёт делать
     * агрегат «другим» после каждой записи.
     */
    override fun equals(other: Any?): Boolean = this === other || (other is Drug && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}

/**
 * План лечения: сколько препарата участник зарезервировал под себя.
 *
 * Идентификатор препарата хранится в самом плане, хотя внутри агрегата он и так известен:
 * тот же тип отдаётся на запрос «мои планы по всем препаратам», а там без него не обойтись.
 */
data class TreatmentPlan(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
) {
    init {
        if (!plannedAmount.isPositive()) throw InvalidQuantity()
    }
}

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

/**
 * Чем закончился приём.
 *
 * Оба поля обнуляемые, и это не случайность: приём может исчерпать план, а может исчерпать
 * и сам препарат. `drug == null` означает, что препарата больше нет и строку надо удалить.
 */
data class IntakeOutcome(
    val drug: Drug?,
    val plan: TreatmentPlan?
)
