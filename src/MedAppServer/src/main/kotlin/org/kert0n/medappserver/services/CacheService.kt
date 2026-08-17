package org.kert0n.medappserver.services

import com.github.benmanes.caffeine.cache.Caffeine
import com.sksamuel.aedile.core.Cache
import com.sksamuel.aedile.core.asCache
import com.sksamuel.aedile.core.expireAfterWrite
import java.util.*
import org.kert0n.medappserver.domain.Intake
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
    @Value($$"${sync.journal.windowInMinutes:30}") private val syncJournalWindow: Long,
) {
    // Medkit share tokens. maximumSize is a hard cap, not a hint: under enough concurrent
    // sharing a key can be evicted before medkit.share.termInMinutes elapses, so the validity
    // window is an upper bound. Acceptable — ask for a new key — but never document it as
    // absolute.
    @Bean
    fun medKitTokenCache(): Cache<String, UUID> = Caffeine.newBuilder()
        .expireAfterWrite(medKitShareTerm.minutes)
        .maximumSize(10_000)
        .asCache()

    // Successful registrations per client address.
    @Bean
    fun successfulRegistrationsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(registrationTimeOut.seconds)
        .maximumSize(10_000)
        .asCache()

    /**
     * Журнал синхронизаций: помнит запрос ровно столько, сколько нужно повтору.
     *
     * В памяти процесса и с временем жизни, а не в таблице, — намеренно: приём это самое личное,
     * что здесь есть, и хранить дневник «кто когда сколько выпил» ради технической задачи «не
     * списать дважды» нельзя. Цена названа в описании операции: после перезапуска сервера повтор
     * старого запроса спишет второй раз.
     */
    @Bean
    fun intakeJournalCache(): Cache<UUID, Intake> = Caffeine.newBuilder()
        .expireAfterWrite(syncJournalWindow.minutes)
        .maximumSize(100_000)
        .asCache()

    // Token requests per client address: every one costs a bcrypt verification, so an
    // unauthenticated caller could otherwise burn CPU for free.
    @Bean
    fun loginAttemptsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(loginThrottleWindow.seconds)
        .maximumSize(10_000)
        .asCache()
}