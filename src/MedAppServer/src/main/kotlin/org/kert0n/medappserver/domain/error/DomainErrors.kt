package org.kert0n.medappserver.domain.error

/**
 * Отказы агрегата.
 *
 * Модель не знает про HTTP: она сообщает, какое правило нарушено, а перевод в код ответа
 * делает `ApiExceptionHandler`. Иначе доменное правило нельзя было бы проверить без
 * поднятого веб-слоя, а сообщение об ошибке — увести наружу вместе с количествами.
 *
 * Сообщения существуют для логов и для читателя стектрейса; в ответ они не попадают.
 */
sealed class DomainRuleViolated(message: String) : RuntimeException(message)

/** Количество должно быть строго положительным. */
class InvalidQuantity : DomainRuleViolated("Quantity must be greater than zero")

/** Списать больше, чем есть, нельзя. */
class InsufficientStock : DomainRuleViolated("Amount exceeds the stock")

/** Запас можно только пополнить; расход выражается списанием. */
class QuantityNotIncreased : DomainRuleViolated("New quantity must be greater than the current one")

/** Сумма планов не может превысить остаток. */
class PlannedAmountExceedsStock : DomainRuleViolated("Planned amount exceeds the available stock")

/** Приём не может быть больше собственного плана. */
class IntakeExceedsPlan : DomainRuleViolated("Intake exceeds the planned amount")

/** У пользователя уже есть план на этот препарат: его надо менять, а не заводить второй. */
class TreatmentPlanAlreadyExists : DomainRuleViolated("Treatment plan already exists for this user and drug")

/** Плана нет. */
class NoSuchTreatmentPlan : DomainRuleViolated("There is no such treatment plan")
