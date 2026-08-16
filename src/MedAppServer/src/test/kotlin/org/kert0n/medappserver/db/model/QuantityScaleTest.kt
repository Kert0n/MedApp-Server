package org.kert0n.medappserver.db.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/** Проверяет контракт `NUMERIC(19,6)`, реализованный сеттерами сущностей. */
class QuantityScaleTest {

    private fun drug(quantity: BigDecimal) = Drug(
        name = "Тест", quantity = quantity, quantityUnit = "таб",
        formType = null, category = null, manufacturer = null,
        country = null, description = null, medKit = MedKit()
    )

    @Test
    fun `конструктор приводит количество к масштабу базы`() {
        assertEquals(QUANTITY_SCALE, drug(BigDecimal("2.5")).quantity.scale())
    }

    @Test
    fun `лишние знаки отбрасываются, а не округляются вверх`() {
        val drug = drug(BigDecimal("2.5"))

        drug.quantity = BigDecimal("1.23456789")

        assertEquals(QUANTITY_SCALE, drug.quantity.scale())
        assertEquals(
            BigDecimal("1.234567"), drug.quantity,
            "округление вверх приписало бы препарату количество, которого нет"
        )
    }

    @Test
    fun `умножение не раздувает масштаб плана`() {
        val plan = TreatmentPlan(
            planKey = TreatmentPlanKey(), user = User(hashedKey = "k"),
            drug = drug(BigDecimal("10")), plannedAmount = BigDecimal("7")
        )

        plan.plannedAmount = plan.plannedAmount * BigDecimal("0.3333333333")

        assertEquals(QUANTITY_SCALE, plan.plannedAmount.scale())
        assertEquals(BigDecimal("2.333333"), plan.plannedAmount)
    }

    @Test
    fun `нулевой литерал становится нулём в масштабе базы`() {
        val drug = drug(BigDecimal("2.5"))

        drug.quantity = BigDecimal.ZERO

        assertEquals(BigDecimal("0.000000"), drug.quantity)
        assertEquals(true, drug.quantity.isZero())
    }
}
