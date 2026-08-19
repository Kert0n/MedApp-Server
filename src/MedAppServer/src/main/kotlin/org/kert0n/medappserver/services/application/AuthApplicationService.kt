package org.kert0n.medappserver.services.application

import java.util.UUID
import org.kert0n.medappserver.domain.InvalidRegistrationSecret
import org.kert0n.medappserver.domain.TooManyRegistrations
import org.kert0n.medappserver.domain.User
import org.kert0n.medappserver.services.aggregate.UserService
import org.kert0n.medappserver.services.security.RegistrationSecret
import org.kert0n.medappserver.services.security.SecurityService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Регистрация и выдача токена.
 *
 * Раньше это лежало в контроллере, и вместе с ним там жили сравнение секрета, проверка лимита и
 * генерация ключа. HTTP-слою полагается перевод запроса и ответа, а не порядок этих проверок.
 */
@Service
class AuthApplicationService(
    private val userService: UserService,
    private val securityService: SecurityService,
    private val registrationSecret: RegistrationSecret
) {

    /**
     * Заводит пользователя и возвращает сгенерированные учётные данные.
     *
     * Секрет проверяется первым: иначе по коду ответа стало бы видно состояние лимита, а его
     * незачем показывать тому, кто секрета не знает.
     */
    @Transactional
    fun register(secret: String, clientAddress: String): Credentials {
        // Сравнение постоянного времени: `!=` останавливается на первом различии, и время
        // ответа выдало бы длину совпавшего начала.
        if (!securityService.secretsMatch(secret, registrationSecret.value)) throw InvalidRegistrationSecret()
        // Лимит по адресу: сдерживает автоматическую регистрацию, ничего не храня о человеке.
        if (!securityService.validateRequest(clientAddress)) throw TooManyRegistrations()

        val login = UUID.randomUUID()
        val key = securityService.generateKey(32)
        userService.registerNewUser(login, key, clientAddress)
        return Credentials(login, key)
    }

    /** Токен по уже аутентифицированному пользователю: проверку пароля сделал Basic-фильтр. */
    @Transactional(readOnly = true)
    fun issueToken(user: User): String = securityService.generateToken(user)

    /** Сгенерированные учётные данные. Доменного понятия за ними нет — это ответ на регистрацию. */
    data class Credentials(val login: UUID, val key: String)
}
