package org.kert0n.medappserver.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * Общий секрет регистрации: без него `POST /v1/auth/register` открыт всем.
 *
 * Пустая строка по умолчанию, а не отсутствие значения: секрет может прийти любым путём —
 * переменной окружения `REGISTRATION_SECRET`, файлом секрета через configtree, профилем, —
 * и проверка ниже одинаково ловит случай, когда он не пришёл ниоткуда. Базовой
 * `application.properties` в проекте нет, поэтому вне профилей значения не существует
 * вовсе.
 *
 * Пустой секрет — не конфигурация, а обходимый барьер, поэтому приложение падает при
 * старте, а не принимает любую регистрацию. `require` в `init` даёт это бесплатно: Spring
 * не создаст бин и не поднимет контекст.
 */
@ConfigurationProperties(prefix = "registration")
class RegistrationProperties(
    val secret: String = ""
) {
    init {
        require(secret.isNotBlank()) {
            "registration.secret must not be blank: set the REGISTRATION_SECRET environment " +
                "variable or provide application-prod.properties"
        }
    }

    companion object {
        /**
         * Дублирует значение из `application-mock-prod.properties`.
         *
         * Дубль осознанный — читать properties отсюда нечем, — но он обязан быть
         * проверяемым: без теста правка properties тихо обезоружила бы [ProdSecretsGuard].
         */
        const val MOCK_PROD_SECRET = "mock-prod-secret"
    }
}

/**
 * Прод не поднимается с заглушкой из mock-prod.
 *
 * Образ стартует с `SPRING_PROFILES_ACTIVE=mock-prod,prod`, а `mock-prod` лежит в git
 * вместе со своим заведомо ненастоящим секретом. Проверка на пустоту тут молчит: секрет-то
 * непустой. Если оператор забыл файл секрета, прод поднимался с общеизвестным значением и
 * никак об этом не сообщал. С паролём БД так не выйдет — без настоящего приложение просто
 * не подключится, — а секрет регистрации выстрелит тихо.
 *
 * Профиль выбирает [Profile], а не чтение `environment.activeProfiles`: ровно для этого
 * аннотация и существует, и при ней «стенд mock-prod без прода поднимается» — не ветка в
 * коде, а отсутствие бина.
 *
 * Живёт в `config`, а не в `AuthController`, потому что это условие старта приложения, а не
 * поведение HTTP-эндпоинта. В контроллере проверка выполнялась при создании бина по
 * стечению обстоятельств — оттого, что контроллер тоже бин, — и требовала тащить туда
 * `Environment`, которому в контроллере делать нечего.
 */
@Configuration(proxyBeanMethods = false)
@Profile(PROD_PROFILE)
class ProdSecretsGuard(properties: RegistrationProperties) {
    init {
        require(properties.secret != RegistrationProperties.MOCK_PROD_SECRET) {
            "registration.secret is still the mock-prod placeholder while the '$PROD_PROFILE' " +
                "profile is active: provide the real secret via secrets/registration.secret " +
                "or application-prod.properties"
        }
    }
}

const val PROD_PROFILE = "prod"
