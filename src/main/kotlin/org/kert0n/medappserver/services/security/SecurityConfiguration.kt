package org.kert0n.medappserver.services.security

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.proc.SecurityContext
import jakarta.servlet.DispatcherType
import org.kert0n.medappserver.controller.ProblemResponseWriter
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
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter


@Configuration
@EnableWebSecurity
class SecurityConfiguration(
    private val rsaKeys: RsaKeyProperties,
    private val problems: ProblemResponseWriter,
) {

    /**
     * Отказ аутентификации отвечает problem+json, как и остальные ошибки.
     *
     * Без WWW-Authenticate намеренно: это мобильный API, и окно браузера здесь только мешало бы.
     */
    private fun unauthorized() = AuthenticationEntryPoint { _, response, _ ->
        problems.write(response, HttpStatus.UNAUTHORIZED)
    }

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
     * HTTP Basic is accepted **only** where a token is issued: anywhere else the long-lived
     * registration key could be replayed per request and the short token lifetime would buy
     * nothing.
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
            .securityMatcher("/v1/auth/token")
            // Защита от CSRF выключена законно: учётные данные приезжают заголовком, который
            // ставит клиент, а не кукой, которую браузер подставил бы сам. Подделывать нечего.
            // Держится это не на комментарии: `StatelessAuthTest` запрещает куки в проде.
            .csrf { csrf -> csrf.disable() }
            .addFilterBefore(
                LoginThrottleFilter(securityService, problems),
                BasicAuthenticationFilter::class.java
            )
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { configurer ->
                configurer.authenticationEntryPoint(unauthorized())
            }
            // Точку входа нужно задать и здесь: неверные учётные данные отвергает сам
            // BasicAuthenticationFilter — своей точкой входа, а не общей, и тело было бы пустым.
            .httpBasic { basic -> basic.authenticationEntryPoint(unauthorized()) }
            .build()
    }

    @Bean
    @Order(2)
    fun filterChain(httpSecurity: HttpSecurity): SecurityFilterChain {
        return httpSecurity
            // По той же причине, что и в цепочке выдачи токена: токен приезжает заголовком
            // `Authorization`, сессии нет, кук нет — неявной аутентификации, на которую и
            // рассчитан CSRF, здесь просто не существует.
            .csrf { csrf -> csrf.disable() }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(
                        // Registration only. /v1/auth/token is handled by the Basic chain
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
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { configurer ->
                configurer.authenticationEntryPoint(unauthorized())
            }
            .oauth2ResourceServer { oauth2 ->
                oauth2.jwt { jwt ->
                    jwt.decoder(jwtDecoder())
                }
            }
            .build()
    }


}