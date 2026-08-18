package org.kert0n.medappserver.controller

import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import java.util.*
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationDTO
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.kert0n.medappserver.api.toDto
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.application.ReservationApplicationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody

/**
 * Бронь адресуется упаковкой: двух броней на одну пачку у человека быть не может, поэтому пары
 * «человек и упаковка» достаточно, а собственный идентификатор был бы лишней сущностью.
 */
@RestController
@RequestMapping("/v1/reservations")
@Tag(name = "Reservations", description = "How much of a package the caller claims for themselves")
class ReservationController(
    private val reservations: ReservationApplicationService,
    private val preconditions: Preconditions
) {

    private val logger = LoggerFactory.getLogger(ReservationController::class.java)

    @GetMapping
    @ApiResponse(responseCode = "200", description = "Reservations returned")
    fun listReservations(authentication: Authentication): List<ReservationDTO> {
        logger.debug("GET /v1/reservations by user {}", authentication.userId)
        return reservations.ofUser(authentication.userId)
    }

    @GetMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Reservation found")
    @ApiResponse(responseCode = "404", description = "No reservation on this package", content = [Content()])
    fun getReservation(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID
    ): ResponseEntity<ReservationDTO> {
        logger.debug("GET /v1/reservations/{} by user {}", drugId, authentication.userId)
        return reservations.read(authentication.userId, drugId).withEtag()
    }

    @PostMapping
    @ApiResponse(responseCode = "201", description = "Reservation created")
    @ApiResponse(responseCode = "400", description = "Invalid amount", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Package is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Reservation on this package already exists", content = [Content()])
    fun createReservation(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Reservation to create")
        @Valid @RequestBody request: ReservationCreateRequest
    ): ResponseEntity<ReservationDTO> {
        logger.debug("POST /v1/reservations by user {} on drug {}", authentication.userId, request.drugId)
        // Предусловия нет: создание ничего не перезаписывает, а вторая бронь на ту же пачку
        // отвергается сама по себе — 409 приходит от правила, а не от версии.
        return reservations
            .create(authentication.userId, request.drugId, request.amount)
            .createdWithEtag()
    }

    @PatchMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Reservation updated")
    @ApiResponse(responseCode = "400", description = "Invalid amount or malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No reservation on this package", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Reservation has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun patchReservation(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?,
        @SwaggerRequestBody(description = "New reserved amount")
        @Valid @RequestBody request: ReservationPatchRequest
    ): ResponseEntity<ReservationDTO> {
        logger.debug("PATCH /v1/reservations/{} by user {}", drugId, authentication.userId)
        return reservations
            .changeTo(authentication.userId, drugId, request.amount, preconditions.requiredMatch(ifMatch))
            .withEtag()
    }

    @DeleteMapping("/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Reservation cancelled")
    @ApiResponse(responseCode = "400", description = "Malformed If-Match", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No reservation on this package", content = [Content()])
    @ApiResponse(responseCode = "412", description = "Reservation has changed since the version supplied", content = [Content()])
    @ApiResponse(responseCode = "428", description = "If-Match is required", content = [Content()])
    fun deleteReservation(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID,
        @Parameter(
            description = "Version the caller decided by, as a strong entity tag",
            required = true,
            example = "\"3\""
        )
        @RequestHeader(value = Preconditions.IF_MATCH, required = false) ifMatch: String?
    ) {
        logger.debug("DELETE /v1/reservations/{} by user {}", drugId, authentication.userId)
        reservations.cancel(authentication.userId, drugId, preconditions.requiredMatch(ifMatch))
    }
}
