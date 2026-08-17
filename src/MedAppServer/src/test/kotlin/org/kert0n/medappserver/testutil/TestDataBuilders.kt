package org.kert0n.medappserver.testutil

import java.util.*
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.domain.Drug

/** Сборщики тестовых данных. Строятся доменные значения — сущностей тест не видит. */

class DrugBuilder(private val medKitId: UUID) {
    private var id: UUID = UUID.randomUUID()
    private var name: String = "Test Drug"
    private var quantity: Double = 100.0
    private var quantityUnit: String = "mg"
    private var formType: String? = "tablet"
    private var category: String? = "painkiller"
    private var manufacturer: String? = "Test Pharma"
    private var country: String? = "TestLand"
    private var description: String? = "Test description"

    fun withId(id: UUID) = apply { this.id = id }
    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }
    fun withQuantityUnit(unit: String) = apply { this.quantityUnit = unit }
    fun withFormType(formType: String?) = apply { this.formType = formType }
    fun withCategory(category: String?) = apply { this.category = category }
    fun withManufacturer(manufacturer: String?) = apply { this.manufacturer = manufacturer }
    fun withCountry(country: String?) = apply { this.country = country }
    fun withDescription(description: String?) = apply { this.description = description }

    fun build(): Drug = Drug(
        id = id,
        medKitId = medKitId,
        name = name,
        quantity = qty(quantity),
        quantityUnit = quantityUnit,
        formType = formType,
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}

class DrugCreateRequestBuilder {
    private var name: String = "Test Drug"
    private var quantity: Double = 100.0
    private var quantityUnit: String = "mg"
    private var formType: String? = "tablet"
    private var category: String? = "painkiller"
    private var manufacturer: String? = "Test Pharma"
    private var country: String? = "TestLand"
    private var description: String? = "Test description"

    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }
    fun withQuantityUnit(unit: String) = apply { this.quantityUnit = unit }

    fun build(): DrugCreateRequest = DrugCreateRequest(
        name = name,
        quantity = qty(quantity),
        quantityUnit = quantityUnit,
        formType = formType,
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}

class DrugPatchRequestBuilder {
    private var name: String? = null
    private var quantity: Double? = null
    private var quantityUnit: String? = null

    fun withName(name: String?) = apply { this.name = name }
    fun withQuantity(quantity: Double?) = apply { this.quantity = quantity }
    fun withQuantityUnit(unit: String?) = apply { this.quantityUnit = unit }

    fun build(): DrugPatchRequest = DrugPatchRequest(
        name = name,
        quantity = quantity?.let { qty(it) },
        quantityUnit = quantityUnit
    )
}

fun drugBuilder(medKitId: UUID) = DrugBuilder(medKitId)
fun drugCreateDTOBuilder() = DrugCreateRequestBuilder()
fun drugUpdateDTOBuilder() = DrugPatchRequestBuilder()
