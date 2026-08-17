package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.stereotype.Component
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.method.HandlerMethod
import io.swagger.v3.oas.annotations.Operation as SwaggerOperation

/** Название и описание операции в опубликованном контракте. */
data class OperationText(val summary: String, val description: String)

/**
 * Тексты операций API, вынесенные из контроллеров: иначе аннотации занимают треть исходника, и
 * метод теряется между `@Operation` и списком ответов. Здесь только текст для клиентов —
 * обоснования решений остаются KDoc-ом рядом с кодом.
 *
 * Ключ — `operationId`, то есть имя метода контроллера, поэтому имена должны быть глобально
 * уникальны: при совпадении springdoc дописывает `_1`. `springdoc.spec-properties` не подошёл —
 * там ключ это путь с методом (`v1/drugs/{drugId}.get`), и его пришлось бы править при каждом
 * изменении маршрута.
 */
val OPERATION_TEXTS: Map<String, OperationText> = mapOf(
    // ── Аутентификация ───────────────────────────────────────────────────────────
    "register" to OperationText(
        "Register a new user",
        "Creates a new user and returns generated credentials."
    ),
    "token" to OperationText(
        "Issue JWT token",
        "Uses HTTP Basic authentication and returns a JWT access token. The token carries its own " +
            "expiry in the `exp` claim; use it as `Authorization: Bearer <token>`."
    ),

    // ── Препараты ────────────────────────────────────────────────────────────────
    "getDrug" to OperationText(
        "Get a drug",
        "Returns a drug the caller has access to."
    ),
    "createDrug" to OperationText(
        "Add a drug to a medicine kit",
        "Creates a drug in the given kit."
    ),
    "patchDrug" to OperationText(
        "Update a package",
        "Changes the given fields; absent fields are left as they are. Quantity here is a correction " +
            "of the record — you recounted the pack and saw a different number — not a refill, and it " +
            "leaves reservations alone."
    ),
    "deleteDrug" to OperationText(
        "Delete a drug",
        "Destroys the package and every reservation placed on it."
    ),
    "recordIntake" to OperationText(
        "Record an intake",
        "Takes the given amount out of the package — the only way its contents decrease. There is no " +
            "distinction between a planned intake and an emergency one: what was taken reduces the " +
            "package, and the reservation is the owner's to adjust. Taking more than the package holds " +
            "is refused: a package cannot be refilled, so a second pack is a second package. Returns " +
            "no body when the package ran out and was destroyed."
    ),
    "moveDrug" to OperationText(
        "Move a drug to another medicine kit",
        "Transfers the drug between kits."
    ),

    // ── Справочник ───────────────────────────────────────────────────────────────
    "searchDrugTemplates" to OperationText(
        "Search the catalogue",
        "Searches by name, Latin name, active substance and manufacturer."
    ),
    "listQuantityUnits" to OperationText(
        "List quantity units",
        "Shared vocabulary of quantity units. A drug references a unit by identifier, so this " +
            "list is where the identifier comes from."
    ),
    "listFormTypes" to OperationText(
        "List dosage forms",
        "Shared vocabulary of dosage forms, used the same way as quantity units."
    ),
    "getDrugTemplate" to OperationText(
        "Get a catalogue entry",
        "Returns a single catalogue entry."
    ),

    // ── Брони ────────────────────────────────────────────────────────────────────
    "listReservations" to OperationText(
        "List reservations",
        "Returns every reservation of the caller."
    ),
    "getReservation" to OperationText(
        "Get a reservation",
        "Returns the caller's reservation on the package."
    ),
    "createReservation" to OperationText(
        "Reserve part of a package",
        "Claims an amount of the package for the caller. The amount may exceed what is left in the " +
            "package: how much of their own reservation to keep is the owner's decision, not the " +
            "server's."
    ),
    "patchReservation" to OperationText(
        "Change the reserved amount",
        "Sets a new reserved amount. It may exceed what is left in the package."
    ),
    "deleteReservation" to OperationText(
        "Cancel a reservation",
        "Releases the claim. A reservation of zero does not exist, so cancelling is a deletion."
    ),

    // ── Аптечки и членство ───────────────────────────────────────────────────────
    "createMedKit" to OperationText(
        "Create a medicine kit",
        "Creates a kit owned by nobody in particular."
    ),
    "listMedKits" to OperationText(
        "List medicine kits",
        "Returns counters for every kit of the caller, without loading their contents."
    ),
    "getMedKit" to OperationText(
        "Get a medicine kit",
        "Returns the kit with its drugs."
    ),
    "createInvitation" to OperationText(
        "Create an invitation",
        "Issues a key others can use to join the kit."
    ),
    "deleteMedKit" to OperationText(
        "Delete a medicine kit",
        "Deletes the kit for every participant, including its packages and the reservations on them. Use when " +
            "the physical kit no longer exists as a shared thing. Pass targetMedKitId to move the drugs " +
            "into another kit of yours instead of discarding them. To leave a shared kit without " +
            "destroying it, delete your membership instead."
    ),
    "joinMedKit" to OperationText(
        "Join a medicine kit",
        "Accepts an invitation and joins the kit."
    ),
    "leaveMedKit" to OperationText(
        "Leave a medicine kit",
        "Removes the caller from the kit together with their reservations in it. The kit itself and " +
            "other participants stay."
    ),

    // ── Пользователь ─────────────────────────────────────────────────────────────
    "getSnapshot" to OperationText(
        "Get the caller's snapshot",
        "Returns the caller identifier with every accessible medicine kit and its drugs, for sync."
    )
)

