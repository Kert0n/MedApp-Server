package org.kert0n.medappserver.controller

import org.kert0n.medappserver.domain.error.ConcurrentAggregateChange
import org.kert0n.medappserver.domain.error.DomainException
import org.kert0n.medappserver.domain.error.DrugNotFound
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.IntakeConflict
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
import org.kert0n.medappserver.domain.error.TreatmentPlanNotFound
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/**
 * Single place where failures become responses.
 *
 * Without it the default error body echoed the exception message, and those messages
 * carry drug identifiers and quantities ("Available: 3.0, Requested: 5.0"). That is
 * exactly the kind of detail this server is built not to hand out, and it was switched on
 * in production. The status code stays informative; the body does not.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun handleDomain(exception: DomainException): ProblemDetail = problem(
        when (exception) {
            is DrugNotFound, is MedKitNotFound, is TreatmentPlanNotFound -> HttpStatus.NOT_FOUND
            is TreatmentPlanAlreadyExists, is IntakeConflict, is ConcurrentAggregateChange -> HttpStatus.CONFLICT
            is InvalidQuantity, is InsufficientStock, is PlannedAmountExceedsStock -> HttpStatus.BAD_REQUEST
        }
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(exception: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(exception.statusCode.value()))

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

    private fun problem(status: HttpStatus): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detailFor(status)).apply {
            title = status.reasonPhrase
        }

    /**
     * Deliberately coarse: the caller learns what class of thing went wrong, never which
     * record or how much of it there is.
     */
    private fun detailFor(status: HttpStatus): String = when (status) {
        HttpStatus.BAD_REQUEST -> "Request cannot be processed"
        HttpStatus.UNAUTHORIZED -> "Authentication is required"
        HttpStatus.FORBIDDEN -> "Request is not allowed"
        HttpStatus.NOT_FOUND -> "Requested resource does not exist"
        HttpStatus.CONFLICT -> "Request conflicts with the current state"
        HttpStatus.TOO_MANY_REQUESTS -> "Too many requests"
        HttpStatus.GATEWAY_TIMEOUT -> "Too many registration requests"
        else -> status.reasonPhrase
    }
}
