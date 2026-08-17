package org.kert0n.medappserver.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.Cache
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import java.util.*
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.stereotype.Service

@Service
class CacheService(
    @Value($$"${medkit.share.termInMinutes}") private val medKitShareTerm: Long,
    @Value($$"${registration.timeout.InSeconds}") private val registrationTimeOut: Long,
    @Value($$"${authentication.throttle.windowInSeconds:300}") private val loginThrottleWindow: Long,
    @Value($$"${intake.idempotency.windowInMinutes:15}") private val intakeIdempotencyWindow: Long,
) {
    // Storage for medkit share tokens.
    //
    // maximumSize is a hard cap, not a hint: under enough concurrent sharing a key can be
    // evicted before medkit.share.termInMinutes elapses, so the advertised validity window
    // is an upper bound rather than a guarantee. Acceptable — the caller simply asks for a
    // new key — but do not document the TTL as absolute.
    @Bean
    fun medKitTokenCache(): Cache<String, UUID> = Caffeine.newBuilder()
        .expireAfterWrite(medKitShareTerm.minutes)
        .maximumSize(10_000)
        .asCache()

    @Bean
    // Storage for successful registration attempt
    fun successfulRegistrationsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(registrationTimeOut.seconds)
        .maximumSize(10_000)
        .asCache()

    // Token requests per client address. Every /auth/login costs a bcrypt verification,
    // so an unauthenticated caller could otherwise burn CPU for free.
    @Bean
    fun loginAttemptsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(loginThrottleWindow.seconds)
        .maximumSize(10_000)
        .asCache()

    /**
     * Результаты приёмов по идентификатору, который придумал клиент.
     *
     * Ограничения назвать прямо, потому что они видны наружу: хранилище живёт в памяти
     * процесса, не переживает перезапуск и не работает при нескольких экземплярах — повтор,
     * попавший на другой узел, будет выполнен заново. Окно тоже верхняя граница, а не
     * гарантия: при достаточном потоке запись вытеснится по `maximumSize` раньше срока.
     *
     * От потерянного обновления это не защищает и защищать не должно — тем занята версия
     * агрегата. Кеш отвечает ровно за одно: повтор того же запроса не списывает второй раз.
     */
    @Bean
    fun intakeResultsCache(): Cache<UUID, IntakeReceipt> = Caffeine.newBuilder()
        .expireAfterWrite(intakeIdempotencyWindow.minutes)
        .maximumSize(10_000)
        .asCache()
}

/**
 * Что вернул первый приём с этим идентификатором.
 *
 * Отпечаток нужен, чтобы отличить повтор от подмены: тот же идентификатор с другим
 * содержимым — не повтор, а вторая команда под чужим именем, и она отвергается.
 */
data class IntakeReceipt(
    val fingerprint: String,
    val outcome: org.kert0n.medappserver.domain.IntakeOutcome
)