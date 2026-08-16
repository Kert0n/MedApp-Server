package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.hibernate.annotations.Formula
import java.math.BigDecimal
import java.util.*

/**
 * Препарат вместе со своими планами лечения — корень агрегата.
 *
 * Правила остатка и планов живут здесь, а не в сервисах: сколько можно списать, можно ли
 * увеличить план и что происходит с планами, когда препарата стало меньше, — вопросы к
 * самому препарату. Сервис остаётся тем, кто загружает агрегат, сохраняет его и переводит
 * доменные отказы в коды ответа.
 *
 * Свойства объявлены через `var` потому, что Hibernate заполняет их при загрузке; это
 * требование отображения, а не разрешение менять препарат мимо методов ниже.
 */
@Entity
@Table(
    name = "user_drugs",
    indexes = [
        Index(name = "ix_user_drugs_name", columnList = "name"),
        Index(name = "ix_user_drugs_med_kit_id", columnList = "med_kit_id")
    ]
)
class Drug(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    @NotNull
    @Size(max = 300)
    @Column(name = "name", nullable = false, length = 300)
    var name: String,

    quantity: BigDecimal,

    @NotNull
    @Size(max = 50)
    @Column(name = "quantity_unit", nullable = false, length = 50)
    var quantityUnit: String,

    @Size(max = 100)
    @Column(name = "form_type", length = 100)
    var formType: String?,

    @Size(max = 200)
    @Column(name = "category", length = 200)
    var category: String?,

    @Size(max = 300)
    @Column(name = "manufacturer", length = 300)
    var manufacturer: String?,

    @Size(max = 100)
    @Column(name = "country", length = 100)
    var country: String?,

    @Column(name = "description", length = Integer.MAX_VALUE)
    var description: String?,

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "med_kit_id",
        nullable = false,
        // Определение задано явно, чтобы схема, сгенерированная Hibernate в тестах, совпадала
        // с db/schema.sql: иначе каскад проверялся бы только в одной из двух схем.
        foreignKey = ForeignKey(
            name = "user_drugs_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKit,

    /**
     * Планы лечения этого препарата — внутренность агрегата.
     *
     * Каскад и `orphanRemoval` означают, что план не существует отдельно от препарата:
     * добавление в набор создаёт строку, удаление из набора её удаляет, удаление препарата
     * уносит планы с собой.
     */
    @OneToMany(mappedBy = "drug", fetch = FetchType.LAZY, cascade = [CascadeType.ALL], orphanRemoval = true)
    var treatmentPlans: MutableSet<TreatmentPlan> = mutableSetOf()

) {

    /** Остаток препарата, нормализованный до масштаба колонки `NUMERIC(19,6)`. */
    @NotNull
    @Column(name = "quantity", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var quantity: BigDecimal = quantity.toQuantityScale()
        set(value) {
            field = value.toQuantityScale()
        }

    /**
     * Сумма планов **в том виде, в каком она лежала в базе на момент загрузки**.
     *
     * Считается базой в том же SELECT, которым читается препарат, поэтому список приходит
     * вместе с суммами одним запросом — этим и пользуются проекции чтения.
     *
     * В решениях агрегата участвовать не может: как только команда добавила, уменьшила или
     * сжала план, значение здесь устарело — оно не пересчитывается ни при изменении
     * коллекции, ни при flush. Именно поэтому прежний код был вынужден дописывать сюда
     * `-= списанное` руками. Всё, что решает агрегат, он решает по [plannedTotal].
     *
     * Из кода на Kotlin это свойство не читается: его единственные потребители — запросы
     * проекций, где оно пишется как `d.storedPlannedTotal`.
     */
    @Formula("(SELECT COALESCE(SUM(u.planned_amount), 0) FROM usings u WHERE u.drug_id = id)")
    var storedPlannedTotal: BigDecimal = BigDecimal.ZERO

    // ── Планы ────────────────────────────────────────────────────────────────────

    /**
     * Сколько препарата разобрано планами — по собственной коллекции агрегата.
     *
     * Единственная сумма, по которой принимаются решения: она учитывает изменения текущей
     * транзакции, ещё не дошедшие до базы, в отличие от [storedPlannedTotal].
     */
    val plannedTotal: BigDecimal
        get() = treatmentPlans.fold(BigDecimal.ZERO) { sum, plan -> sum + plan.plannedAmount }

    /**
     * Остаток, не занятый ни одним планом.
     *
     * Приватно: наружу такую разность отдаёт форма чтения, где обе величины взяты из одного
     * запроса. Здесь она нужна только двум проверкам ниже.
     */
    private val availableQuantity: BigDecimal
        get() = quantity - plannedTotal

    fun planOf(userId: UUID): TreatmentPlan? = treatmentPlans.find { it.planKey.userId == userId }

    fun requirePlanOf(userId: UUID): TreatmentPlan = planOf(userId) ?: throw NoSuchTreatmentPlan()

    /**
     * Резервирует количество за пользователем.
     *
     * Один пользователь — один план на препарат: составной ключ этого не допускает, и
     * повторная попытка означает, что клиент хотел изменить существующий план.
     */
    fun createPlan(user: User, amount: BigDecimal): TreatmentPlan {
        val planned = requirePositive(amount)
        if (planOf(user.id) != null) throw TreatmentPlanAlreadyExists()
        if (planned > availableQuantity) throw PlannedAmountExceedsStock()

        val plan = TreatmentPlan(
            planKey = TreatmentPlanKey(user.id, id),
            user = user,
            drug = this,
            plannedAmount = planned
        )
        treatmentPlans.add(plan)
        return plan
    }

    /** Меняет размер плана. Свой прежний размер план при проверке не занимает. */
    fun changePlan(userId: UUID, amount: BigDecimal): TreatmentPlan {
        val planned = requirePositive(amount)
        val plan = requirePlanOf(userId)
        if (planned > availableQuantity + plan.plannedAmount) throw PlannedAmountExceedsStock()

        plan.plannedAmount = planned
        return plan
    }

    /** Пользователь отказывается от своего плана. */
    fun cancelPlan(userId: UUID) {
        treatmentPlans.remove(requirePlanOf(userId))
    }

    /**
     * Убирает план пользователя, если он есть.
     *
     * Отличается от [cancelPlan] тем, что отсутствие плана здесь не ошибка: это не решение
     * владельца плана, а следствие того, что доступ к препарату пропал.
     */
    fun revokePlanOf(userId: UUID) {
        treatmentPlans.removeIf { it.planKey.userId == userId }
    }

    // ── Остаток ──────────────────────────────────────────────────────────────────

    /** Описательные поля; `null` означает «оставить как есть». */
    fun describe(details: DrugDetails) {
        details.name?.let { name = it }
        details.quantityUnit?.let { quantityUnit = it }
        details.formType?.let { formType = it }
        details.category?.let { category = it }
        details.manufacturer?.let { manufacturer = it }
        details.country?.let { country = it }
        details.description?.let { description = it }
    }

    /**
     * Пополнение запаса.
     *
     * Уменьшать остаток этим методом нельзя: расход выражается списанием, и только оно
     * говорит, сколько именно ушло. Присвоение меньшего числа под конкурентным доступом
     * теряет чужие списания, случившиеся между чтением и записью, а планы пришлось бы
     * пересчитывать по неизвестно чему.
     */
    fun increaseQuantityTo(newQuantity: BigDecimal) {
        val increased = requirePositive(newQuantity)
        if (increased <= quantity) throw QuantityNotIncreased()
        quantity = increased
    }

    /**
     * Списание вне плана лечения.
     *
     * Возвращает `true`, если препарат кончился: строку удаляет вызывающий, потому что
     * удаление — работа хранилища, а не агрегата.
     */
    fun consume(amount: BigDecimal): Boolean {
        val consumed = requirePositive(amount)
        if (consumed > quantity) throw InsufficientStock()

        quantity -= consumed
        if (quantity.isZero()) {
            treatmentPlans.clear()
            return true
        }
        reconcilePlansToStock()
        return false
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

        plan.plannedAmount -= consumed
        quantity -= consumed

        if (quantity.isZero()) {
            treatmentPlans.clear()
            return IntakeOutcome(drugExhausted = true, plan = null)
        }
        if (plan.plannedAmount.isZero()) {
            treatmentPlans.remove(plan)
            return IntakeOutcome(drugExhausted = false, plan = null)
        }
        return IntakeOutcome(drugExhausted = false, plan = plan)
    }

    /**
     * Переезд в другую аптечку.
     *
     * Планы тех, кто к целевой аптечке доступа не имеет, исчезают вместе с доступом: иначе
     * препарат уносил бы с собой чужие резервы в аптечку, которую эти люди не видят.
     */
    fun moveTo(targetMedKit: MedKit, accessibleUserIds: Set<UUID>) {
        treatmentPlans.removeIf { it.planKey.userId !in accessibleUserIds }
        medKit = targetMedKit
    }

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
    private fun reconcilePlansToStock() {
        val planned = plannedTotal
        if (planned <= quantity) return

        treatmentPlans.forEach { plan ->
            plan.plannedAmount = plan.plannedAmount
                .multiply(quantity)
                .divide(planned, QUANTITY_SCALE, QUANTITY_ROUNDING)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Drug

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    companion object {
        /**
         * Единственный способ завести препарат: количество проверяется здесь, а не в
         * контроллере, поэтому препарата с нулевым или отрицательным остатком не бывает.
         */
        fun create(
            medKit: MedKit,
            name: String,
            quantity: BigDecimal,
            quantityUnit: String,
            formType: String? = null,
            category: String? = null,
            manufacturer: String? = null,
            country: String? = null,
            description: String? = null
        ): Drug = Drug(
            name = name,
            quantity = requirePositive(quantity),
            quantityUnit = quantityUnit,
            formType = formType,
            category = category,
            manufacturer = manufacturer,
            country = country,
            description = description,
            medKit = medKit
        )

        private fun requirePositive(amount: BigDecimal): BigDecimal {
            if (!amount.isPositive()) throw InvalidQuantity()
            return amount.toQuantityScale()
        }
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
 * Оба исхода наблюдаемы снаружи: приём может исчерпать план, а может исчерпать и сам
 * препарат, и это разные события.
 */
data class IntakeOutcome(
    /** Препарат кончился этим приёмом; строку удаляет вызывающий. */
    val drugExhausted: Boolean,
    /** Оставшийся план или `null`, если приём его исчерпал. */
    val plan: TreatmentPlan?
)
