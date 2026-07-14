package org.kert0n.medappserver.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@Validated
@ConfigurationProperties("authentication")
data class AuthenticationProperties(
    val term: Duration = Duration.ofMinutes(10),
    @field:NotBlank
    val issuer: String = "medapp-server",
    @field:NotBlank
    val audience: String = "medapp-api"
)
