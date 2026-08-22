package org.kert0n.medappserver.services

import io.swagger.v3.oas.models.media.Schema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.serializer
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider

/**
 * Контракт объявляет, какие поля приезжают всегда, а какие бывают пустыми.
 *
 * Источник — тот же сериализатор, который эти тела и пишет: у kotlinx для каждого поля есть
 * ответ на оба вопроса, и брать их откуда-то ещё значило бы завести второе мнение о том же.
 * Написанное руками `@Schema(required = true)` таким вторым мнением и было: обязательность
 * держалась не на типах, а на том, где не забыли аннотацию, — и в ответах не была объявлена
 * нигде.
 *
 * - **обязательно** то, у чего нет значения по умолчанию: пропустить такое поле нельзя;
 * - **пустым** может прийти то, чей тип обнуляем.
 *
 * Пустое и необязательное — разные вещи, и разводятся они здесь порознь. `formTypeId: Uuid?` в
 * ответе приезжает всегда (`encodeDefaults = true`), но значением бывает `null`: обязательное и
 * обнуляемое одновременно. Он же в запросе объявлен как `Uuid? = null` — там его можно не
 * присылать вовсе.
 */
@Configuration
class OpenApiFieldPresence {

    @Bean
    fun fieldPresence(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val wire = wireDescriptors()
        openApi.components?.schemas?.forEach { (name, schema) ->
            wire[name]?.let { describe(schema, it) }
        }
    }

    /**
     * Схемы springdoc зовёт по простому имени класса — по нему и находится описание провода.
     *
     * Класс, до которого поиск не дотянулся, остался бы без объявлений молча, поэтому то же
     * соответствие проверяется по исходникам в `ContractFieldPresenceTest`.
     */
    private fun wireDescriptors(): Map<String, SerialDescriptor> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(Serializable::class.java))
        return scanner.findCandidateComponents(CONTRACT_PACKAGE)
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it) }
            .associate { it.simpleName to serializer(it).descriptor }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun describe(schema: Schema<*>, descriptor: SerialDescriptor) {
        val mandatory = mutableListOf<String>()
        for (index in 0 until descriptor.elementsCount) {
            val field = descriptor.getElementName(index)
            if (!descriptor.isElementOptional(index)) mandatory += field
            if (descriptor.getElementDescriptor(index).isNullable) allowEmpty(schema, field)
        }
        schema.required = mandatory.ifEmpty { null }
    }

    /**
     * Пустое значение в контракте версии 3.1 — второй тип, а не флаг `nullable`.
     *
     * Свойство, уехавшее в `$ref`, своего типа не имеет: обнуляемых ссылок в контракте нет, и
     * заводить ради них обёртку `anyOf` незачем.
     */
    private fun allowEmpty(schema: Schema<*>, field: String) {
        val property = schema.properties?.get(field) ?: return
        property.types?.let { property.types = LinkedHashSet(it) + NULL_TYPE }
    }

    private companion object {
        const val CONTRACT_PACKAGE = "org.kert0n.medappserver.api"
        const val NULL_TYPE = "null"
    }
}
