package org.kert0n.medappserver.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.Cache
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import org.kert0n.medappserver.application.model.IntakeCacheEntry
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Configuration
class CacheConfiguration(
    @Value($$"${medkit.share.termInMinutes}") private val medKitShareTerm: Long,
    @Value($$"${registration.timeout.InSeconds}") private val registrationTimeOut: Long,
    @Value($$"${authentication.throttle.windowInSeconds:300}") private val loginThrottleWindow: Long,
    @Value($$"${intake.idempotency.windowInMinutes:15}") private val intakeIdempotencyWindow: Long,
) {
    @Bean
    fun medKitTokenCache(): Cache<String, UUID> = cache(medKitShareTerm.minutes, 10_000)

    @Bean
    fun successfulRegistrationsCache(): Cache<String, Int> = cache(registrationTimeOut.seconds, 10_000)

    @Bean
    fun intakeRecordsCache(): Cache<String, IntakeCacheEntry> = cache(intakeIdempotencyWindow.minutes, 50_000)

    @Bean
    fun loginAttemptsCache(): Cache<String, Int> = cache(loginThrottleWindow.seconds, 10_000)

    private fun <K : Any, V : Any> cache(ttl: kotlin.time.Duration, maximumSize: Long): Cache<K, V> =
        Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maximumSize).asCache()
}
