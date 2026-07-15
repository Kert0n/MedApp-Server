package org.kert0n.medappserver.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.Cache
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service
import java.util.*
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

@Service
class CacheService(
    @Value($$"${medkit.share.termInMinutes}") private val medKitShareTerm: Long,
    @Value($$"${registration.throttle.window:5m}") private val registrationWindow: Duration,
) {
    init {
        require(medKitShareTerm > 0) { "medkit.share.termInMinutes must be positive" }
        require(!registrationWindow.isZero && !registrationWindow.isNegative) {
            "registration.throttle.window must be positive"
        }
    }

    // Storage for medkit share tokens
    @Bean
    fun medKitTokenCache(): Cache<String, UUID> = Caffeine.newBuilder()
        .expireAfterWrite(medKitShareTerm.minutes)
        .maximumSize(10_000)
        .asCache()

    @Bean
    // Storage for successful registration attempt
    fun successfulRegistrationsCache(): Cache<String, AtomicInteger> = Caffeine.newBuilder()
        .expireAfterWrite(registrationWindow.toMillis(), TimeUnit.MILLISECONDS)
        .maximumSize(10_000)
        .asCache()
}
