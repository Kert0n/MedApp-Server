package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Что присылает регистрация: логин и пароль придумывает клиент.
 *
 * Пока их придумывал сервер, пароль жил только в ответе: потерялся ответ или не записался он на
 * устройство — и на сервере оставалась учётка, в которую никто не войдёт, а повтор заводил
 * вторую. Теперь учётные данные известны до отправки, и повтор упирается в первичный ключ — 409,
 * как у аптечки и упаковки.
 *
 * Пароль — печатный ASCII без пробела, от 32 до 72 символов. Верхняя граница — bcrypt: он считает
 * байты, и длиннее 72 Spring пароль не принимает; ASCII уравнивает символы с байтами и не даёт
 * заголовку Basic зависеть от кодировки. Нижняя — затем, что стойкость ключа теперь выбирает
 * клиент.
 */
@Schema(description = "Credentials chosen by the client")
@Serializable
data class RegisterRequest(
    @Schema(description = "Client-invented login identifier")
    val login: Uuid,

    @field:Size(min = 32, max = 72)
    // Якоря нужны контракту, а не проверке: `@Pattern` сверяет строку целиком, а шаблон OpenAPI
    // ищется подстрокой, и без них клиент по контракту пропустил бы пароль с пробелом.
    @field:Pattern(regexp = "^[\\x21-\\x7E]+$")
    @Schema(description = "Secret key: 32 to 72 printable ASCII characters, no spaces")
    val password: String
)

/**
 * Только сам токен.
 *
 * Срок жизни уже в claim `exp`, дублировать его в обёртке — два источника одного факта. Схема
 * (`Bearer`) одна и зафиксирована в OpenAPI, от ответа к ответу не меняется.
 */
@Schema(description = "Issued access token")
@Serializable
data class TokenResponse(
    @Schema(description = "JWT access token")
    val accessToken: String
)
