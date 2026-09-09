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
 * Имя ключа читается из текста ошибки целиком, а не ищется в нём подстрокой.
 *
 * Отдельным полем его отдаёт `PSQLException`, но драйвер подключён `runtimeOnly` — на
 * компиляции его типов нет, и тащить их туда ради одного поля значило бы завести зависимость
 * слоя хранения от конкретного драйвера. Поэтому разбирается текст.
 *
 * Разбирается именно так, а не поиском известного имени в сообщении: имена ключей вкладываются
 * друг в друга. `med_kits_pkey` — подстрока `user_med_kits_pkey`, и поиск подстрокой перевёл бы
 * повторное вступление в аптечку в «аптечка уже существует», причём результат зависел бы от
 * порядка объявления в [REFUSALS]. Postgres всегда закавычивает имя, и кавычки дают точную
 * границу.
 */
private fun ExposedSQLException.constraintName(): String? {
    val text = generateSequence(this as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" ")
    return CONSTRAINT_NAME.find(text)?.groupValues?.get(1)
}

private val CONSTRAINT_NAME = Regex("""constraint "([^"]+)"""")
