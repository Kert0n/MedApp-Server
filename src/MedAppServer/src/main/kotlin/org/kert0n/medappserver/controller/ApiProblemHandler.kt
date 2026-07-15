package org.kert0n.medappserver.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.net.URI

@RestControllerAdvice
class ApiProblemHandler {
    @ExceptionHandler(ResponseStatusException::class)
    fun handleStatus(exception: ResponseStatusException): ProblemDetail {
        val status = HttpStatus.valueOf(exception.statusCode.value())
        return ProblemDetail.forStatusAndDetail(status, safeDetail(status)).apply {
            title = status.reasonPhrase
            type = URI.create("urn:medapp:error:${status.name.lowercase().replace('_', '-')}")
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed").apply {
            title = "Invalid request"
            type = URI.create("urn:medapp:error:validation")
            setProperty(
                "errors",
                exception.bindingResult.fieldErrors.map { error ->
                    mapOf("field" to error.field, "reason" to (error.defaultMessage ?: "invalid value"))
                }
            )
        }

    private fun safeDetail(status: HttpStatus): String = when (status) {
        HttpStatus.NOT_FOUND -> "Requested resource does not exist"
        HttpStatus.FORBIDDEN -> "Request is not allowed"
        HttpStatus.UNAUTHORIZED -> "Authentication is required"
        HttpStatus.CONFLICT -> "Request conflicts with the current state"
        HttpStatus.TOO_MANY_REQUESTS -> "Registration limit exceeded"
        HttpStatus.BAD_REQUEST -> "Request cannot be processed"
        else -> status.reasonPhrase
    }
}
