package org.kert0n.medappserver.controller

import org.kert0n.medappserver.domain.AlreadyMember
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.domain.DrugAlreadyExists
import org.kert0n.medappserver.domain.MedKitAlreadyExists
import org.kert0n.medappserver.domain.InvalidRegistrationSecret
import org.kert0n.medappserver.domain.NoSuchReservation
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.domain.ReservationAlreadyExists
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.orchestrator.ConflictingSync
import org.kert0n.medappserver.api.PreconditionRequired
import org.kert0n.medappserver.domain.TooManyRegistrations
import org.kert0n.medappserver.domain.UserAlreadyExists
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Единственное место, где отказ превращается в ответ.
 *
 * Тело по умолчанию пересказывает сообщение исключения, а в них — идентификаторы пачек и
 * количества, то есть ровно то, чего этот сервер наружу не отдаёт. Код ответа остаётся
 * содержательным, тело — нет.
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
     * Отсутствие брони — 404: ресурса нет. Вторая бронь того же человека на ту же пачку и
     * повторное вступление — 409: ресурс уже существует, его нельзя создать заново. Остальное
     * — 400: запрос сам по себе противоречив.
     */
    @ExceptionHandler(DomainRuleViolated::class)
    fun handleDomainRule(exception: DomainRuleViolated): ProblemDetail = problem(
        when (exception) {
            is NoSuchReservation -> HttpStatus.NOT_FOUND
            // Недоступная аптечка и несуществующая отвечают одинаково: иначе код ответа
            // выдавал бы существование чужой.
            is NotAMember -> HttpStatus.NOT_FOUND
            // Повтор создания с тем же придуманным клиентом идентификатором — в ту же корзину,
            // что дубль брони и повторное вступление: конфликт, предусловием запроса не бывший.
            is ReservationAlreadyExists, is AlreadyMember,
            is MedKitAlreadyExists, is DrugAlreadyExists, is UserAlreadyExists -> HttpStatus.CONFLICT
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
     * Проверка тела запроса.
     *
     * Имена полей и нарушенное ограничение опубликованы в контракте — их вернуть можно.
     * Отвергнутые значения не опубликованы, и они остаются внутри.
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
     * Тот же идентификатор расхода с другим содержимым — конфликт, а не повтор.
     *
     * Отличается от устаревшей версии кодом, а не только текстом: на 412 клиент перечитывает и
     * готовит запрос заново, а здесь повторять бессмысленно — это дефект клиента.
     */
    @ExceptionHandler(ConflictingSync::class)
    fun handleConflictingSync(exception: ConflictingSync): ProblemDetail = problem(HttpStatus.CONFLICT)

    /**
     * Предусловие: не предъявлено — 428, предъявлено и не совпало — 412.
     *
     * Где едет версия — параметром или телом, как у синхронизации, — смысла отказа не меняет.
     * 409 остаётся тому, что предусловием запроса не было: дублю брони, повторному вступлению и
     * занятому клиентскому идентификатору.
     */
    @ExceptionHandler(PreconditionRequired::class)
    fun handlePreconditionRequired(exception: PreconditionRequired): ProblemDetail =
        problem(HttpStatus.PRECONDITION_REQUIRED)

    private fun problem(status: HttpStatus): ProblemDetail = ProblemDetails.forStatus(status)
}
