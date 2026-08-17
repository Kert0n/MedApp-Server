package org.kert0n.medappserver.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Ресурс, состояние которого клиент может предъявить обратно.
 *
 * Версия непрозрачна: клиент её не толкует, не сравнивает на «больше» и не считает команды по
 * ней — он только возвращает ту, по которой принимал решение.
 */
interface Versioned {
    val version: Long
}

/**
 * Ответ с сильным тегом: по нему клиент и предъявит состояние в следующей команде.
 *
 * Тег ставится и на ответ команды, а не только чтения: версия после записи известна лишь по
 * факту коммита, и без неё клиенту пришлось бы перечитывать ресурс, чтобы сделать следующий шаг.
 */
fun <T : Versioned> T.withEtag(): ResponseEntity<T> =
    ResponseEntity.ok().eTag(etagOf(version)).body(this)

/** То же для только что созданного ресурса. */
fun <T : Versioned> T.createdWithEtag(): ResponseEntity<T> =
    ResponseEntity.status(HttpStatus.CREATED).eTag(etagOf(version)).body(this)

/** Ресурса после команды не осталось — тегу неоткуда взяться. */
fun <T : Any> noContentWithoutEtag(): ResponseEntity<T> = ResponseEntity.noContent().build()

private fun etagOf(version: Long): String = "\"$version\""

/**
 * Разбор `If-Match`.
 *
 * Предусловие требуется от каждой команды, которая перезаписывает существующее состояние.
 * Отсутствие заголовка — не «клиенту всё равно», а «клиент не сказал, по чему он решал»: такую
 * команду выполнять нельзя, иначе одновременная правка молча потеряется. Поэтому 428, а не
 * тихое выполнение.
 *
 * `*` отвергается: он значит «лишь бы ресурс существовал» и от потерянного обновления не
 * защищает вовсе. Разрешить его — значит дать клиенту способ обойти предусловие, не заметив
 * этого.
 *
 * Слабые теги (`W/"7"`) тоже отвергаются: слабое сравнение допускает «семантически то же
 * состояние», а здесь совпадение обязано быть точным.
 */
@Component
class Preconditions {

    fun requiredMatch(ifMatch: String?): Long {
        // Заголовка нет вовсе — 428; заголовок есть, но им ничего не сказано — 400. Пустое
        // значение это неверно заполненный заголовок, а не его отсутствие.
        if (ifMatch == null) {
            throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required")
        }
        return parse(ifMatch)
    }

    private fun parse(ifMatch: String): Long {
        val value = ifMatch.trim()
        if (value.isEmpty() || value == "*" || value.startsWith("W/")) throw unparseable()
        if (!value.startsWith('"') || !value.endsWith('"') || value.length < 3) throw unparseable()
        val version = value.substring(1, value.length - 1).toLongOrNull() ?: throw unparseable()
        // Отрицательной версии не бывает ни у одной строки: такой тег сервер не выдавал.
        if (version < 0) throw unparseable()
        return version
    }

    private fun unparseable() =
        ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match must be a strong entity tag")

    companion object {
        const val IF_MATCH: String = HttpHeaders.IF_MATCH
    }
}
