package org.kert0n.medappserver.services.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter


@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val rsaKeys: RsaKeyProperties,
) {

    @Bean
    fun jwtDecoder(): JwtDecoder = NimbusJwtDecoder.withPublicKey(rsaKeys.publicKey).build()


    @Bean
    fun jwtEncoder(): JwtEncoder {
        val jwk = RSAKey.Builder(rsaKeys.publicKey)
            .privateKey(rsaKeys.privateKey)
            .build()
        val jwks: JWKSource<SecurityContext?> = ImmutableJWKSet<SecurityContext?>(JWKSet(jwk))
        return NimbusJwtEncoder(jwks)
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder()
    }

    /**
     * HTTP Basic is accepted **only** where a token is issued.
     *
     * It used to apply to every endpoint, which meant the long-lived registration key
     * could be replayed on every request and the short token lifetime bought nothing.
     */
    @Bean
    @Order(1)
    fun tokenIssuingFilterChain(
        httpSecurity: HttpSecurity,
        // Injected as a method parameter, not into the constructor: SecurityService needs
        // the JwtEncoder/JwtDecoder beans defined here, so a constructor dependency would
        // be a cycle.
        securityService: SecurityService
    ): SecurityFilterChain {
        return httpSecurity
            .securityMatcher("/v1/auth/login")
            .csrf { csrf -> csrf.disable() }
            .addFilterBefore(
                LoginThrottleFilter(securityService),
                BasicAuthenticationFilter::class.java
            )
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { configurer ->
                // Deliberately no WWW-Authenticate challenge: this is a mobile API, a
                // browser credential prompt would be noise.
                configurer.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .httpBasic { }
            .build()
    }

    @Bean
    @Order(2)
    fun filterChain(httpSecurity: HttpSecurity): SecurityFilterChain {
        return httpSecurity
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        // Registration only. /v1/auth/login is handled by the Basic chain
                        // above and must not be open here.
                        "/v1/auth/register",
                        "/swagger",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/v3/api-docs.yaml",
                        // Liveness only. /actuator/** would open /env and heapdump the
                        // moment anyone widens management.endpoints.web.exposure.include.
                        "/actuator/health",
                        "/actuator/health/**",
                    )
                    .permitAll()
                    .dispatcherTypeMatchers(
                        DispatcherType.ERROR,
                        DispatcherType.FORWARD
                    )
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }
            // BEWARE OF THIS! XCSS possible if code changes
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { configurer ->
                configurer
                    .authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder())
                }
            }
            .build()
    }


}
