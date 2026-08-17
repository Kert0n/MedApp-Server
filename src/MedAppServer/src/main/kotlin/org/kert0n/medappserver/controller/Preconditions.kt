package org.kert0n.medappserver.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.server.ResponseStatusException

/**
 * `ETag` и `If-Match` — разбор в одном месте.
 *
 * Версия агрегата и есть сущностный тег: она меняется ровно тогда, когда меняется
 * представление, и не меняется больше ни от чего. Поэтому тег сильный — слабый обещал бы
 * «семантически эквивалентно», а этого мы про две версии сказать не можем.
 *
 * Здесь живёт HTTP-часть предусловия: есть ли заголовок и разбирается ли он. Совпадает ли
 * версия — решает агрегат, потому что это уже про состояние, а не про запрос.
 */
object Preconditions {

    /** Сильный тег из версии: `ETag: "3"`. */
    fun etag(version: Long): String = "\"$version\""

    /**
     * Версия, предъявленная клиентом, или отказ.
     *
     * Коды разведены намеренно: 428 означает «предусловие обязательно, добавьте заголовок»,
     * 400 — «заголовок есть, но прочитать его нельзя». Клиенту это разные починки, и
     * сваливать их в один код значило бы заставить его гадать.
     *
     * `*` тоже отвергается, хотя RFC 9110 считает его допустимым: он утверждает лишь «ресурс
     * существует», а предусловия здесь заведены против потерянного обновления, от которого
     * `*` не защищает вовсе. Принять его значило бы дать клиенту способ обойти проверку,
     * ничего не потеряв в глазах спецификации.
     */
    fun requiredVersion(ifMatch: String?): Long {
        val header = ifMatch?.trim()
        if (header.isNullOrEmpty()) {
            throw ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "If-Match is required")
        }
        return TAG.matchEntire(header)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "If-Match is not a strong entity tag")
    }

    /** Ответ команды или чтения одиночного ресурса — вместе с текущей версией. */
    fun <T : Any> withEtag(version: Long, body: T): ResponseEntity<T> =
        ResponseEntity.ok().eTag(etag(version)).body(body)

    /**
     * Один сильный тег из цифр, и ничего кроме.
     *
     * Список тегов через запятую сюда не подходит по смыслу: команда меняет одно состояние, и
     * «любая из этих версий» означало бы, что клиент сам не знает, что перезаписывает.
     */
    private val TAG = Regex("\"(\\d{1,19})\"")
}
