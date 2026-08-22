package org.kert0n.medappserver.controller

import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.InvalidRegistrationSecret
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.orchestrator.ConflictingSync
import org.kert0n.medappserver.services.orchestrator.StaleSyncVersion
import org.kert0n.medappserver.api.PreconditionRequired
import org.kert0n.medappserver.domain.TooManyRegistrations
import org.springframework.http.HttpStatus
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
            // Предъявленная версия не совпала с той, что в базе. Различать «прислал
            // устаревшую» и «проиграл гонку» база не даёт — она отвечает одинаково, нулём
            // задетых строк, — да и клиенту разницы нет: и там и там решение принято по
            // картине, которой больше нет. Повторять за него сервер не берётся.
            is StaleVersion -> HttpStatus.PRECONDITION_FAILED
            // Секрет регистрации не совпал: отвечаем как на запрет, а не как на ошибку формы.
            is InvalidRegistrationSecret -> HttpStatus.FORBIDDEN
            is TooManyRegistrations -> HttpStatus.TOO_MANY_REQUESTS
            else -> HttpStatus.BAD_REQUEST
        }
    )

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

    /**
     * Предусловие: не предъявлено — 428, предъявлено и не совпало — 412.
     *
     * 409 остаётся тому, что предусловием запроса не было: дублю брони, повторному вступлению
     * и версиям из тела синхронизации.
     */
    /** Тот же идентификатор с другим содержимым — конфликт, а не повтор. */
    @ExceptionHandler(ConflictingSync::class)
    fun handleConflictingSync(exception: ConflictingSync): ProblemDetail = problem(HttpStatus.CONFLICT)

    /** Версия из тела синхронизации: конфликт записи, а не предусловие запроса. */
    @ExceptionHandler(StaleSyncVersion::class)
    fun handleStaleSyncVersion(exception: StaleSyncVersion): ProblemDetail = problem(HttpStatus.CONFLICT)

    @ExceptionHandler(PreconditionRequired::class)
    fun handlePreconditionRequired(exception: PreconditionRequired): ProblemDetail =
        problem(HttpStatus.PRECONDITION_REQUIRED)

    private fun problem(status: HttpStatus): ProblemDetail = ProblemDetails.forStatus(status)
}
