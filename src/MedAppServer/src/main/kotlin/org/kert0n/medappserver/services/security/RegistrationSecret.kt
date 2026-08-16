package org.kert0n.medappserver.services.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

@Component
class RegistrationSecret(
    @Value($$"${registration.secret:}") val value: String,
    environment: Environment
) {
    init {
        require(value.isNotBlank()) {
            "registration.secret must not be blank: set REGISTRATION_SECRET or application-prod.properties"
        }
        require(!(environment.activeProfiles.contains(PROD_PROFILE) && value == MOCK_PROD_SECRET)) {
            "registration.secret is still the mock-prod placeholder: provide secrets/registration.secret " +
                "or application-prod.properties"
        }
    }

    private companion object {
        const val PROD_PROFILE = "prod"
        const val MOCK_PROD_SECRET = "mock-prod-secret"
    }
}
