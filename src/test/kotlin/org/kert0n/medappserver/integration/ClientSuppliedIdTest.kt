package org.kert0n.medappserver.integration

import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.domain.DrugAlreadyExists
import org.kert0n.medappserver.domain.MedKitAlreadyExists
import org.kert0n.medappserver.services.aggregate.MedKitService
import org.kert0n.medappserver.services.aggregate.NewDrug
import org.kert0n.medappserver.services.orchestrator.DrugPlacement
import org.kert0n.medappserver.testutil.ApiRoutes
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext

/**
 * Идентификатор придумывает клиент, поэтому потерянный ответ больше не теряет ресурс.
 *
 * Раньше `id` рождался на сервере и приходил только в ответе: не доехал ответ — клиент не знает
 * адреса созданного и не может повторить запрос, не заведя второй такой же. Теперь адрес известен
 * до отправки, а повтор упирается в первичный ключ и отвечает конфликтом.
 */
@PostgresIntegrationTest
class ClientSuppliedIdTest {

    @Autowired private lateinit var context: WebApplicationContext
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var medKitService: MedKitService
    @Autowired private lateinit var placement: DrugPlacement
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(SecurityMockMvcConfigurers.springSecurity())
            .build()
    }

    // ── Аптечка ──────────────────────────────────────────────────────────────────

    @Test
    fun `аптечка заводится под идентификатором клиента и по нему же читается`() {
        val owner = dbHelper.freshUser("supplied-kit")
        val kitId = Uuid.random()

        createMedKit(owner.id, kitId)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(kitId.toString()))

        mockMvc.perform(get(ApiRoutes.medKit(kitId)).with(asUser(owner.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(kitId.toString()))
    }

    @Test
    fun `повтор создания аптечки отвечает конфликтом и второй не заводит`() {
        val owner = dbHelper.freshUser("supplied-kit-repeat")
        val kitId = Uuid.random()

        createMedKit(owner.id, kitId).andExpect(status().isCreated)
        createMedKit(owner.id, kitId).andExpect(status().isConflict)

        assertEquals(1, dbHelper.medKitCount(kitId), "повтор завёл вторую аптечку")
    }

    /**
     * Чужой идентификатор — тоже конфликт, и это осознанно.
     *
     * Ответ сообщает, что идентификатор занят, но не чей он и что в нём. Перебрать UUIDv4 нельзя,
     * а тот, кто прислал не им придуманный идентификатор, знает его откуда-то ещё.
     */
    @Test
    fun `чужой идентификатор аптечки отвергается конфликтом`() {
        val owner = dbHelper.freshUser("supplied-kit-owner")
        val outsider = dbHelper.freshUser("supplied-kit-eve")
        val kitId = Uuid.random()

        createMedKit(owner.id, kitId).andExpect(status().isCreated)
        createMedKit(outsider.id, kitId).andExpect(status().isConflict)

        mockMvc.perform(get(ApiRoutes.medKit(kitId)).with(asUser(outsider.id)))
            .andExpect(status().isNotFound)
    }

    // ── Упаковка ─────────────────────────────────────────────────────────────────

    @Test
    fun `упаковка заводится под идентификатором клиента и по нему же читается`() {
        val owner = dbHelper.freshUser("supplied-drug")
        val kit = dbHelper.freshMedKit(owner.id)
        val drugId = Uuid.random()

        createDrug(owner.id, kit.id, drugId)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser(owner.id)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.drug.id").value(drugId.toString()))
    }

    @Test
    fun `повтор создания упаковки отвечает конфликтом и второй не заводит`() {
        val owner = dbHelper.freshUser("supplied-drug-repeat")
        val kit = dbHelper.freshMedKit(owner.id)
        val drugId = Uuid.random()

        createDrug(owner.id, kit.id, drugId).andExpect(status().isCreated)
        createDrug(owner.id, kit.id, drugId).andExpect(status().isConflict)

        assertNotNull(dbHelper.drug(drugId))
        assertEquals(1, dbHelper.drugIdsIn(kit.id).size, "повтор завёл вторую упаковку")
    }

    @Test
    fun `чужой идентификатор упаковки отвергается конфликтом`() {
        val owner = dbHelper.freshUser("supplied-drug-owner")
        val outsider = dbHelper.freshUser("supplied-drug-eve")
        val ownerKit = dbHelper.freshMedKit(owner.id)
        val outsiderKit = dbHelper.freshMedKit(outsider.id)
        val drugId = Uuid.random()

        createDrug(owner.id, ownerKit.id, drugId).andExpect(status().isCreated)
        createDrug(outsider.id, outsiderKit.id, drugId).andExpect(status().isConflict)

        mockMvc.perform(get(ApiRoutes.drug(drugId)).with(asUser(outsider.id)))
            .andExpect(status().isNotFound)
    }

    // ── Учётная запись ───────────────────────────────────────────────────────────

    /**
     * Потерянный ответ регистрации больше не оставляет лишней учётки.
     *
     * Логин и пароль клиент знает до отправки. Повтор упирается в первичный ключ, а токен по тем
     * же данным подтверждает, что учётка своя, — заводить вторую незачем.
     */
    @Test
    fun `повтор регистрации отвечает конфликтом, а учётка остаётся своей`() {
        val login = Uuid.random()
        val password = "k".repeat(43)

        register(login, password).andExpect(status().isCreated)
        register(login, password).andExpect(status().isConflict)

        mockMvc.perform(post(ApiRoutes.TOKEN).with(httpBasic(login.toString(), password)))
            .andExpect(status().isOk)
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Int::class.java, UUID.fromString(login.toString())),
            "повтор завёл вторую учётку"
        )
    }

    // ── Гонка ────────────────────────────────────────────────────────────────────

    /**
     * Конфликт приходит от ключа, а не от предварительного чтения.
     *
     * Чтение «а нет ли уже такого» пропустило бы одновременный повтор: обе стороны увидели бы
     * пусто и обе записали. Здесь второй вставке физически не дают пройти, пока первая не
     * закоммитится, — ожидание подтверждается PostgreSQL, а не задержкой потока.
     */
    @Test
    fun `одновременное создание аптечки с одним идентификатором даёт один успех и один конфликт`() {
        val owner = dbHelper.freshUser("supplied-kit-race")
        val kitId = Uuid.random()

        val outcome = raceOnSameId { medKitService.create(kitId, owner.id) }

        assertEquals(1, outcome.count { it == null }, "успешных должно быть ровно одно: $outcome")
        assertEquals(
            1, outcome.count { it is MedKitAlreadyExists },
            "проигравший обязан получить доменный конфликт, а не ошибку БД: $outcome"
        )
        assertEquals(1, dbHelper.medKitCount(kitId), "гонка завела вторую аптечку")
    }

    @Test
    fun `одновременное создание упаковки с одним идентификатором даёт один успех и один конфликт`() {
        val owner = dbHelper.freshUser("supplied-drug-race")
        val kit = dbHelper.freshMedKit(owner.id)
        val drugId = Uuid.random()
        val command = NewDrug(drugId, "Aspirin", BigDecimal("10"), dbHelper.unit().id)

        val outcome = raceOnSameId { placement.place(command, kit.id, owner.id) }

        assertEquals(1, outcome.count { it == null }, "успешных должно быть ровно одно: $outcome")
        assertEquals(
            1, outcome.count { it is DrugAlreadyExists },
            "проигравший обязан получить доменный конфликт, а не ошибку БД: $outcome"
        )
        assertEquals(listOf(drugId), dbHelper.drugIdsIn(kit.id), "гонка завела вторую упаковку")
    }

    /**
     * Обе стороны стартуют, вторая упирается в ключ, первой разрешают закоммититься.
     *
     * Порядок задан явно: без него тест иногда проверял бы два последовательных запроса и
     * зеленел бы, ничего не доказав.
     */
    private fun raceOnSameId(create: () -> Unit): List<Throwable?> {
        val firstWrote = CountDownLatch(1)
        val allowFirstCommit = CountDownLatch(1)
        val secondBackend = ArrayBlockingQueue<Int>(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        create()
                        firstWrote.countDown()
                        assertTrue(allowFirstCommit.await(10, TimeUnit.SECONDS), "вторая сторона не дошла до ключа")
                    }
                }.exceptionOrNull()
            }
            assertTrue(firstWrote.await(10, TimeUnit.SECONDS), "первая сторона не записала")

            val second = pool.submit<Throwable?> {
                runCatching {
                    TransactionTemplate(transactionManager).execute {
                        secondBackend.put(jdbc.queryForObject("SELECT pg_backend_pid()", Int::class.java)!!)
                        create()
                    }
                }.exceptionOrNull()
            }
            awaitDatabaseLock(secondBackend.poll(10, TimeUnit.SECONDS) ?: error("вторая сторона не начала транзакцию"))
            allowFirstCommit.countDown()

            return listOf(first, second).map { it.get(30, TimeUnit.SECONDS)?.rootCause() }
        } finally {
            allowFirstCommit.countDown()
            pool.shutdownNow()
        }
    }

    /** Ждёт именно блокировку в PostgreSQL: вставка по занятому ключу ждёт коммита первой. */
    private fun awaitDatabaseLock(backendPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val blocked = jdbc.queryForObject(
                "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, backendPid
            ) == true
            if (blocked) return
            Thread.onSpinWait()
        }
        error("вторая вставка не встала в очередь за первичным ключом")
    }

    private fun Throwable.rootCause(): Throwable = generateSequence(this) { it.cause }.last()

    // ── Внутреннее ──────────────────────────────────────────────────────────────

    private fun createMedKit(userId: Uuid, medKitId: Uuid) = mockMvc.perform(
        post(ApiRoutes.MED_KITS).with(asUser(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"id":"$medKitId"}""")
    )

    private fun createDrug(userId: Uuid, medKitId: Uuid, drugId: Uuid) = mockMvc.perform(
        post(ApiRoutes.drugsOf(medKitId)).with(asUser(userId))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """{"id":"$drugId","name":"Aspirin","quantity":"10.0",""" +
                    """"quantityUnitId":"${dbHelper.unit().id}"}"""
            )
    )

    /** Свой адрес: квота регистраций общая на контекст, и чужие тесты её не должны расходовать. */
    private fun register(login: Uuid, password: String) = mockMvc.perform(
        post(ApiRoutes.REGISTER)
            .with { request -> request.apply { remoteAddr = "192.0.2.143" } }
            .header("X-Registration-Token", "test-secret")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"login":"$login","password":"$password"}""")
    )

    private fun asUser(userId: Uuid) = jwt().jwt { it.subject(userId.toString()) }
}
