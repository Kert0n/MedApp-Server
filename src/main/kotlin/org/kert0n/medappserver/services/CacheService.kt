package org.kert0n.medappserver.services

import com.github.benmanes.caffeine.cache.Caffeine
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.Invitation
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
    @Value($$"${sync.journal.termInMinutes:1440}") private val syncJournalTerm: Long,
) {
    // Ключи приглашения. `maximumSize` — жёсткий предел, а не пожелание: при достаточном числе
    // одновременных приглашений ключ вытеснится раньше, чем истечёт `medkit.share.termInMinutes`,
    // поэтому срок жизни — верхняя граница. Это приемлемо (попросить новый ключ), но обещать его
    // как точный нельзя.
    @Bean
    fun medKitTokenCache(): Cache<String, Invitation> = Caffeine.newBuilder()
        .expireAfterWrite(medKitShareTerm.minutes)
        .maximumSize(10_000)
        .asCache()

    // Удачные регистрации с одного адреса клиента.
    @Bean
    fun successfulRegistrationsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(registrationTimeOut.seconds)
        .maximumSize(10_000)
        .asCache()

    /**
     * Журнал синхронизаций: что уже применено, по придуманному клиентом идентификатору.
     *
     * Не в базе: приём слишком персонален для таблицы (часть 1.2). Граница названа честно —
     * журнал живёт в памяти процесса, значит не переживает рестарт и не работает между
     * экземплярами. Повтор после рестарта спишет второй раз; таблица `drug_sync_receipts`
     * записана долгом и заводится, когда экземпляров станет больше одного.
     */
    @Bean
    fun syncJournalCache(): Cache<Uuid, Intake> = Caffeine.newBuilder()
        .expireAfterWrite(syncJournalTerm.minutes)
        .maximumSize(100_000)
        .asCache()

    // Запросы токена с одного адреса: каждый стоит проверки bcrypt, и без счётчика
    // неаутентифицированный вызывающий жёг бы процессор даром.
    @Bean
    fun loginAttemptsCache(): Cache<String, Int> = Caffeine.newBuilder()
        .expireAfterWrite(loginThrottleWindow.seconds)
        .maximumSize(10_000)
        .asCache()
}