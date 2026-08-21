package org.kert0n.medappserver.services.aggregate

import java.util.*
import kotlin.test.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.DomainRuleViolated
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@SpringBootTest
@ActiveProfiles("test")
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

    // ── createNew ──

    @Test
    fun `create creates medkit with user`() {
        val alice = dbHelper.freshUser("alice")
        val medKit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertNotNull(medKit.id)
        assertTrue(medKit.members.contains(alice.id))
    }

    // ── findById ──

    @Test
    fun `requireById throws NOT_FOUND for non-existent medkit`() {
        assertThrows<DomainRuleViolated> {
            medKitService.requireById(UUID.randomUUID())
        }
    }

    // ── findByIdForUser ──

    @Test
    fun `requireAccessible throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, eve.id)
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

    // ── findMedKitSummaries ──

    @Test
    fun `allOfUser returns the kit with its members`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.create(alice.id)
        dbHelper.flushAndClear()

        // Аптечка приходит агрегатом: счётчик участников получается из неё самой.
        val mine = medKitService.allOfUser(alice.id).single()
        assertEquals(setOf(alice.id), mine.members)
    }

    // ── generateMedKitShareKey / joinMedKitByKey ──

    @Test
    fun `joinByInvitation adds user and invalidates key`() {
        val owner = dbHelper.freshUser("owner")
        val joiner = dbHelper.freshUser("joiner")
        val kit = medKitService.create(owner.id)
        dbHelper.flushAndClear()

        val key = medKitService.invite(kit.id, owner.id)
        medKitService.joinByInvitation(key, joiner.id)
        dbHelper.flushAndClear()

        val joinerKits = medKitService.allOfUser(joiner.id)
        assertEquals(1, joinerKits.size)
        assertEquals(kit.id, joinerKits.first().id)

        // Key should be invalidated after use
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

    // ── addUserToMedKit ──

    @Test
    fun `join adds second user`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        medKitService.join(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertEquals(1, medKitService.allOfUser(bob.id).size)
    }

    @Test
    fun `join throws when user is already a member`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitService.join(kit.id, alice.id)
        }
    }

    // ── removeUserFromMedKit ──

    @Test
    fun `leave keeps medkit when other users remain`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.create(alice.id)
        medKitService.join(kit.id, bob.id)
        dbHelper.flushAndClear()

        medKitService.leave(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.requireAccessible(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, bob.id)
        }
    }

    @Test
    fun `leave deletes medkit when last user leaves`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.create(alice.id)
        dbHelper.flushAndClear()

        medKitService.leave(kit.id, alice.id)
        dbHelper.flushAndClear()

        assertNull(medKitStore.findById(kit.id))
    }
}
