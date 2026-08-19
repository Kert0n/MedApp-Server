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
import org.kert0n.medappserver.services.aggregate.userId
import org.kert0n.medappserver.services.application.ReservationApplicationService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
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
class ReservationController(private val reservations: ReservationApplicationService) {

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
    ): ReservationDTO {
        logger.debug("GET /v1/reservations/{} by user {}", drugId, authentication.userId)
        return reservations.read(authentication.userId, drugId)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201", description = "Reservation created")
    @ApiResponse(responseCode = "400", description = "Invalid amount", content = [Content()])
    @ApiResponse(responseCode = "404", description = "Package is not accessible", content = [Content()])
    @ApiResponse(responseCode = "409", description = "Reservation on this package already exists", content = [Content()])
    fun createReservation(
        authentication: Authentication,
        @SwaggerRequestBody(description = "Reservation to create")
        @Valid @RequestBody request: ReservationCreateRequest
    ): ReservationDTO {
        logger.debug("POST /v1/reservations by user {} on drug {}", authentication.userId, request.drugId)
        return reservations.create(authentication.userId, request.drugId, request.amount)
    }

    @PatchMapping("/{drugId}")
    @ApiResponse(responseCode = "200", description = "Reservation updated")
    @ApiResponse(responseCode = "400", description = "Invalid amount", content = [Content()])
    @ApiResponse(responseCode = "404", description = "No reservation on this package", content = [Content()])
    fun patchReservation(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID,
        @SwaggerRequestBody(description = "New reserved amount")
        @Valid @RequestBody request: ReservationPatchRequest
    ): ReservationDTO {
        logger.debug("PATCH /v1/reservations/{} by user {}", drugId, authentication.userId)
        return reservations.changeTo(authentication.userId, drugId, request.amount)
    }

    @DeleteMapping("/{drugId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204", description = "Reservation cancelled")
    @ApiResponse(responseCode = "404", description = "No reservation on this package", content = [Content()])
    fun deleteReservation(
        authentication: Authentication,
        @Parameter(description = "Package identifier") @PathVariable drugId: UUID
    ) {
        logger.debug("DELETE /v1/reservations/{} by user {}", drugId, authentication.userId)
        reservations.cancel(authentication.userId, drugId)
    }
}
