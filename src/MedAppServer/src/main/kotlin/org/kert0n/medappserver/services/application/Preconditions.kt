package org.kert0n.medappserver.services.application

/**
 * Предусловие команды: клиент предъявляет версию того, что видел.
 *
 * Проверка живёт в прикладном слое, а не в домене: домен отвечает за правила предметной
 * области, а «на что ты смотрел, когда решал» — вопрос протокола.
 *
 * Отказов два, и они про разное. **Не предъявлено** — команда пришла без версии вообще:
 * пропустить её значило бы разрешить писать вслепую. **Не совпало** — версия предъявлена, но
 * состояние уже другое, и решение принималось по устаревшей картине.
 *
 * Третий случай — предъявленное совпало на проверке, но состояние уехало, пока команда шла, —
 * ловит уже предикат в `UPDATE` и отвечает 409. Разница честная: здесь видно до попытки, там —
 * по числу задетых строк.
 */
class PreconditionRequired : RuntimeException("The command must state the version it acts on")

class PreconditionFailed : RuntimeException("The stated version is not the current one")

/** Версия, которую команда обязана была предъявить. */
fun statedVersion(version: Long?): Long = version ?: throw PreconditionRequired()

/** Сверка предъявленного с тем, что на самом деле прочитано. */
fun requireStated(version: Long?, actual: Long) {
    if (statedVersion(version) != actual) throw PreconditionFailed()
}
