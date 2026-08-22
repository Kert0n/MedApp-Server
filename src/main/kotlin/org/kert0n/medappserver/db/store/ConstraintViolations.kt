package org.kert0n.medappserver.db.store

import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.ReservationAlreadyExists

/**
 * Нарушение именованного ключа переводится в доменный отказ.
 *
 * Правила «одна бронь на пару» и «дважды не вступают» выражены в коде и проверяются чтением —
 * ключи их только страхуют. Но на настоящей гонке чтение обеих сторон проходит, и правило
 * держит уже ключ: наружу летит нарушение ограничения, то есть пятисотка на осмысленный
 * запрос.
 *
 * Перевод стоит здесь, а не в обработчике ошибок: имя ключа — знание слоя хранения, и выше
 * ему делать нечего.
 */
private val REFUSALS: Map<String, () -> DomainRuleViolated> = mapOf(
    "reservations_pkey" to ::ReservationAlreadyExists,
    "user_med_kits_pkey" to ::AlreadyMember
)

/**
 * Выполняет запись, переводя известные нарушения ключей.
 *
 * Неизвестное нарушение пробрасывается как есть: подменять его доменным отказом значило бы
 * выдать поломку схемы за нормальный отказ.
 */
fun <T> translatingConstraints(write: () -> T): T =
    try {
        write()
    } catch (e: ExposedSQLException) {
        throw REFUSALS[e.constraintName()]?.invoke() ?: e
    }

/**
 * Имя ключа ищется в тексте ошибки, а не в поле драйвера.
 *
 * Отдельным полем его отдаёт `PSQLException`, но драйвер подключён `runtimeOnly` — на
 * компиляции его типов нет, и тащить их туда ради одного поля значило бы завести зависимость
 * слоя хранения от конкретного драйвера. Ищем по известным именам: совпадение по имени ключа
 * надёжнее разбора формата сообщения, который у разных версий свой.
 */
private fun ExposedSQLException.constraintName(): String? {
    val text = generateSequence(this as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return REFUSALS.keys.firstOrNull { text.contains(it) }
}
