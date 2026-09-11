package org.kert0n.medappserver.services.application

import kotlin.uuid.Uuid
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
 * Порядок проверок — сравнение секрета, лимит, запись — живёт здесь, а не в контроллере:
 * HTTP-слою полагается перевод запроса и ответа, а не решение о том, что проверяется первым.
 */
@Service
class AuthApplicationService(
    private val userService: UserService,
    private val securityService: SecurityService,
    private val registrationSecret: RegistrationSecret
) {

    /**
     * Заводит пользователя с учётными данными, которые придумал клиент.
     *
     * Секрет проверяется первым: иначе по коду ответа стало бы видно состояние лимита, а его
     * незачем показывать тому, кто секрета не знает.
     *
     * Занятый логин отвергает первичный ключ, а не чтение перед записью: одновременный повтор
     * прошёл бы мимо чтения. Такой повтор квоту адреса не тратит — засчитывается только
     * состоявшаяся запись.
     */
    @Transactional
    fun register(secret: String, clientAddress: String, login: Uuid, password: String) {
        // Сравнение постоянного времени: `!=` останавливается на первом различии, и время
        // ответа выдало бы длину совпавшего начала.
        if (!securityService.secretsMatch(secret, registrationSecret.value)) throw InvalidRegistrationSecret()
        // Лимит по адресу: сдерживает автоматическую регистрацию, ничего не храня о человеке.
        if (!securityService.validateRequest(clientAddress)) throw TooManyRegistrations()

        userService.registerNewUser(login, password, clientAddress)
    }

    /** Токен по уже аутентифицированному пользователю: проверку пароля сделал Basic-фильтр. */
    @Transactional(readOnly = true)
    fun issueToken(user: User): String = securityService.generateToken(user)
}
