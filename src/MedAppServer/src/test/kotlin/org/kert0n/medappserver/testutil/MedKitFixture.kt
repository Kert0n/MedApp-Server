package org.kert0n.medappserver.testutil

import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.MedKitSummary
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.orchestrators.MedKitLifecycleService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Fixture API for integration and story setup.
 *
 * Production reads and lifecycle commands use dedicated services; this adapter may access
 * repositories directly only while arranging test data.
 */
@Component
class MedKitFixture(
    private val lifecycle: MedKitLifecycleService,
    private val medKits: MedKitRepository,
    private val users: UserRepository
) {

    @Transactional
    fun createNew(userId: UUID): MedKit =
        medKits.findById(lifecycle.create(userId)).orElseThrow()

    @Transactional(readOnly = true)
    fun findById(medKitId: UUID): MedKit =
        medKits.findByIdOrNull(medKitId) ?: notFound()

    @Transactional(readOnly = true)
    fun findByIdForUser(medKitId: UUID, userId: UUID): MedKit =
        medKits.findAccessible(medKitId, userId) ?: notFound()

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<MedKit> =
        medKits.findAllById(medKits.findIdsByUserId(userId))

    @Transactional(readOnly = true)
    fun findMedKitSummaries(userId: UUID): List<MedKitSummary> =
        medKits.findMedKitSummariesByUserId(userId)

    fun generateMedKitShareKey(medKitId: UUID, userId: UUID): String =
        lifecycle.createInvitation(userId, medKitId)

    @Transactional
    fun joinMedKitByKey(key: String, userId: UUID): MedKit =
        medKits.findById(lifecycle.join(userId, key)).orElseThrow()

    @Transactional
    fun addUserToMedKit(medKitId: UUID, userId: UUID): MedKit {
        val medKit = medKits.findByIdOrNull(medKitId) ?: notFound()
        val user = users.findByIdOrNull(userId) ?: notFound()
        if (medKit.users.any { it.id == userId }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User already exists")
        }
        medKit.users.add(user)
        user.medKits.add(medKit)
        return medKit
    }

    fun removeUserFromMedKit(medKit: MedKit, user: User) =
        lifecycle.leave(user.id, medKit.id)

    private fun notFound(): Nothing =
        throw ResponseStatusException(HttpStatus.NOT_FOUND, "MedKit not found")
}
