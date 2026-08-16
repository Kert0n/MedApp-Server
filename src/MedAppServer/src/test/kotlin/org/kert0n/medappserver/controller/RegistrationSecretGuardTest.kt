package org.kert0n.medappserver.controller

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.services.security.RegistrationSecret
import org.mockito.kotlin.mock
import org.springframework.mock.env.MockEnvironment
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Прод не должен подниматься с секретом-заглушкой из git.
 *
 * Образ стартует с `SPRING_PROFILES_ACTIVE=mock-prod,prod`, а `mock-prod` лежит в
 * репозитории вместе со своим заведомо ненастоящим `registration.secret`. Прежняя проверка
 * ловила только **пустой** секрет, поэтому забытый файл секрета приводил к тому, что прод
 * работал с общеизвестным значением и молчал об этом. С паролём БД так не выйдет — без
 * настоящего приложение просто не подключится, — а секрет регистрации выстрелит тихо.
 *
 * Конструктор проверяется напрямую: поднимать контекст ради `require` в `init` незачем, а
 * прямой вызов заодно показывает, что именно ломается.
 */
class RegistrationSecretGuardTest {

    private val mockProdSecret = "mock-prod-secret"

    private fun build(secret: String, vararg profiles: String) = RegistrationSecret(
        value = secret,
        environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
    )

    private fun controller(secret: String, vararg profiles: String) = AuthController(
        registrationSecret = build(secret, *profiles),
        userService = mock<UserService>(),
        securityService = mock<SecurityService>()
    )

    @Test
    fun `прод с заглушкой не поднимается`() {
        val error = assertFailsWith<IllegalArgumentException> {
            build(mockProdSecret, "mock-prod", "prod")
        }
        // Сообщение обязано называть путь исправления: иначе дежурный получит падение
        // старта без понятия, что делать.
        assertTrue(
            error.message!!.contains("secrets/registration.secret"),
            "сообщение должно подсказывать, где задать секрет: ${error.message}"
        )
    }

    @Test
    fun `стенд mock-prod с заглушкой поднимается`() {
        // Ровно ради этого профиль и существует: продовая конфигурация без продовых
        // секретов. Если бы проверка смотрела только на значение, стенд бы сломался.
        controller(mockProdSecret, "mock-prod")
    }

    @Test
    fun `прод с настоящим секретом поднимается`() {
        controller("s3cret-from-docker-secrets", "mock-prod", "prod")
    }

    @Test
    fun `пустой секрет не поднимается ни при каком профиле`() {
        assertFailsWith<IllegalArgumentException> { build("", "dev") }
        assertFailsWith<IllegalArgumentException> { build("   ", "mock-prod", "prod") }
    }

    @Test
    fun `литерал заглушки совпадает с application-mock-prod properties`() {
        // Значение продублировано в коде и в properties. Дубль осознанный — читать
        // properties в init нечем, — но он обязан быть проверяемым: без этого теста правка
        // properties тихо обезоружила бы всю защиту, и никто бы не заметил.
        val properties = Files.readString(Path.of("src/main/resources/application-mock-prod.properties"))
        assertTrue(
            properties.contains("registration.secret=$mockProdSecret"),
            "AuthController знает заглушку как '$mockProdSecret', а в application-mock-prod.properties её нет"
        )
    }
}
