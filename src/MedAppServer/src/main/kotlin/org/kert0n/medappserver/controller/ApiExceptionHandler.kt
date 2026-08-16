package org.kert0n.medappserver.controller

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

    private fun problem(status: HttpStatus): ProblemDetail = ProblemDetails.forStatus(status)
}
