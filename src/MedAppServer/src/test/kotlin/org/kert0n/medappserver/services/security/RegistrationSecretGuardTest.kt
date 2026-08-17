package org.kert0n.medappserver.services.security

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

/**
 * Прод не должен подниматься с секретом-заглушкой из git.
 *
 * Образ стартует с `SPRING_PROFILES_ACTIVE=mock-prod,prod`, а `mock-prod` лежит в
 * репозитории вместе со своим ненастоящим `registration.secret`. Проверка только на пустоту
 * пропускала забытый файл продовых секретов: сервер работал с общеизвестным значением и
 * молчал об этом.
 *
 * Конструктор проверяется напрямую: поднимать контекст ради `require` в `init` незачем.
 */
class RegistrationSecretGuardTest {

    private val mockProdSecret = "mock-prod-secret"

    private fun build(secret: String, vararg profiles: String) = RegistrationSecret(
        value = secret,
        environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
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
        // Ровно ради этого профиль и существует: продовая конфигурация без продовых секретов.
        build(mockProdSecret, "mock-prod")
    }

    @Test
    fun `прод с настоящим секретом поднимается`() {
        build("s3cret-from-docker-secrets", "mock-prod", "prod")
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
        // properties тихо обезоружила бы защиту, и никто бы не заметил.
        val properties = Files.readString(Path.of("src/main/resources/application-mock-prod.properties"))
        assertTrue(
            properties.contains("registration.secret=$mockProdSecret"),
            "guard знает заглушку как '$mockProdSecret', а в application-mock-prod.properties её нет"
        )
    }
}
