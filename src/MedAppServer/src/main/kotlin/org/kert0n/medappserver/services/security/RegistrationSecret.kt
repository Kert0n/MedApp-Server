package org.kert0n.medappserver.services.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Секрет регистрации и проверки, без которых он не защищает.
 *
 * Проверок две, и вторая важнее первой. Образ стартует с
 * `SPRING_PROFILES_ACTIVE=mock-prod,prod`, а `application-mock-prod.properties` лежит в
 * репозитории вместе со своим заведомо ненастоящим секретом. Проверка только на пустоту
 * пропускала случай, когда файл продовых секретов забыт: прод поднимался с общеизвестным
 * значением из git и молчал об этом. С паролём БД так не выйдет — без настоящего
 * приложение просто не подключится, — а секрет регистрации выстрелил бы тихо.
 */
@Component
class RegistrationSecret(
    // Пустая строка по умолчанию, а не отсутствие значения: секрет может прийти любым путём
    // (переменная окружения, docker secret через configtree, профиль), и проверка ниже
    // одинаково поймает случай, когда он не пришёл ниоткуда.
    @Value($$"${registration.secret:}") val value: String,
    environment: Environment
) {
    init {
        require(value.isNotBlank()) {
            "registration.secret must not be blank: set the REGISTRATION_SECRET environment " +
                "variable or provide application-prod.properties"
        }
        require(!(environment.activeProfiles.contains(PROD_PROFILE) && value == MOCK_PROD_SECRET)) {
            "registration.secret is still the mock-prod placeholder: provide " +
                "secrets/registration.secret or application-prod.properties"
        }
    }

    private companion object {
        const val PROD_PROFILE = "prod"
        const val MOCK_PROD_SECRET = "mock-prod-secret"
    }
}
