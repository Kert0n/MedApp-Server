package org.kert0n.medappserver.controller

import org.kert0n.medappserver.domain.error.ConcurrentAggregateChange
import org.kert0n.medappserver.domain.error.DomainException
import org.kert0n.medappserver.domain.error.DrugNotFound
import org.kert0n.medappserver.domain.error.DrugTemplateNotFound
import org.kert0n.medappserver.domain.error.InsufficientStock
import org.kert0n.medappserver.domain.error.IntakeConflict
import org.kert0n.medappserver.domain.error.InvitationNotFound
import org.kert0n.medappserver.domain.error.InvalidMedKitTarget
import org.kert0n.medappserver.domain.error.InvalidQuantity
import org.kert0n.medappserver.domain.error.MedKitNotFound
import org.kert0n.medappserver.domain.error.PlannedAmountExceedsStock
import org.kert0n.medappserver.domain.error.TreatmentPlanAlreadyExists
import org.kert0n.medappserver.domain.error.TreatmentPlanNotFound
import org.kert0n.medappserver.domain.error.UserNotFound
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

/** Maps typed failures to stable responses without exposing identifiers or quantities. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(DomainException::class)
    fun handleDomain(exception: DomainException): ProblemDetail = problem(
        when (exception) {
            is DrugNotFound, is DrugTemplateNotFound, is MedKitNotFound, is TreatmentPlanNotFound,
            is UserNotFound, is InvitationNotFound -> HttpStatus.NOT_FOUND
            is TreatmentPlanAlreadyExists, is IntakeConflict, is ConcurrentAggregateChange -> HttpStatus.CONFLICT
            is InvalidQuantity, is InsufficientStock, is PlannedAmountExceedsStock,
            is InvalidMedKitTarget -> HttpStatus.BAD_REQUEST
        }
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(exception: ResponseStatusException): ProblemDetail =
        problem(HttpStatus.valueOf(exception.statusCode.value()))

    /** Returns field and constraint names, but never rejected values. */
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

    /** Details identify only the error class. */
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
