package org.kert0n.medappserver.services

import io.swagger.v3.core.util.PrimitiveType
import kotlin.uuid.Uuid
import org.springframework.context.annotation.Configuration

/**
 * Публикует `kotlin.uuid.Uuid` так же, как выглядел джавовый: `type: string, format: uuid`.
 *
 * Своими силами swagger котлиновский тип не узнаёт: в схемах он разложил бы его на внутренние
 * поля, а в параметрах пути печатал бы голое `type: string` без формата.
 *
 * Регистрация идёт в `PrimitiveType` — на том уровне, где swagger решает, что тип скалярный.
 * Ни `SpringDocUtils.replaceWithClass`, ни свой `ModelConverter` параметры не покрывают:
 * замерено, `format: uuid` пропадал у четырнадцати параметров пути при обоих способах.
 */
@Configuration
class UuidSchemaConfiguration {

    companion object {
        init {
            PrimitiveType.customClasses()[Uuid::class.java.name] = PrimitiveType.UUID
        }
    }
}
