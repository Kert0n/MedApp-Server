package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
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

/**
 * Операция, принимающая версию, объявляет её коды отказа.
 *
 * Весь механизм предусловий отвечает 428 и 412, и клиент узнаёт об этом только из контракта.
 *
 * Проставляется по факту, а не рукой у каждой операции: у кого есть версия — у того есть и коды.
 * Расписать их по аннотациям значило бы завести девять мест, где можно забыть, — а забывают всегда
 * там же, где добавляют новый эндпойнт.
 *
 * Что считается «принимает версию»: параметр запроса `version` или поле `version` в теле — то есть
 * ровно то, что разворачивает `statedVersion`. Синхронизация под правило не подпадает намеренно:
 * её версии зовутся иначе (`drugVersion`), едут телом и отвечают 409, потому что предусловием
 * запроса они не были (2.6). Это не исключение из правила, а другое правило — и написано оно
 * здесь, а не подразумевается.
 *
 * Тело видно только там, где под рукой `components`: `$ref` разрешается по ним, поэтому проход
 * идёт по документу целиком, а не по одной операции.
 */
@Component
class PreconditionResponsesCustomizer : GlobalOpenApiCustomizer {

    override fun customise(openApi: OpenAPI) {
        val schemas: Map<String, Schema<*>> = openApi.components?.schemas.orEmpty()
        openApi.paths?.values?.forEach { path ->
            path.readOperations().forEach { operation ->
                if (statesVersion(operation, schemas)) declarePreconditions(operation)
            }
        }
    }

    private fun statesVersion(operation: Operation, schemas: Map<String, Schema<*>>): Boolean =
        operation.parameters.orEmpty().any { it.name == VERSION && it.`in` == QUERY } ||
            VERSION in bodyProperties(operation, schemas)

    /**
     * Свойства тела — только верхнего уровня.
     *
     * Вглубь не идём: у синхронизации версия картины броней лежит во вложенной части, и
     * заглядывающее вглубь правило записало бы ей 412, которого она не отвечает.
     */
    private fun bodyProperties(operation: Operation, schemas: Map<String, Schema<*>>): Set<String> {
        val declared = operation.requestBody?.content?.values?.firstOrNull()?.schema ?: return emptySet()
        val resolved = declared.`$ref`?.substringAfterLast('/')?.let { schemas[it] } ?: declared
        return resolved.properties?.keys.orEmpty()
    }

    private fun declarePreconditions(operation: Operation) {
        val responses = operation.responses ?: return
        responses.putIfAbsent(
            PRECONDITION_REQUIRED,
            ApiResponse().description("The command did not state the version it acts on")
        )
        responses.putIfAbsent(
            PRECONDITION_FAILED,
            ApiResponse().description("The stated version is not the current one")
        )
    }

    private companion object {
        const val VERSION = "version"
        const val QUERY = "query"
        const val PRECONDITION_REQUIRED = "428"
        const val PRECONDITION_FAILED = "412"
    }
}
