package org.kert0n.medappserver.testutil

import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post

/**
 * Пути API в одном месте.
 *
 * Ресурсы ещё переезжают на `/v1`, и без такого списка каждый переезд правил бы по десятку
 * тестовых файлов. Здесь пока только аутентификация — остальные маршруты приедут вместе с
 * ресурсным API.
 */
object ApiRoutes {
    const val REGISTER = "/v1/auth/register"
    const val TOKEN = "/v1/auth/token"
    const val ME = "/v1/users/me"

    const val DRUG_TEMPLATES = "/v1/drug-templates"
    const val RESERVATIONS = "/v1/reservations"
    const val MED_KITS = "/v1/med-kits"
    const val MEMBERSHIPS = "/v1/med-kit-memberships"

    fun drug(drugId: Any) = "/v1/drugs/$drugId"
    fun intakes(drugId: Any) = "/v1/drugs/$drugId/intakes"
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
        // Планы лечения стали бронями, а расход — приёмом упаковки. Отдельный маршрут приёма
        // с клиентским идентификатором так и не заработал: он отвечал 501 и ушёл вместе с
        // понятием «приём по плану».
        "/v1/treatment-plans", "/v1/treatment-plans/x",
        "/v1/drugs/x/consumptions", "/v1/intakes/x",
        "/med-kit", "/med-kit/x", "/med-kit/join", "/med-kit/x/share", "/med-kit/x/leave"
    )
}

/** Обёртка над MockMvc для публичных операций аутентификации. */
class ApiTestClient(private val mockMvc: MockMvc) {

    fun register(secret: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.REGISTER).header(REGISTRATION_TOKEN_HEADER, secret)
    )

    /** Запрос токена с уже готовым заголовком Authorization — в том числе намеренно кривым. */
    fun tokenWithAuthorization(header: String): ResultActions = mockMvc.perform(
        post(ApiRoutes.TOKEN).header("Authorization", header)
    )

    fun tokenWithoutCredentials(): ResultActions = mockMvc.perform(post(ApiRoutes.TOKEN))

    private companion object {
        const val REGISTRATION_TOKEN_HEADER = "X-Registration-Token"
    }
}
