package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.stereotype.Component
import org.springframework.web.method.HandlerMethod

/**
 * Доводит операции контроллеров до опубликованного вида.
 *
 * Сами тексты живут в `@Operation` рядом с методом: контроллер занимается только переводом
 * HTTP, и держать описание эндпойнта в отдельном файле значит искать его там по имени метода.
 * Здесь остаётся то, чего в аннотации не выразить, — требование описания и общий для всех
 * защищённых операций 401.
 */
@Component
class OperationContractCustomizer : GlobalOperationCustomizer {

    override fun customize(operation: Operation, handlerMethod: HandlerMethod): Operation {
        // Чужие контроллеры (тот же актуатор) документируются своими средствами.
        if (!handlerMethod.beanType.packageName.startsWith(CONTROLLER_PACKAGE)) return operation

        requireDocumented(operation, handlerMethod)
        addUnauthorizedUnlessPublic(operation)
        return operation
    }

    /**
     * Падение при пустом тексте намеренно: новый эндпойнт без описания должен ломать сборку, а
     * не появляться в контракте безымянным.
     */
    private fun requireDocumented(operation: Operation, handlerMethod: HandlerMethod) {
        val missing = buildList {
            if (operation.summary.isNullOrBlank()) add("summary")
            if (operation.description.isNullOrBlank()) add("description")
        }
        if (missing.isEmpty()) return

        val name = operation.operationId ?: handlerMethod.method.name
        error(
            "У операции '$name' (${handlerMethod.beanType.simpleName}.${handlerMethod.method.name}) " +
                "нет ${missing.joinToString(" и ")}. Добавьте @Operation рядом с методом — эндпойнт " +
                "не должен попадать в контракт без описания."
        )
    }

    /**
     * Дописывает 401 всем операциям, кроме публичных: требование Bearer стоит глобально, значит
     * вернуть 401 может любая защищённая, и клиент должен читать это из контракта.
     *
     * Требование берётся из самой операции: каждая объявляет свой `security` явно, умолчания
     * на уровне документа нет. Пустое требование значит «отвечает без токена», и 401 такая
     * операция вернуть не может. Своя схема вместо общей (Basic у выдачи токена) публичной её
     * не делает.
     *
     * Что объявление есть у каждой, следит `ControllerReadabilityTest`: пустой список — это и
     * значение по умолчанию у `@Operation`, поэтому «объявили пусто» отличимо от «не
     * объявляли» только в исходнике, но не здесь.
     */
    private fun addUnauthorizedUnlessPublic(operation: Operation) {
        if (operation.security.isNullOrEmpty()) return
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
