package org.kert0n.medappserver.db.model

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * Нормализация scale происходит в сущности, а не в вызывающем коде.
 *
 * Это и есть замена россыпи `.toQuantityScale()` по сервисам: бизнес-логика пишет
 * `drug.quantity = drug.quantity - consumed`, а привести результат к виду колонки
 * `numeric(19,6)` — задача сеттера. Тест держит инвариант: если сеттер уберут, значения
 * начнут расходиться со прочитанными из базы по scale, и сравнения через `equals`
 * (в том числе внутри `assertEquals` и коллекций) станут врать.
 *
 * Spring здесь не нужен: проверяется свойство самих классов.
 */
class QuantityScaleTest {

    private fun drug(quantity: BigDecimal) = Drug(
        name = "Тест", quantity = quantity, quantityUnit = "таб",
        formType = null, category = null, manufacturer = null,
        country = null, description = null, medKit = MedKit()
    )

    @Test
    fun `конструктор приводит количество к scale базы`() {
        assertEquals(QUANTITY_SCALE, drug(BigDecimal("2.5")).quantity.scale())
    }

    @Test
    fun `присваивание приводит количество к scale базы`() {
        val drug = drug(BigDecimal("2.5"))

        // Восемь знаков — больше, чем в колонке: без сеттера значение осталось бы со scale 8.
        drug.quantity = BigDecimal("1.23456789")

        assertEquals(QUANTITY_SCALE, drug.quantity.scale())
        assertEquals(BigDecimal("1.234568"), drug.quantity, "лишние знаки округляются HALF_UP")
    }

    @Test
    fun `умножение не раздувает scale плана`() {
        // Умножение складывает scale операндов: 6 + 6 = 12. Ровно так планы и сжимаются
        // в QuantityReductionService, где коэффициент считается с запасом знаков.
        val using = TreatmentPlan(
            key = TreatmentPlanKey(), user = User(hashedKey = "k"),
            drug = drug(BigDecimal("10")), plannedAmount = BigDecimal("7")
        )

        using.plannedAmount = using.plannedAmount * BigDecimal("0.3333333333")

        assertEquals(QUANTITY_SCALE, using.plannedAmount.scale())
    }

    @Test
    fun `нулевой литерал становится нулём со scale базы`() {
        // BigDecimal.ZERO имеет scale 0, и без нормализации `quantity == BigDecimal.ZERO`
        // давало бы true, а прочитанное из базы `0.000000` — false. Такая асимметрия и есть
        // та ловушка, из-за которой в проекте есть isZero().
        val drug = drug(BigDecimal("2.5"))

        drug.quantity = BigDecimal.ZERO

        assertEquals(BigDecimal("0.000000"), drug.quantity)
    }

    @Test
    fun `сумма планов из формулы приводится к scale базы`() {
        // Формула возвращает COALESCE(SUM(...), 0): без планов это целочисленный ноль со
        // scale 0. Значения, которые проставляет код, сеттер выравнивает.
        val drug = drug(BigDecimal("2.5"))

        drug.totalPlannedAmount = BigDecimal("1.5")

        assertEquals(QUANTITY_SCALE, drug.totalPlannedAmount.scale())
    }
}
