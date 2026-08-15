package org.kert0n.medappserver.application.model

import java.util.UUID

data class DrugTemplateView(
    val id: UUID,
    val name: String,
    val formType: String?,
    val category: String?,
    val quantityUnit: String?,
    val manufacturer: String?,
    val country: String?,
    val description: String?
)
