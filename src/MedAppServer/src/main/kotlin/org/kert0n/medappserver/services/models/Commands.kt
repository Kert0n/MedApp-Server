package org.kert0n.medappserver.services.models

import java.math.BigDecimal
import java.util.UUID


data class DrugCreation(
    val name: String,
    val quantity: BigDecimal,
    val quantityUnit: String,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)


data class DrugPatch(
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnit: String? = null,
    val formType: String? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)


data class PlanSnapshot(
    val userId: UUID,
    val drugId: UUID,
    val plannedAmount: BigDecimal
)


data class NewCredentials(val login: UUID, val key: String)
