package org.kert0n.medappserver.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("registration")
data class RegistrationProperties(
    @field:NotBlank
    val secret: String,
    @field:Valid
    val throttle: Throttle = Throttle()
) {
    data class Throttle(
        val window: Duration = Duration.ofMinutes(5),
        @field:Min(1)
        val maxSuccessfulRegistrations: Int = 3
    )
}
