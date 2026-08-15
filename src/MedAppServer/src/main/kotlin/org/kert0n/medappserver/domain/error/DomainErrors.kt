package org.kert0n.medappserver.domain.error

import java.util.UUID

sealed class DomainException(message: String) : RuntimeException(message)

class DrugNotFound(drugId: UUID) : DomainException("Drug $drugId was not found")

class DrugTemplateNotFound(templateId: UUID) : DomainException("Drug template $templateId was not found")

class MedKitNotFound(medKitId: UUID) : DomainException("MedKit $medKitId was not found")

class UserNotFound(userId: UUID) : DomainException("User $userId was not found")

class InvitationNotFound : DomainException("Invitation was not found or has expired")

class InvalidMedKitTarget : DomainException("Source and target MedKit must differ")

class TreatmentPlanNotFound(userId: UUID, drugId: UUID) :
    DomainException("Treatment plan for user $userId and drug $drugId was not found")

class InvalidQuantity(message: String = "Quantity must be positive") : DomainException(message)

class InsufficientStock : DomainException("Insufficient stock")

class TreatmentPlanAlreadyExists(userId: UUID, drugId: UUID) :
    DomainException("Treatment plan for user $userId and drug $drugId already exists")

class PlannedAmountExceedsStock : DomainException("Planned amount exceeds stock")

class IntakeConflict : DomainException("Intake id was already used with another payload")

class ConcurrentAggregateChange : DomainException("Aggregate changed while locks were being acquired")
