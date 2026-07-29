package org.kert0n.medappserver.services.security

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.db.model.User
import org.springframework.security.core.Authentication
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.io.encoding.Base64

/**
 * SHA-256 в Base64 — то, чем в этом проекте закрываются ключи кешей.
 *
 * Функция верхнего уровня, а не метод сервиса: она без состояния и без зависимостей, а
 * пользователи у неё есть за пределами аутентификации — например кеш идемпотентности приёма.
 * Ради одного хеша тащить в оркестратор приёма зависимость от [SecurityService] незачем.
 *
 * Простой дайджест сознательно. Ключ с секретом был бы механикой ни за чем: записи живут
 * минуты, кеш в памяти и умирает вместе с процессом, а кто читает эту память — видит и сами
 * значения.
 */
fun hashToken(token: String): String =
    Base64.encode(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))

@Service
class SecurityService(
    private val passwordEncoder: PasswordEncoder,
    private val encoder: JwtEncoder,
    private val decoder: JwtDecoder,
    @Value($$"${authentication.termInMinutes}") private val authenticationTerm: Long,
    @Value($$"${registration.timeout.BanNumber}") private val registrationNumber: Long,
    @Value($$"${authentication.throttle.maxAttempts:20}") private val maxLoginAttempts: Int,
    // Both caches are Cache<String, Int>; qualify them so resolution does not rely on
    // parameter-name matching.
    @Qualifier("successfulRegistrationsCache") private val successfulRegistrationsCache: Cache<String, Int>,
    @Qualifier("loginAttemptsCache") private val loginAttemptsCache: Cache<String, Int>
) {

    private val secureRandom = SecureRandom()

    private companion object {
        /** URL-safe без выравнивания: см. [generateKey]. */
        val KEY_ENCODING: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    }

    /**
     * Случайный ключ: [size] байт из [SecureRandom], закодированные Base64.
     *
     * Алфавит URL-safe и без выравнивания. Обычный Base64 давал три неудобства сразу, и все
     * три видны в выдаче: 32 байта не кратны трём, поэтому **каждый** ключ заканчивался на
     * `=`; в алфавите есть `+` и `/`, которые приходится экранировать в URL и в оболочке.
     * Ключ ходит в заголовке Basic, в ссылках-приглашениях и через копирование руками — там
     * это мешает, а стойкости не добавляет.
     *
     * Энтропия не меняется: те же 8 * size бит, просто записанные без хвоста выравнивания.
     * Длина при size = 32 — 43 символа вместо 44.
     *
     * Старые ключи продолжают работать: в базе лежит их bcrypt-хеш, а не сам ключ, и форма
     * записи на проверку не влияет.
     */
    fun generateKey(size: Int): String =
        KEY_ENCODING.encode(ByteArray(size).also { secureRandom.nextBytes(it) })
    fun check(raw: String, hashedPassword: String): Boolean = passwordEncoder.matches(raw, hashedPassword)
    fun hashPassword(rawPassword: String): String = passwordEncoder.encode(rawPassword)!!

    /**
     * Compares two shared secrets without leaking how much of a candidate was correct.
     *
     * `==` on strings stops at the first differing character, so response time depends on
     * the length of the matching prefix. Both sides are hashed first because
     * [MessageDigest.isEqual] itself returns early when lengths differ — hashing makes both
     * inputs the same length, so neither the content nor the length of the expected secret
     * shows through.
     */
    fun secretsMatch(candidate: String, expected: String): Boolean = MessageDigest.isEqual(
        hashToken(candidate).toByteArray(StandardCharsets.UTF_8),
        hashToken(expected).toByteArray(StandardCharsets.UTF_8)
    )


    /**
     * Токен по аутентификации запроса.
     *
     * Каст принципала живёт здесь, а не в контроллере: тип принципала задаёт
     * SecurityConfiguration вместе с UserDetailsService, и знать о нём — работа этого
     * пакета. Пока каст стоял в AuthController, контроллер импортировал сущность БД ради
     * одной строки.
     */
    fun generateToken(authentication: Authentication): String =
        generateToken(authentication.principal as User)

    fun generateToken(user: User, termInMinutes: Long = authenticationTerm): String {
        val now = Instant.now()
        return encoder.encode(
            JwtEncoderParameters.from(
                JwtClaimsSet.builder().run {
                    issuedAt(now)
                    expiresAt(now.plus(termInMinutes, ChronoUnit.MINUTES))
                    subject(user.id.toString())
                    build()
                }
            )
        ).tokenValue
    }

    /** Cache key for a client address, so no address is used as a key verbatim. */
    private fun addressCacheKey(clientAddress: String): String = hashToken(clientAddress)

    /**
     * Tracks successful registrations per client address to throttle automated signups.
     *
     * Сравнение строгое: [registrationNumber] — это сколько регистраций разрешено, а не
     * после скольких начинать отказывать. Раньше стояло `<=`, и лимит на деле был на
     * единицу больше объявленного; послабление было записано осознанно, но правило «не чаще
     * трёх раз с адреса» его больше не допускает.
     *
     * Одна неточность осталась и оставлена намеренно: проверка идёт до инкремента, поэтому
     * одновременные запросы могут проскочить вместе. Точный счёт потребовал бы атомарного
     * счётчика с возвратом при неудачной регистрации; на защите от ботов это ничего не
     * меняет, а машинерии добавляет. Не «чинить» без причины.
     *
     * Окно отсчитывается от последней **успешной** регистрации: счётчик живёт в кеше с
     * expireAfterWrite, а отклонённые попытки его не трогают. То есть после исчерпания
     * лимита адрес ждёт полное окно, но не может продлевать блокировку сам себе.
     */
    fun validateRequest(ip: String): Boolean =
        (successfulRegistrationsCache.getOrNull(addressCacheKey(ip)) ?: 0) < registrationNumber

    /**
     * Whether another token request from this address may proceed to authentication.
     *
     * Unlike [validateRequest] this uses a strict comparison: it guards a real cost
     * (a bcrypt verification per request) rather than merely deterring bots.
     */
    fun isLoginAllowed(clientAddress: String): Boolean =
        (loginAttemptsCache.getOrNull(addressCacheKey(clientAddress)) ?: 0) < maxLoginAttempts

    /**
     * Counts a token request. Every attempt is counted, not just failures: a legitimate
     * client asks for a token roughly once per token lifetime, so the limit is far above
     * normal use, and counting all attempts also covers an attacker holding valid
     * credentials.
     */
    fun recordLoginAttempt(clientAddress: String) {
        val key = addressCacheKey(clientAddress)
        loginAttemptsCache[key] = (loginAttemptsCache.getOrNull(key) ?: 0) + 1
    }

    // Creates or increases successful registration attempt from a client address
    fun registerIncrease(ip: String) {
        val key = addressCacheKey(ip)
        val current = successfulRegistrationsCache.getOrNull(key)
        if (current == null) {
            successfulRegistrationsCache.put(key, 1)
        } else {
            successfulRegistrationsCache[key] = current + 1
        }
    }
}