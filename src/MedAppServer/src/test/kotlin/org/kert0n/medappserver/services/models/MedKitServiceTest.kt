package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.store.MedKitStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional
import org.kert0n.medappserver.domain.DomainRuleViolated
import java.util.*
import kotlin.test.*

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
    fun `createNew creates medkit with user`() {
        val alice = dbHelper.freshUser("alice")
        val medKit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertNotNull(medKit.id)
        assertTrue(medKit.members.contains(alice.id))
    }

    // ── findById ──

    @Test
    fun `findById throws NOT_FOUND for non-existent medkit`() {
        assertThrows<DomainRuleViolated> {
            medKitService.findById(UUID.randomUUID())
        }
    }

    // ── findByIdForUser ──

    @Test
    fun `findByIdForUser throws when user has no access`() {
        val alice = dbHelper.freshUser("alice")
        val eve = dbHelper.freshUser("eve")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, eve.id)
        }
    }

    // ── findAllByUser ──

    @Test
    fun `findAllByUser returns medkits for user`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.createNew(alice.id)
        medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertEquals(2, medKitService.findAllByUser(alice.id).size)
    }

    // ── findMedKitSummaries ──

    @Test
    fun `findMedKitSummaries returns summaries for user`() {
        val alice = dbHelper.freshUser("alice")
        medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        val summaries = medKitService.overviews(alice.id)
        assertEquals(1, summaries.size)
    }

    // ── generateMedKitShareKey / joinMedKitByKey ──

    @Test
    fun `joinMedKitByKey adds user and invalidates key`() {
        val owner = dbHelper.freshUser("owner")
        val joiner = dbHelper.freshUser("joiner")
        val kit = medKitService.createNew(owner.id)
        dbHelper.flushAndClear()

        val key = medKitService.generateMedKitShareKey(kit.id, owner.id)
        medKitService.joinMedKitByKey(key, joiner.id)
        dbHelper.flushAndClear()

        val joinerKits = medKitService.findAllByUser(joiner.id)
        assertEquals(1, joinerKits.size)
        assertEquals(kit.id, joinerKits.first().id)

        // Key should be invalidated after use
        assertFailsWith<DomainRuleViolated> {
            medKitService.joinMedKitByKey(key, joiner.id)
        }
    }

    @Test
    fun `joinMedKitByKey fails for missing key`() {
        val user = dbHelper.freshUser("user")

        assertFailsWith<DomainRuleViolated> {
            medKitService.joinMedKitByKey("missing-key", user.id)
        }
    }

    // ── addUserToMedKit ──

    @Test
    fun `addUserToMedKit adds second user`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        medKitService.addUserToMedKit(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertEquals(1, medKitService.findAllByUser(bob.id).size)
    }

    @Test
    fun `addUserToMedKit throws when user already exists`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        assertFailsWith<DomainRuleViolated> {
            medKitService.addUserToMedKit(kit.id, alice.id)
        }
    }

    // ── removeUserFromMedKit ──

    @Test
    fun `removeUserFromMedKit keeps medkit when other users remain`() {
        val alice = dbHelper.freshUser("alice")
        val bob = dbHelper.freshUser("bob")
        val kit = medKitService.createNew(alice.id)
        medKitService.addUserToMedKit(kit.id, bob.id)
        dbHelper.flushAndClear()

        medKitService.removeUserFromMedKit(kit.id, bob.id)
        dbHelper.flushAndClear()

        assertNotNull(medKitService.requireAccessible(kit.id, alice.id))
        assertFailsWith<DomainRuleViolated> {
            medKitService.requireAccessible(kit.id, bob.id)
        }
    }

    @Test
    fun `removeUserFromMedKit deletes medkit when last user leaves`() {
        val alice = dbHelper.freshUser("alice")
        val kit = medKitService.createNew(alice.id)
        dbHelper.flushAndClear()

        medKitService.removeUserFromMedKit(kit.id, alice.id)
        dbHelper.flushAndClear()

        assertNull(medKitStore.findById(kit.id))
    }
}
