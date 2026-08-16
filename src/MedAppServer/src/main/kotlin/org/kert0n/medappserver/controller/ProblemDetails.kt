package org.kert0n.medappserver.controller

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Тексты ошибок в одном месте.
 *
 * Формулировки намеренно грубые: вызывающий узнаёт класс проблемы, но никогда — какая
 * запись и сколько её. Раньше тело ошибки повторяло сообщение исключения, а те несли
 * идентификаторы препаратов и остатки.
 */
object ProblemDetails {

    fun forStatus(status: HttpStatus): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detailFor(status)).apply {
            title = status.reasonPhrase
        }

    private fun detailFor(status: HttpStatus): String = when (status) {
        HttpStatus.BAD_REQUEST -> "Request cannot be processed"
        HttpStatus.UNAUTHORIZED -> "Authentication is required"
        HttpStatus.FORBIDDEN -> "Request is not allowed"
        HttpStatus.NOT_FOUND -> "Requested resource does not exist"
        HttpStatus.CONFLICT -> "Request conflicts with the current state"
        HttpStatus.TOO_MANY_REQUESTS -> "Too many requests"
        else -> status.reasonPhrase
    }
}

/**
 * Пишет ту же ошибку из фильтров и точки входа безопасности.
 *
 * Без этого ответы делились надвое: контроллеры отдавали problem+json, а отказы уровня
 * безопасности — пустое тело с одним статусом. Клиенту приходилось разбирать два формата,
 * а `Content-Type` у пустого ответа вообще ни о чём не говорил.
 */
@Component
class ProblemResponseWriter(private val objectMapper: ObjectMapper) {

    fun write(response: HttpServletResponse, status: HttpStatus) {
        if (response.isCommitted) return
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        objectMapper.writeValue(response.outputStream, ProblemDetails.forStatus(status))
    }
}
