package org.kert0n.medappserver.testutil

import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kert0n.medappserver.api.DrugCreateRequest
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.api.DrugSyncRequest
import org.kert0n.medappserver.api.IntakeRequest
import org.kert0n.medappserver.api.MedKitCreateRequest
import org.kert0n.medappserver.api.MembershipCreateRequest
import org.kert0n.medappserver.api.RegisterRequest
import org.kert0n.medappserver.api.ReservationCreateRequest
import org.kert0n.medappserver.api.ReservationPatchRequest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*

/** Пути API в одном месте: пока только аутентификация. */
object ApiRoutes {
    const val REGISTER = "/v1/auth/register"
    const val TOKEN = "/v1/auth/token"
    const val ME = "/v1/users/me"

    const val DRUG_TEMPLATES = "/v1/drug-templates"
    const val RESERVATIONS = "/v1/reservations"
    const val MED_KITS = "/v1/med-kits"
    const val MEMBERSHIPS = "/v1/med-kit-memberships"

    fun drug(drugId: Any) = "/v1/drugs/$drugId"
    fun intake(drugId: Any, intakeId: Any) = "/v1/drugs/$drugId/intakes/$intakeId"
    fun sync(drugId: Any, syncId: Any) = "/v1/drugs/$drugId/sync/$syncId"
    fun drugsOf(medKitId: Any) = "/v1/med-kits/$medKitId/drugs"
    fun drugIn(medKitId: Any, drugId: Any) = "/v1/med-kits/$medKitId/drugs/$drugId"
    fun drugTemplate(templateId: Any) = "/v1/drug-templates/$templateId"
    fun reservation(drugId: Any) = "/v1/reservations/$drugId"
    fun medKit(medKitId: Any) = "/v1/med-kits/$medKitId"
    fun invitations(medKitId: Any) = "/v1/med-kits/$medKitId/invitations"
    fun membership(medKitId: Any) = "/v1/med-kit-memberships/$medKitId"

    /** Маршруты, которые API больше не обслуживает. */
    val RETIRED = listOf(
        "/auth/register", "/auth/login", "/user",
        "/drug", "/drug/quantity/x", "/drug/consume/x", "/drug/move/x",
        "/drug/template/search", "/drug/template/x",
        "/using", "/using/drug/x", "/using/drug/x/intake",
        // Второй ряд — пути прежнего словаря: планов лечения и расхода по плану в API нет,
        // есть брони и приём упаковки.
        "/v1/treatment-plans", "/v1/treatment-plans/x",
        "/v1/drugs/x/consumptions", "/v1/intakes/x",
        // Приём без идентификатора: повторить потерянный ответ было нечем, теперь он PUT по id.
        "/v1/drugs/x/intakes",
        "/med-kit", "/med-kit/x", "/med-kit/join", "/med-kit/x/share", "/med-kit/x/leave"
    )
}

/** Обёртка над MockMvc для публичных операций API. */
class ApiTestClient(private val mockMvc: MockMvc, private val json: Json = Json) {

    fun register(secret: String, login: Uuid, password: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.REGISTER).header(REGISTRATION_TOKEN_HEADER, secret)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.encodeToString(RegisterRequest(login, password)))
    )

    /** Запрос токена с уже готовым заголовком Authorization — в том числе намеренно кривым. */
    fun tokenWithAuthorization(header: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.TOKEN).header("Authorization", header)
    )

    fun tokenWithoutCredentials(): ResultActions = mockMvc.perform(post(ApiRoutes.TOKEN))

    /** Ресурсные запросы от лица участника истории. */
    fun asUser(userId: Uuid): AuthenticatedApiTestClient = AuthenticatedApiTestClient(mockMvc, json, userId)

    private companion object {
        const val REGISTRATION_TOKEN_HEADER = "X-Registration-Token"
    }
}

/**
 * Клиент одного участника user-story.
 *
 * Он намеренно возвращает весь HTTP-ответ: ожидаемый статус и наличие тела задаёт сама история,
 * поэтому транспортный контракт не прячется внутри удобного метода.
 */
