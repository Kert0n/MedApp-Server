package org.kert0n.medappserver.db.repository

import java.math.BigDecimal
import java.util.UUID

/**
 * Формы чтения.
 *
 * Это не DTO представления: они принадлежат хранилищу и описывают, что именно достаётся из
 * базы. DTO собирается из них в слое API — иначе правка публичного контракта меняла бы SQL.
 *
 * Запросы, которые их строят, считают суммы планов в базе и соединяются с планами явным
 * джойном, а не через коллекцию внутри препарата: коллекция заставляла загружать все планы
 * целиком ради одного числа.
 */

/** Препарат вместе с тем, сколько его зарезервировано планами. */
data class DrugView(
    val id: UUID,
    val name: String,
    val quantity: BigDecimal,
    val plannedQuantity: BigDecimal,
    val quantityUnit: String,
    val formType: String?,
    val category: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?,
    val medKitId: UUID
) {
    /** Незарезервированный остаток. Считается здесь, чтобы не разъезжаться по местам вызова. */
    val availableQuantity: BigDecimal get() = quantity - plannedQuantity
}

/** План лечения так, как его видит владелец. */
data class TreatmentPlanView(
    val drugId: UUID,
    val plannedAmount: BigDecimal
)

/**
 * Счётчики аптечки без загрузки её содержимого.
 *
 * Своя форма, а не DTO представления: запрос не должен зависеть от формы публичного ответа.
 */
data class MedKitSummary(
    val id: UUID,
    val userCount: Long,
    val drugCount: Long
)

/** Карточка справочника; названия формы и единицы уже развёрнуты запросом. */
data class DrugTemplateView(
    val id: UUID,
    val name: String,
    val nameLat: String?,
    val activeSubstance: String?,
    val formType: String?,
    val category: String?,
    val quantityUnit: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)