/**
 * Подставляет тексты в операции и требует, чтобы текст был у каждой.
 *
 * Падение при отсутствии ключа намеренно: новый эндпойнт без описания должен ломать сборку, а не
 * появляться в контракте безымянным.
 */
@Component
class OperationTextCustomizer : GlobalOperationCustomizer {

    override fun customize(operation: Operation, handlerMethod: HandlerMethod): Operation {
        // Чужие контроллеры (тот же актуатор) документируются своими средствами.
        if (!handlerMethod.beanType.packageName.startsWith(CONTROLLER_PACKAGE)) return operation

        val operationId = operation.operationId ?: handlerMethod.method.name
        val text = OPERATION_TEXTS[operationId] ?: error(
            "Для операции '$operationId' (${handlerMethod.beanType.simpleName}.${handlerMethod.method.name}) " +
                "нет текста в OPERATION_TEXTS. Добавьте summary и description — эндпойнт не должен " +
                "попадать в контракт без описания."
        )
        operation.summary = text.summary
        operation.description = text.description
        addUnauthorizedUnlessPublic(operation, handlerMethod)
        return operation
    }

    /**
     * Дописывает 401 всем операциям, кроме публичных: требование Bearer стоит глобально, значит
     * вернуть 401 может любая защищённая, и клиент должен читать это из контракта.
     *
     * Публичность — по аннотации метода, а не по `operation.security`: для `security = []`
     * springdoc оставляет список пустым, и «требований нет» от «глобальное ещё не применено»
     * по нему не отличить.
     */
    private fun addUnauthorizedUnlessPublic(operation: Operation, handlerMethod: HandlerMethod) {
        // Публичной делает пустой список: `security = []`. Непустой — своя схема вместо
        // глобальной (Basic у выдачи токена), и 401 такая операция вернуть может.
        val declared = AnnotatedElementUtils.findMergedAnnotation(
            handlerMethod.method, SwaggerOperation::class.java
        )
        if (declared != null && declared.security.isEmpty()) return
        val responses = operation.responses ?: return
        if (responses.containsKey(UNAUTHORIZED)) return
        responses.addApiResponse(
            UNAUTHORIZED,
            ApiResponse().description("Authentication is required")
        )
    }

    private companion object {
        const val CONTROLLER_PACKAGE = "org.kert0n.medappserver.controller"
        const val UNAUTHORIZED = "401"
    }
}
