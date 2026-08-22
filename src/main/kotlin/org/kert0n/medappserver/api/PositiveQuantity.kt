package org.kert0n.medappserver.api

import jakarta.validation.Constraint
import jakarta.validation.Payload
import jakarta.validation.ReportAsSingleViolation
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import kotlin.reflect.KClass
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Величина, которую можно попросить: строго больше нуля и не шире колонки.
 *
 * Одно условие — одна метка: `@DecimalMin`, `@Digits` и `@Schema(pattern = …)` описывали бы одно
 * правило в трёх местах, и разойтись им ничто не мешает.
 *
 * Образец в схему ставится по этой же метке — настройкой `positiveQuantities`, а не `@Schema`
 * поверх самой аннотации: мета-аннотацию springdoc не читает, и без настройки поле публикуется
 * числом и без образца.
 *
 * Разрядность берётся у колонки: `numeric(19, 6)` — это 13 цифр до точки и 6 после.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
@Constraint(validatedBy = [])
@DecimalMin(value = "0.0", inclusive = false)
@Digits(integer = QUANTITY_PRECISION - QUANTITY_SCALE, fraction = QUANTITY_SCALE)
@ReportAsSingleViolation
annotation class PositiveQuantity(
    val message: String = "must be greater than zero and no wider than the column",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)
