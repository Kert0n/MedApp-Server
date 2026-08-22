package org.kert0n.medappserver.services.aggregate

import kotlin.test.*
import kotlin.uuid.Uuid
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

@PostgresIntegrationTest
@Transactional
class MedKitServiceTest {

    @Autowired

    private lateinit var medKitStore: MedKitStore


    @Autowired
    private lateinit var medKitService: MedKitService
    @Autowired
    private lateinit var userService: UserService
    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ── create ──

    @Test
    fun `create creates medkit with user`() {
        val alice = dbHelper.freshUser("alice")
        val medKit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertNotNull(medKit.id)
        assertTrue(medKit.members.contains(alice.id))
    }

    // ── get ──

    @Test
    fun `get throws NOT_FOUND for non-existent medkit`() {
        assertThrows<DomainRuleViolated> {
            medKitService.get(Uuid.random(), Uuid.random())
        }
    }

    @Test
    fun `get throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitService.get(kit.id, eve.id)
        }
    }

    // ── allOfUser ──

    @Test
    fun `allOfUser returns medkits of user`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.create(alice.id)
        medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertEquals(2, medKitService.allOfUser(alice.id).size)
    }

    
    @Test
    fun `allOfUser returns the kit with its members`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.create(alice.id)
        dbHelper.flushAndClear()

        // Аптечка приходит агрегатом: счётчик участников получается из неё самой.
        val mine = medKitService.allOfUser(alice.id).single()
        assertEquals(setOf(alice.id), mine.members)
    }

    // ── invite / joinByInvitation ──

    @Test
    fun `joinByInvitation adds user and invalidates key`() {
        val owner = dbHelper.freshUser("owner")
        val joiner = dbHelper.freshUser("joiner")
        val kit = medKitService.create(owner.id)
        dbHelper.flushAndClear()

        val key = medKitService.invite(medKitService.get(kit.id, owner.id), owner.id)
        medKitService.joinByInvitation(key, joiner.id)
        dbHelper.flushAndClear()

        val joinerKits = medKitService.allOfUser(joiner.id)
        assertEquals(1, joinerKits.size)
        assertEquals(kit.id, joinerKits.first().id)

        // Ключ одноразовый: после вступления он уже не действует.
        assertFailsWith<DomainRuleViolated> {
            medKitService.joinByInvitation(key, joiner.id)
        }
    }

    @Test
    fun `joinByInvitation fails for missing key`() {
        val user = dbHelper.freshUser("user")

        assertFailsWith<DomainRuleViolated> {
            medKitService.joinByInvitation("missing-key", user.id)
        }
    }

    // ── join ──

    @Test
    fun `join adds second user`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        dbHelper.join(kit.id, alice.id, bob.id)
        dbHelper.flushAndClear()

        assertEquals(1, medKitService.allOfUser(bob.id).size)
    }

    @Test
    fun `join throws when user is already a member`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            dbHelper.join(kit.id, alice.id, alice.id)
        }
    }

    // ── leave ──

    @Test
    fun `leave keeps medkit when other users remain`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        dbHelper.join(kit.id, alice.id, bob.id)
        dbHelper.flushAndClear()

        medKitService.leave(medKitService.get(kit.id, bob.id), bob.id, dbHelper.medKitVersion(kit.id))
        dbHelper.flushAndClear()

        assertNotNull(medKitService.get(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.get(kit.id, bob.id)
        }
    }

    @Test
    fun `leave deletes medkit when last user leaves`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        medKitService.leave(medKitService.get(kit.id, alice.id), alice.id, dbHelper.medKitVersion(kit.id))
        dbHelper.flushAndClear()

        assertNull(dbHelper.medKit(kit.id))
    }
}
