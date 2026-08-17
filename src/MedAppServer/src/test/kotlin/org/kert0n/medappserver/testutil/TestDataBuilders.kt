package org.kert0n.medappserver.testutil

import java.util.*
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Quantity
import org.kert0n.medappserver.domain.QuantityUnit

/** Сборщики тестовых данных. Строятся доменные значения — сущностей тест не видит. */

class DrugBuilder(private val medKitId: UUID, private val unit: QuantityUnit) {
    private var id: UUID = UUID.randomUUID()
    private var name: String = "Test Drug"
    private var quantity: Double = 100.0
    private var category: String? = "painkiller"
    private var manufacturer: String? = "Test Pharma"
    private var country: String? = "TestLand"
    private var description: String? = "Test description"

    fun withId(id: UUID) = apply { this.id = id }
    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }
    fun withCategory(category: String?) = apply { this.category = category }
    fun withManufacturer(manufacturer: String?) = apply { this.manufacturer = manufacturer }
    fun withCountry(country: String?) = apply { this.country = country }
    fun withDescription(description: String?) = apply { this.description = description }

    fun build(): Drug = Drug(
        id = id,
        medKitId = medKitId,
        name = name,
        quantity = Quantity(qty(quantity), unit),
        category = category,
        manufacturer = manufacturer,
        country = country,
        description = description
    )
}

class DrugCreateRequestBuilder(private val quantityUnitId: UUID) {
    private var name: String = "Test Drug"
    private var quantity: Double = 100.0

    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }

    fun build(): DrugCreateRequest = DrugCreateRequest(
        name = name,
        quantity = qty(quantity),
        quantityUnitId = quantityUnitId,
        category = "painkiller",
        manufacturer = "Test Pharma",
        country = "TestLand",
        description = "Test description"
    )
}

class DrugPatchRequestBuilder {
    private var name: String? = null
    private var quantity: Double? = null

    fun withName(name: String?) = apply { this.name = name }
    fun withQuantity(quantity: Double?) = apply { this.quantity = quantity }

    fun build(): DrugPatchRequest = DrugPatchRequest(
        name = name,
        quantity = quantity?.let { qty(it) }
    )
}

fun drugBuilder(medKitId: UUID, unit: QuantityUnit) = DrugBuilder(medKitId, unit)
fun drugCreateDTOBuilder(quantityUnitId: UUID) = DrugCreateRequestBuilder(quantityUnitId)
fun drugUpdateDTOBuilder() = DrugPatchRequestBuilder()
