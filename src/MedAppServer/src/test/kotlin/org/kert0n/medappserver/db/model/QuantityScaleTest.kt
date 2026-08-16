package org.kert0n.medappserver.db.model

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.quantity.QUANTITY_SCALE as DOMAIN_QUANTITY_SCALE
import java.math.BigDecimal
import kotlin.test.assertEquals

/** Verifies the numeric(19,6) contract implemented by persistence entities. */
class QuantityScaleTest {

    private fun drug(quantity: BigDecimal) = Drug(
        name = "Тест", quantity = quantity, quantityUnit = "таб",
        formType = null, category = null, manufacturer = null,
        country = null, description = null, medKit = MedKit()
    )

    @Test
    fun `конструктор приводит количество к scale базы`() {
        assertEquals(DOMAIN_QUANTITY_SCALE, drug(BigDecimal("2.5")).quantity.scale())
    }

    @Test
    fun `присваивание приводит количество к scale базы`() {
        val drug = drug(BigDecimal("2.5"))

        drug.quantity = BigDecimal("1.23456789")

        assertEquals(DOMAIN_QUANTITY_SCALE, drug.quantity.scale())
        assertEquals(BigDecimal("1.234568"), drug.quantity, "лишние знаки округляются HALF_UP")
    }

    @Test
    fun `умножение не раздувает scale плана`() {
        val using = TreatmentPlan(
            key = TreatmentPlanKey(), user = User(hashedKey = "k"),
            drug = drug(BigDecimal("10")), plannedAmount = BigDecimal("7")
        )

        using.plannedAmount = using.plannedAmount * BigDecimal("0.3333333333")

        assertEquals(DOMAIN_QUANTITY_SCALE, using.plannedAmount.scale())
    }

    @Test
    fun `нулевой литерал становится нулём со scale базы`() {
        val drug = drug(BigDecimal("2.5"))

        drug.quantity = BigDecimal.ZERO

        assertEquals(BigDecimal("0.000000"), drug.quantity)
    }
}
