package org.kert0n.medappserver.domain

/**
 * Отказы домена.
 *
 * Про HTTP модель не знает: она сообщает нарушенное правило, а код ответа выбирает
 * `ApiExceptionHandler`. Сообщения — для логов и стектрейса, в ответ они не попадают.
 */
sealed class DomainRuleViolated(message: String) : RuntimeException(message)

/** Количество должно быть строго положительным. */
class InvalidQuantity : DomainRuleViolated("Quantity must be greater than zero")

/** Съесть больше, чем в пачке, нельзя: столько таблеток в ней физически не было. */
class InsufficientStock : DomainRuleViolated("Amount exceeds the stock")

/** У человека уже есть бронь на эту упаковку: её надо менять, а не заводить вторую. */
class ReservationAlreadyExists : DomainRuleViolated("Reservation already exists for this user and drug")

/** Брони нет. */
class NoSuchReservation : DomainRuleViolated("There is no such reservation")

/** Пользователь не участник этой аптечки — или самой аптечки нет. */
class NotAMember : DomainRuleViolated("Medicine kit is not accessible")

/** Пользователь уже участник: вступать второй раз нечего. */
class AlreadyMember : DomainRuleViolated("User is already a member of this medicine kit")

/** Секрет регистрации не совпал: заводить пользователя не по чему. */
class InvalidRegistrationSecret : DomainRuleViolated("Registration secret does not match")

/** С этого адреса уже зарегистрировались столько раз, сколько разрешено. */
class TooManyRegistrations : DomainRuleViolated("Too many registrations from this address")

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
