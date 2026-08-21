package org.kert0n.medappserver.services

import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Настройка kotlinx для тел запросов и ответов.
 *
 * Умолчание не годится по двум пунктам, и оба меняют то, что видит клиент.
 *
 * `encodeDefaults`: без него kotlinx молча не пишет поле, значение которого совпало с
 * умолчанием, а у нас такие есть — `formTypeId: UUID? = null` и вся правка пачки. Контракт
 * обещал бы поле, ответ его терял, и клиент отличал бы «сервер не прислал» от «сервер прислал
 * пусто» только по тому, чего в JSON нет.
 *
 * `ignoreUnknownKeys`: так ведёт себя Jackson в Spring Boot, и менять это заодно со сменой
 * библиотеки значило бы протащить второе решение под видом первого. Клиент новее сервера —
 * обычное состояние на телефонах, где обновляются не все и не сразу.
 *
 * Разделение с Jackson задаёт сам Boot: kotlinx берёт только типы с `@Serializable`, остальное
 * — включая `ProblemDetail` и актуаторы — остаётся Джексону.
 */
@Configuration
class JsonConfiguration {

    @Bean
    fun json(): Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}
