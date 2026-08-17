package org.kert0n.medappserver.domain

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

/**
 * Списать больше, чем в упаковке, нельзя.
 *
 * Это не защита от клиента, а физика: столько таблеток в пачке не было. Пополнения у упаковки
 * не бывает — новая пачка это новая упаковка, — поэтому перерасход означает, что разошлись не
 * числа, а представления о том, из какой пачки ели.
 */
class InsufficientStock : DomainRuleViolated("Amount exceeds the stock")

/** У человека уже есть бронь на эту упаковку: её надо менять, а не заводить вторую. */
class ReservationAlreadyExists : DomainRuleViolated("Reservation already exists for this user and drug")

/** Брони нет. */
class NoSuchReservation : DomainRuleViolated("There is no such reservation")

/** Пользователь не участник этой аптечки — или самой аптечки нет. */
class NotAMember : DomainRuleViolated("Medicine kit is not accessible")

/** Пользователь уже участник: вступать второй раз нечего. */
class AlreadyMember : DomainRuleViolated("User is already a member of this medicine kit")

/** Пользователь без ключа не существует. */
class InvalidCredentials : DomainRuleViolated("Credentials must not be blank")

/** Аптечки без участников не бывает: последний выходящий забирает её с собой. */
class MedKitWithoutMembers : DomainRuleViolated("Medicine kit must have at least one member")

/** Количество не бывает отрицательным. */
class NegativeQuantity : DomainRuleViolated("Quantity must not be negative")

/** Величины в разных единицах измерения несравнимы и не складываются. */
class QuantityUnitMismatch : DomainRuleViolated("Quantities must share the same unit")

/** Единицы измерения с таким идентификатором в справочнике нет. */
class UnknownQuantityUnit : DomainRuleViolated("Unknown quantity unit")

/** Формы выпуска с таким идентификатором в справочнике нет. */
class UnknownFormType : DomainRuleViolated("Unknown dosage form")
