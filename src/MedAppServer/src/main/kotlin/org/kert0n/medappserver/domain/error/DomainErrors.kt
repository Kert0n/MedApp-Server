package org.kert0n.medappserver.domain.error

import java.util.UUID

sealed class DomainException(message: String) : RuntimeException(message)

class DrugNotFound(drugId: UUID) : DomainException("Drug $drugId was not found")

class MedKitNotFound(medKitId: UUID) : DomainException("MedKit $medKitId was not found")

class TreatmentPlanNotFound(userId: UUID, drugId: UUID) :
    DomainException("Treatment plan for user $userId and drug $drugId was not found")

class InvalidQuantity(message: String = "Quantity must be positive") : DomainException(message)

class InsufficientStock : DomainException("Insufficient stock")

class TreatmentPlanAlreadyExists(userId: UUID, drugId: UUID) :
    DomainException("Treatment plan for user $userId and drug $drugId already exists")

class PlannedAmountExceedsStock : DomainException("Planned amount exceeds stock")

class IntakeConflict : DomainException("Intake id was already used with another payload")
