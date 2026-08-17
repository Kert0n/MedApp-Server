package org.kert0n.medappserver.domain

import java.util.UUID

/**
 * Препарат вместе со своими планами лечения — корень агрегата.
 *
 * Состояние неизменяемо: команда не меняет объект, а возвращает следующее состояние. Поэтому
 * тут нет ни `var`, ни присваиваний снаружи — правило «менять препарат можно только его
 * методами» держит компилятор.
 *
 * Конструктор отвечает за целостность целиком, а не выборочно. Пока правила жили в
 * JPA-сущности, так было нельзя: Hibernate собирает объект пустым конструктором и заполняет
 * поля, поэтому проверять на входе было нечего. Здесь препарат собирается только этим
 * конструктором — и состояния, противоречащего правилам, не существует ни в памяти, ни при
 * чтении из базы: несогласованная строка не прочитается молча, а упадёт на сборке.
 */
data class Drug(
    val id: UUID = UUID.randomUUID(),
    val medKitId: UUID,
    val name: String,
    val quantity: Quantity,
    val formType: FormType? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null,
    val plans: List<TreatmentPlan> = emptyList(),

    /**
     * Версия хранимого состояния — непрозрачный токен, а не поле препарата.
     *
     * Домен его не толкует и не меняет: команды возвращают состояние с той же версией, а
     * продвигает её хранилище, когда изменение доходит до базы. Здесь версия нужна ровно для
     * одного — чтобы агрегат мог отказать команде, собранной по устаревшему состоянию
     * ([requireVersion]). В сравнении препаратов она не участвует по той же причине: два
     * состояния с разными версиями — один и тот же препарат.
     */
    val version: Long = 0
) {

    init {
        if (!quantity.isPositive) throw InvalidQuantity()
        if (plans.any { it.drugId != id }) throw ForeignTreatmentPlan()
        if (plans.distinctBy { it.userId }.size != plans.size) throw TreatmentPlanAlreadyExists()
        // Единица плана не может отличаться от единицы препарата: складываются они постоянно,
        // и разошедшись однажды, дальше давали бы бессмысленные суммы.
        if (plans.any { it.plannedAmount.unit != quantity.unit }) throw QuantityUnitMismatch()
        if (plannedTotal > quantity) throw PlannedAmountExceedsStock()
    }

    /** Сколько препарата разобрано планами. */
    val plannedTotal: Quantity
        get() = plans.fold(quantity.zero()) { sum, plan -> sum + plan.plannedAmount }

    /** Остаток, не занятый ни одним планом. */
    val availableQuantity: Quantity
        get() = quantity - plannedTotal

    /**
     * Предусловие команды: клиент собрал её по тому состоянию, которое лежит в базе сейчас.
     *
     * Проверяется до применения правил, а не после: смысл предусловия в том, чтобы команда по
     * устаревшему состоянию не выполнилась вовсе, даже если по новым данным она допустима.
     */
    fun requireVersion(expected: Long): Drug {
        if (version != expected) throw StaleAggregateVersion()
        return this
    }

    fun planOf(userId: UUID): TreatmentPlan? = plans.find { it.userId == userId }

    fun requirePlanOf(userId: UUID): TreatmentPlan = planOf(userId) ?: throw NoSuchTreatmentPlan()

    // ── Планы ────────────────────────────────────────────────────────────────────

    /**
     * Резервирует количество за пользователем.
     *
     * Один пользователь — один план на препарат: составной ключ этого не допускает, и
     * повторная попытка означает, что клиент хотел изменить существующий план.
     */
    fun createPlan(userId: UUID, amount: Quantity): Drug {
        val planned = requirePositive(amount)
        if (planOf(userId) != null) throw TreatmentPlanAlreadyExists()
        if (planned > availableQuantity) throw PlannedAmountExceedsStock()

        return copy(plans = plans + TreatmentPlan(userId, id, planned))
    }

    /** Меняет размер плана. Свой прежний размер план при проверке не занимает. */
    fun changePlan(userId: UUID, amount: Quantity): Drug {
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

    // ── Остаток ──────────────────────────────────────────────────────────────────

    /** Описательные поля; `null` означает «оставить как есть». */
    fun describe(details: DrugDetails): Drug = copy(
        name = details.name ?: name,
        formType = details.formType ?: formType,
        category = details.category ?: category,
        manufacturer = details.manufacturer ?: manufacturer,
        country = details.country ?: country,
        description = details.description ?: description
    )

    /**
     * Смена единицы измерения: те же числа с другой подписью.
     *
     * Нужна ровно для одного случая — единицу указали неверно при заведении. Пересчёта нет
     * и быть не может: перевести таблетки в миллилитры система не умеет, а притвориться, что
     * умеет, было бы хуже, чем не уметь. Планы переезжают вместе с остатком, иначе агрегат
     * распался бы на величины в разных единицах.
     */
    fun relabelUnitTo(unit: QuantityUnit): Drug = copy(
        quantity = Quantity(quantity.amount, unit),
        plans = plans.map { it.copy(plannedAmount = Quantity(it.plannedAmount.amount, unit)) }
    )

    /**
     * Новое значение остатка — в любую сторону.
     *
     * Вверх это пополнение, вниз — исправление учёта: пользователь пересчитал упаковку и
     * увидел меньше, чем числилось. Планы при уменьшении сжимаются тем же правилом, что и
     * при списании, — агрегат загружен целиком, и решение принимается по всем планам сразу.
     */
    fun changeQuantityTo(newQuantity: Quantity): Drug {
        val changed = requirePositive(newQuantity)
        return copy(quantity = changed, plans = plansScaledTo(changed))
    }

    /**
     * Списание вне плана лечения.
     *
     * `null` означает, что препарат кончился: строку удаляет вызывающий, потому что удаление
     * — работа хранилища. Препарат с нулевым остатком не собирается вовсе — конструктор его
     * не пропустит, и промежуточного состояния с нулём здесь нет.
     */
    fun consume(amount: Quantity): Drug? {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        val left = quantity - consumed
        if (left.isZero) return null

        return copy(quantity = left, plans = plansScaledTo(left))
    }

    /**
     * Приём по плану: уменьшает и план, и остаток.
     *
     * Планы других участников трогать не приходится — приём не может увести сумму планов за
     * остаток, поскольку убавляет обе величины одинаково. По той же причине здесь нет
     * проверки «хватает ли остатка»: приём не больше своего плана, план не больше суммы
     * планов, а сумма планов не больше остатка — это гарантирует конструктор.
     */
    fun applyIntake(userId: UUID, amount: Quantity): IntakeOutcome {
        val consumed = requirePositive(amount)
        val plan = requirePlanOf(userId)
        if (consumed > plan.plannedAmount) throw IntakeExceedsPlan()

        val left = quantity - consumed
        if (left.isZero) return IntakeOutcome(drug = null, plan = null)

        // Остаток плана считается величиной, а не планом: исчерпанного плана не существует,
        // и собрать его нечем — то же правило, по которому не собирается препарат с нулём.
        val leftInPlan = plan.plannedAmount - consumed
        if (leftInPlan.isZero) {
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
     * Планы, сжатые до остатка пропорционально их размеру.
     *
     * Считаются до сборки состояния, а не после: препарата, у которого планы больше остатка,
     * не существует, поэтому промежуточный объект собрать было бы нечем.
     */
    private fun plansScaledTo(stock: Quantity): List<TreatmentPlan> {
        val planned = plannedTotal
        if (planned <= stock) return plans

        return plans.map { plan ->
            plan.copy(plannedAmount = plan.plannedAmount.timesRatio(stock, planned))
        }
    }

    private fun requirePositive(amount: Quantity): Quantity {
        if (!amount.isPositive) throw InvalidQuantity()
        if (amount.unit != quantity.unit) throw QuantityUnitMismatch()
        return amount
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
    val plannedAmount: Quantity
) {
    init {
        if (!plannedAmount.isPositive) throw InvalidQuantity()
    }
}

/**
 * План вместе с версией препарата, которому он принадлежит.
 *
 * Существует ради чтения «мои планы по всем препаратам»: такой ответ собирается не из
 * агрегатов, а одним запросом по строкам планов, и версию корня взять больше неоткуда. В сам
 * [TreatmentPlan] её класть нельзя — внутри агрегата у каждого плана оказалась бы своя копия
 * общего числа, устаревающая при первой же записи.
 */
data class TreatmentPlanEntry(
    val plan: TreatmentPlan,
    val drugVersion: Long
)

/** Описательные поля препарата; `null` — «не менять». */
data class DrugDetails(
    val name: String? = null,
    val formType: FormType? = null,
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
