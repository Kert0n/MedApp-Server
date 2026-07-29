package org.kert0n.medappserver.services.models

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
 * Test fixture adapter for legacy story setup.
 *
 * Production code has no generic MedKit model service: reads and lifecycle commands go
 * through their dedicated services. Older stories keep this adapter only to avoid obscuring
 * their business assertions with fixture construction.
 */
@Component
class MedKitService(
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
    fun findMedKitSummaries(userId: UUID): Set<MedKitSummary> =
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
