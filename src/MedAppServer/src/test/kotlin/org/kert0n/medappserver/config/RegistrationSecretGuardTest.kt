package org.kert0n.medappserver.config

import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Приложение не поднимается с негодным секретом регистрации.
 *
 * Два случая. Пустой секрет — открытая регистрация при любом профиле. Заглушка из
 * `mock-prod` под профилем `prod` — то же самое, только незаметно: образ стартует с
 * `SPRING_PROFILES_ACTIVE=mock-prod,prod`, а `mock-prod` лежит в git вместе со своим
 * заведомо ненастоящим секретом, и проверка на пустоту тут молчит. Если оператор забыл файл
 * секрета, прод работал с общеизвестным значением. С паролём БД так не выйдет — без
 * настоящего приложение просто не подключится, — а секрет регистрации выстрелит тихо.
 *
 * Проверяется создание контекста, а не конструктор класса. Раньше проверки жили в `init`
 * контроллера и тест звал конструктор напрямую; теперь профиль выбирает `@Profile`, и
 * «стенд mock-prod поднимается» — это отсутствие бина [ProdSecretsGuard], а не ветка в
 * коде. Прямым вызовом такое не увидеть.
 *
 * [ApplicationContextRunner], а не `@SpringBootTest`: нужен контекст из двух классов, а не
 * всё приложение с базой. Инфраструктуру привязки свойств поднимает сама
 * `@EnableConfigurationProperties`, автоконфигурация для этого не нужна.
 */
class RegistrationSecretGuardTest {

    /** Регистрирует привязку свойств — в приложении её делает `@EnableConfigurationProperties`. */
    @EnableConfigurationProperties(RegistrationProperties::class)
    private class Properties

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(Properties::class.java, ProdSecretsGuard::class.java)

    private fun start(secret: String, vararg profiles: String) = runner
        .withPropertyValues(
            "registration.secret=$secret",
            "spring.profiles.active=${profiles.joinToString(",")}"
        )

    @Test
    fun `прод с заглушкой не поднимается`() {
        start(RegistrationProperties.MOCK_PROD_SECRET, "mock-prod", "prod").run { context ->
            val failure = context.startupFailure
            assertTrue(failure != null, "контекст обязан не подняться с заглушкой под профилем prod")
            // Сообщение обязано называть путь исправления: иначе дежурный получит падение
            // старта без понятия, что делать.
            assertTrue(
                failure.stackTraceToString().contains("secrets/registration.secret"),
                "сообщение должно подсказывать, где задать секрет: ${failure.message}"
            )
        }
    }

    @Test
    fun `стенд mock-prod с заглушкой поднимается`() {
        // Ровно ради этого профиль и существует: продовая конфигурация без продовых
        // секретов. Бин ProdSecretsGuard под ним не создаётся вовсе.
        start(RegistrationProperties.MOCK_PROD_SECRET, "mock-prod").run { context ->
            assertTrue(context.startupFailure == null, "стенд обязан подниматься с заглушкой")
        }
    }

    @Test
    fun `прод с настоящим секретом поднимается`() {
        start("s3cret-from-docker-secrets", "mock-prod", "prod").run { context ->
            assertTrue(context.startupFailure == null, "прод с настоящим секретом обязан подниматься")
        }
    }

    @Test
    fun `пустой секрет не поднимается ни при каком профиле`() {
        start("", "dev").run { context ->
            assertTrue(context.startupFailure != null, "пустой секрет обязан ронять старт")
        }
        start("   ", "mock-prod", "prod").run { context ->
            assertTrue(context.startupFailure != null, "секрет из пробелов обязан ронять старт")
        }
    }

    @Test
    fun `секрет вообще не задан — старт не проходит`() {
        // Базовой application.properties в проекте нет, поэтому вне профилей значения не
        // существует вовсе. Умолчание в конструкторе — пустая строка, и её ловит та же
        // проверка; без этого случая умолчание можно было бы поменять незаметно.
        runner.withPropertyValues("spring.profiles.active=dev").run { context ->
            assertTrue(context.startupFailure != null, "отсутствие секрета обязано ронять старт")
        }
    }

    @Test
    fun `литерал заглушки совпадает с application-mock-prod properties`() {
        // Значение продублировано в коде и в properties. Дубль осознанный — читать
        // properties из класса нечем, — но он обязан быть проверяемым: без этого теста
        // правка properties тихо обезоружила бы всю защиту, и никто бы не заметил.
        val properties = Files.readString(Path.of("src/main/resources/application-mock-prod.properties"))
        assertTrue(
            properties.contains("registration.secret=${RegistrationProperties.MOCK_PROD_SECRET}"),
            "код знает заглушку как '${RegistrationProperties.MOCK_PROD_SECRET}', " +
                "а в application-mock-prod.properties её нет"
        )
    }
}