class AuthenticatedApiTestClient internal constructor(
    private val mockMvc: MockMvc,
    private val json: Json,
    private val userId: Uuid
) {

    fun snapshot(): TestHttpResponse = perform(get(ApiRoutes.ME))

    fun createMedKit(request: MedKitCreateRequest): TestHttpResponse =
        perform(post(ApiRoutes.MED_KITS), request)

    fun listMedKits(): TestHttpResponse = perform(get(ApiRoutes.MED_KITS))

    fun getMedKit(medKitId: Uuid): TestHttpResponse = perform(get(ApiRoutes.medKit(medKitId)))

    fun inviteTo(medKitId: Uuid): TestHttpResponse = perform(post(ApiRoutes.invitations(medKitId)))

    fun join(request: MembershipCreateRequest): TestHttpResponse =
        perform(post(ApiRoutes.MEMBERSHIPS), request)

    fun leave(medKitId: Uuid): TestHttpResponse = perform(delete(ApiRoutes.membership(medKitId)))

    fun deleteMedKit(medKitId: Uuid, targetMedKitId: Uuid? = null): TestHttpResponse {
        val request = delete(ApiRoutes.medKit(medKitId))
        if (targetMedKitId != null) request.queryParam("targetMedKitId", targetMedKitId.toString())
        return perform(request)
    }

    fun createDrug(medKitId: Uuid, request: DrugCreateRequest): TestHttpResponse =
        perform(post(ApiRoutes.drugsOf(medKitId)), request)

    fun getDrug(drugId: Uuid): TestHttpResponse = perform(get(ApiRoutes.drug(drugId)))

    fun patchDrug(drugId: Uuid, request: DrugPatchRequest): TestHttpResponse =
        perform(patch(ApiRoutes.drug(drugId)), request)

    fun deleteDrug(drugId: Uuid, version: Long): TestHttpResponse =
        perform(delete(ApiRoutes.drug(drugId)).queryParam("version", version.toString()))

    /** Идентификатор приёма свежий, если история не проверяет именно повтор. */
    fun recordIntake(drugId: Uuid, request: IntakeRequest, intakeId: Uuid = Uuid.random()): TestHttpResponse =
        perform(put(ApiRoutes.intake(drugId, intakeId)), request)

    fun synchronise(drugId: Uuid, syncId: Uuid, request: DrugSyncRequest): TestHttpResponse =
        perform(put(ApiRoutes.sync(drugId, syncId)), request)

    fun moveDrug(drugId: Uuid, targetMedKitId: Uuid, version: Long): TestHttpResponse =
        perform(
            put(ApiRoutes.drugIn(targetMedKitId, drugId))
                .queryParam("version", version.toString())
        )

    fun listReservations(): TestHttpResponse = perform(get(ApiRoutes.RESERVATIONS))

    fun getReservation(drugId: Uuid): TestHttpResponse = perform(get(ApiRoutes.reservation(drugId)))

    fun createReservation(request: ReservationCreateRequest): TestHttpResponse =
        perform(post(ApiRoutes.RESERVATIONS), request)

    fun patchReservation(drugId: Uuid, request: ReservationPatchRequest): TestHttpResponse =
        perform(patch(ApiRoutes.reservation(drugId)), request)

    fun deleteReservation(drugId: Uuid, version: Long): TestHttpResponse =
        perform(delete(ApiRoutes.reservation(drugId)).queryParam("version", version.toString()))

    private fun perform(request: MockHttpServletRequestBuilder): TestHttpResponse =
        TestHttpResponse(
            mockMvc.perform(request.with(jwt().jwt { it.subject(userId.toString()) })).andReturn().response,
            json
        )

    private inline fun <reified T> perform(
        request: MockHttpServletRequestBuilder,
        body: T
    ): TestHttpResponse = perform(
        request
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.encodeToString(body))
    )
}

/** Ответ MockMvc с явными проверками проводного контракта. */
class TestHttpResponse internal constructor(
    val raw: MockHttpServletResponse,
    @PublishedApi internal val json: Json
) {
    fun expectStatus(expectedStatus: Int): TestHttpResponse = apply {
        assertEquals(expectedStatus, raw.status, "Unexpected HTTP response: ${raw.contentAsString}")
    }

    fun expectEmpty(expectedStatus: Int) {
        expectStatus(expectedStatus)
        assertEquals(0, raw.contentAsByteArray.size, "Response body must be empty")
    }

    inline fun <reified T> expectBody(expectedStatus: Int): T {
        expectStatus(expectedStatus)
        val actualContentType = assertNotNull(raw.contentType, "Response must have a content type")
        assertTrue(
            MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(actualContentType)),
            "Expected a JSON response, got $actualContentType"
        )
        assertTrue(raw.contentAsByteArray.isNotEmpty(), "Response body must not be empty")
        return json.decodeFromString(raw.contentAsString)
    }
}
