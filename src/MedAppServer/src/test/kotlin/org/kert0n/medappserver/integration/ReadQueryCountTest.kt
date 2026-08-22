package org.kert0n.medappserver.integration

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.application.MedKitApplicationService
import org.kert0n.medappserver.services.application.ReservationApplicationService
import org.kert0n.medappserver.services.application.UserApplicationService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.QueryCount
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager

/**
 * Чтение стоит одинаково при любом объёме.
 *
 * Утверждается **равенство между размерами**, а не точное число: константу пришлось бы подгонять
 * после каждой безобидной правки, и гейт превратился бы в формальность. Равенство ловит ровно то,
 * ради чего он заведён, — рост числа запросов вместе с данными.
 *
 * Постоянный перерасход («всегда пять вместо трёх») равенством не ловится, поэтому фактические
 * числа печатаются: их видно в отчёте и в ревью.
 *
 * Класс намеренно **без** `@Transactional`: замер открывает свою транзакцию, а оснастка — свои,
 * иначе фикстура попала бы в запись вместе с измеряемым обращением.
 */
@PostgresIntegrationTest
class ReadQueryCountTest {

    @Autowired private lateinit var users: UserApplicationService
    @Autowired private lateinit var medKits: MedKitApplicationService
    @Autowired private lateinit var reservations: ReservationApplicationService
    @Autowired private lateinit var dbHelper: DatabaseTestHelper
    @Autowired private lateinit var transactionManager: PlatformTransactionManager

    /**
     * Снимок пользователя: три чтения на весь ответ, сколько бы аптечек у человека ни было.
     *
     * Это и есть маршрут `GET /v1/users/me`, которым клиент синхронизируется целиком.
     */
    @Test
    fun `снимок пользователя не растёт с числом аптечек`() {
        val loner = userWithKits("snapshot-1", kits = 1, drugsEach = 2)
        val hoarder = userWithKits("snapshot-25", kits = 25, drugsEach = 2)

        val forOne = count("снимок, 1 аптечка") { users.snapshot(loner) }
        val forMany = count("снимок, 25 аптечек") { users.snapshot(hoarder) }

        assertEquals(forOne, forMany, "снимок обязан стоить одинаково при любом числе аптечек")
    }

    /**
     * Пустой снимок дешевле, а не дороже.
     *
     * Равенства здесь не требуем: без упаковок читать брони не по чему, и два запроса просто не
     * делаются — это короткое замыкание в `snapshotsOf`, а не экономия на правилах. Замерено:
     * ноль аптечек — 2 запроса, пять — 4.
     *
     * Требуем обратного: пустота не должна обходиться **дороже**. Дороже она стала бы ровно
     * тогда, когда кто-нибудь начнёт спрашивать базу в цикле по пустому списку.
     */
    @Test
    fun `пустой снимок не дороже наполненного`() {
        val newcomer = userWithKits("snapshot-0", kits = 0, drugsEach = 0)
        val settled = userWithKits("snapshot-5", kits = 5, drugsEach = 2)

        val empty = count("снимок, 0 аптечек") { users.snapshot(newcomer) }
        val filled = count("снимок, 5 аптечек") { users.snapshot(settled) }

        assertTrue(empty <= filled, "пустой снимок обошёлся дороже наполненного: $empty против $filled")
    }

    /**
     * Содержимое аптечки: сама аптечка, упаковки и брони на них — постоянным числом чтений.
     *
     * Сравниваются два непустых размера. Пустая аптечка меряется отдельно и по другому правилу:
     * читать брони там не по чему, и запросов честно меньше.
     */
    @Test
    fun `чтение аптечки не растёт с числом упаковок`() {
        val owner = dbHelper.freshUser("kit-content").id
        val small = kitWith(owner, drugs = 10)
        val large = kitWith(owner, drugs = 100)

        val forSmall = count("аптечка, 10 упаковок") { medKits.read(small, owner) }
        val forLarge = count("аптечка, 100 упаковок") { medKits.read(large, owner) }

        assertEquals(forSmall, forLarge, "содержимое аптечки читается за постоянное число запросов")
    }

    /** Пустая аптечка не дороже полной — по той же причине, что и пустой снимок. */
    @Test
    fun `пустая аптечка не дороже полной`() {
        val owner = dbHelper.freshUser("kit-empty").id
        val empty = kitWith(owner, drugs = 0)
        val full = kitWith(owner, drugs = 10)

        val forEmpty = count("аптечка, 0 упаковок") { medKits.read(empty, owner) }
        val forFull = count("аптечка, 10 упаковок") { medKits.read(full, owner) }

        assertTrue(forEmpty <= forFull, "пустая аптечка обошлась дороже полной: $forEmpty против $forFull")
    }

    /** Свои брони по всем упаковкам — один запрос, не зависящий от их числа. */
    @Test
    fun `чтение своих броней не растёт с их числом`() {
        val ascetic = dbHelper.freshUser("claims-1").id
        reserveFor(ascetic, claims = 1)
        val planner = dbHelper.freshUser("claims-250").id
        reserveFor(planner, claims = 250)

        val forOne = count("брони, 1 штука") { reservations.ofUser(ascetic) }
        val forMany = count("брони, 250 штук") { reservations.ofUser(planner) }

        assertEquals(forOne, forMany, "свои брони читаются одним запросом при любом их числе")
    }

    // ── Замер и фикстуры ─────────────────────────────────────────────────────────

    /**
     * Число операторов одного обращения; оно же уходит в отчёт.
     *
     * Пустой замер — провал: обращение, не сходившее в базу, ничего не доказывает, а зелёный
     * такой гейт означал бы, что мерили не то. Проверку делает сам помощник.
     */
    private fun count(what: String, read: () -> Unit): Int =
        QueryCount.of(transactionManager, what, read)

    private fun userWithKits(name: String, kits: Int, drugsEach: Int): Uuid {
        val user = dbHelper.freshUser(name)
        repeat(kits) {
            val kit = dbHelper.freshMedKit(user.id)
            repeat(drugsEach) { dbHelper.freshDrug(kit.id, 10.0) }
        }
        return user.id
    }

    private fun kitWith(owner: Uuid, drugs: Int): Uuid {
        val kit = dbHelper.freshMedKit(owner)
        repeat(drugs) { dbHelper.freshDrug(kit.id, 10.0) }
        return kit.id
    }

    private fun reserveFor(user: Uuid, claims: Int) {
        val kit = dbHelper.freshMedKit(user)
        repeat(claims) {
            val drug = dbHelper.freshDrug(kit.id, 10.0)
            dbHelper.reserve(user, drug.id, qty(1.0))
        }
    }
}
