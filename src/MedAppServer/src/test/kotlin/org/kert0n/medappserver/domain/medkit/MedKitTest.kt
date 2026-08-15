package org.kert0n.medappserver.domain.medkit

import org.junit.jupiter.api.Test
import org.kert0n.medappserver.domain.error.MedKitNotFound
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MedKitTest {
    @Test
    fun `join is idempotent and leave distinguishes last member`() {
        val owner = UUID.randomUUID()
        val participant = UUID.randomUUID()
        val medKit = MedKit.create(owner)

        assertTrue(medKit.join(participant))
        assertFalse(medKit.join(participant))
        assertFalse(medKit.leave(participant).deleteMedKit)
        assertTrue(medKit.leave(owner).deleteMedKit)
    }

    @Test
    fun `inaccessible membership is reported as missing medkit`() {
        val medKit = MedKit.create(UUID.randomUUID())

        assertFailsWith<MedKitNotFound> { medKit.requireAccess(UUID.randomUUID()) }
    }
}
