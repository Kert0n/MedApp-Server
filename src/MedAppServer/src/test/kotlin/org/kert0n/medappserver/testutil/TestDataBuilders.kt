package org.kert0n.medappserver.testutil

import java.util.*
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.db.model.*

/**
 * Test data builders for creating consistent test entities
 */

class UserBuilder {
    private var id: UUID = UUID.randomUUID()
    private var hashedKey: String = "hashed_test_key_123"
    private val medKits: MutableSet<MedKit> = mutableSetOf()
    private val treatmentPlans: MutableSet<TreatmentPlan> = mutableSetOf()

    fun withId(id: UUID) = apply { this.id = id }
    fun withHashedKey(key: String) = apply { this.hashedKey = key }
    fun withMedKit(medKit: MedKit) = apply { this.medKits.add(medKit) }

    fun build(): User {
        val user = User(id = id, hashedKey = hashedKey)
        user.medKits.addAll(medKits)
        user.treatmentPlans.addAll(treatmentPlans)
        return user
    }
}

class MedKitBuilder {
    private var id: UUID = UUID.randomUUID()
    private val users: MutableSet<User> = mutableSetOf()
    private val drugs: MutableSet<Drug> = mutableSetOf()

    fun withId(id: UUID) = apply { this.id = id }
    fun withUser(user: User) = apply { this.users.add(user) }
    fun withDrug(drug: Drug) = apply { this.drugs.add(drug) }

    fun build(): MedKit {
        val medKit = MedKit(id = id)
        medKit.users.addAll(users)
        medKit.drugs.addAll(drugs)
        return medKit
    }
}

class DrugBuilder(private val medKit: MedKit) {
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

    fun build(): Drug {
        return Drug(
            id = id,
            name = name,
            quantity = qty(quantity),
            quantityUnit = quantityUnit,
            formType = formType,
            category = category,
            manufacturer = manufacturer,
            country = country,
            description = description,
            medKit = medKit
        )
    }
}

class TreatmentPlanBuilder(private val user: User, private val drug: Drug) {
    private var plannedAmount: Double = 30.0

    fun withPlannedAmount(amount: Double) = apply { this.plannedAmount = amount }

    fun build(): TreatmentPlan {
        return TreatmentPlan(
            planKey = TreatmentPlanKey(userId = user.id, drugId = drug.id),
            user = user,
            drug = drug,
            plannedAmount = qty(plannedAmount)
        )
    }
}

class DrugCreateRequestBuilder {
    private var name: String = "Test Drug"
    private var quantity: Double = 100.0
    private var quantityUnit: String = "mg"
    private var medKitId: UUID = UUID.randomUUID()
    private var formType: String? = "tablet"
    private var category: String? = "painkiller"
    private var manufacturer: String? = "Test Pharma"
    private var country: String? = "TestLand"
    private var description: String? = "Test description"

    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }
    fun withQuantityUnit(unit: String) = apply { this.quantityUnit = unit }
    fun withMedKitId(id: UUID) = apply { this.medKitId = id }
    fun withFormType(formType: String?) = apply { this.formType = formType }
    fun withCategory(category: String?) = apply { this.category = category }
    fun withManufacturer(manufacturer: String?) = apply { this.manufacturer = manufacturer }
    fun withCountry(country: String?) = apply { this.country = country }
    fun withDescription(description: String?) = apply { this.description = description }

    fun build(): DrugCreateRequest {
        return DrugCreateRequest(
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
}

class DrugPatchRequestBuilder {
    private var name: String? = null
    private var quantity: Double? = null
    private var quantityUnit: String? = null
    private var formType: String? = null
    private var category: String? = null
    private var manufacturer: String? = null
    private var country: String? = null
    private var description: String? = null

    fun withName(name: String) = apply { this.name = name }
    fun withQuantity(quantity: Double) = apply { this.quantity = quantity }
    fun withQuantityUnit(unit: String) = apply { this.quantityUnit = unit }
    fun withFormType(formType: String) = apply { this.formType = formType }
    fun withCategory(category: String) = apply { this.category = category }
    fun withManufacturer(manufacturer: String) = apply { this.manufacturer = manufacturer }
    fun withCountry(country: String) = apply { this.country = country }
    fun withDescription(description: String) = apply { this.description = description }

    fun build(): DrugPatchRequest {
        return DrugPatchRequest(
            name = name,
            quantity = quantity?.let(::qty),
            quantityUnit = quantityUnit,
            formType = formType,
            category = category,
            manufacturer = manufacturer,
            country = country,
            description = description
        )
    }
}

// Helper functions for quick access
fun userBuilder() = UserBuilder()
fun medKitBuilder() = MedKitBuilder()
fun drugBuilder(medKit: MedKit) = DrugBuilder(medKit)
fun treatmentPlanBuilder(user: User, drug: Drug) = TreatmentPlanBuilder(user, drug)
fun drugCreateDTOBuilder() = DrugCreateRequestBuilder()
fun drugUpdateDTOBuilder() = DrugPatchRequestBuilder()
