package org.kert0n.medappserver.controller

import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.ConflictingSync
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.domain.StaleAggregateVersion
import org.springframework.http.HttpStatus
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Single place where failures become responses.
 *
 * The default body echoes the exception message, and those carry package identifiers and
 * quantities — exactly what this server is built not to hand out. The status code stays
 * informative; the body does not.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(exception: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(exception.statusCode.value()))

    /**
     * Нарушенное правило агрегата. Здесь и только здесь оно превращается в код ответа — сама
     * модель про HTTP не знает, иначе её нельзя было бы проверить без веб-слоя.
     *
     * Отсутствие брони — 404: ресурса нет. Вторая бронь того же человека на ту же пачку — 409:
     * ресурс есть, его надо менять. Остальное — 400: запрос сам по себе противоречив.
     */
    @ExceptionHandler(DomainRuleViolated::class)
    fun handleDomainRule(exception: DomainRuleViolated): ProblemDetail = problem(
        when (exception) {
            is NoSuchReservation -> HttpStatus.NOT_FOUND
            // Недоступная аптечка и несуществующая отвечают одинаково: иначе код ответа
            // выдавал бы существование чужой.
            is NotAMember -> HttpStatus.NOT_FOUND
            is ReservationAlreadyExists -> HttpStatus.CONFLICT
            // Клиент решал по устаревшему состоянию: ресурс есть, но не тот, который он видел.
            is StaleAggregateVersion -> HttpStatus.CONFLICT
            // Тот же идентификатор синхронизации с другим содержимым: одно из двух не то.
            is ConflictingSync -> HttpStatus.CONFLICT
            else -> HttpStatus.BAD_REQUEST
        }
    )

    /**
     * Гонка двух команд: обе читали одно состояние, записать успела одна.
     *
     * 409, а не 500: запрос был правильным, просто мир изменился между чтением и записью.
     * Авто-повтора нет — сервер не знает, останется ли команда осмысленной по новому состоянию,
     * и решить это может только тот, кто её отправил.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleLostUpdate(exception: ObjectOptimisticLockingFailureException): ProblemDetail =
        problem(HttpStatus.CONFLICT)

    /**
     * Request body validation. Field names and the constraint that failed are part of the
     * published contract, so they are safe to return — the rejected values are not, and
     * are left out.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleBodyValidation(exception: MethodArgumentNotValidException): ProblemDetail =
        problem(HttpStatus.BAD_REQUEST).apply {
            title = "Invalid request"
            setProperty(
                "errors",
                exception.bindingResult.fieldErrors.map { error ->
                    mapOf(
                        "field" to error.field,
                        "reason" to (error.defaultMessage ?: "invalid value")
                    )
                }
            )
        }

    private fun problem(status: HttpStatus): ProblemDetail = ProblemDetails.forStatus(status)
}
