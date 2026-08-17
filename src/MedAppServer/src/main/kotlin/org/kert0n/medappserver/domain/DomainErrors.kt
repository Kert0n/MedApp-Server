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

/**
 * Клиент решал по устаревшему состоянию: с тех пор агрегат изменился.
 *
 * Повтора нет — сервер не знает, останется ли команда осмысленной по новому состоянию, и
 * решить это может только тот, кто её отправил.
 *
 * Наружу это 412: предусловие предъявлено и не выполнено. Тот же отказ, пришедший из **тела**
 * запроса, а не из заголовка, переводится в [StaleSyncVersion] — см. синхронизацию.
 */
class StaleAggregateVersion : DomainRuleViolated("Aggregate has changed since the version supplied")

/**
 * То же устаревшее состояние, но версия приехала телом, а не заголовком.
 *
 * Различие не косметическое: 412 отвечает на невыполненное **предусловие запроса**, а у
 * синхронизации предусловий два и ни одно не в `If-Match`. Отказ по ним — обычный конфликт
 * состояния, то есть 409.
 */
class StaleSyncVersion : DomainRuleViolated("A version in the synchronisation body is stale")

/** Запрос, не просящий сделать ничего, командой не является. */
class EmptySync : DomainRuleViolated("Synchronisation must carry an intake, a reservation, or both")

/** Тот же идентификатор синхронизации с другим содержимым: одно из двух не то, что было. */
class ConflictingSync : DomainRuleViolated("Synchronisation id was already used for a different request")

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
