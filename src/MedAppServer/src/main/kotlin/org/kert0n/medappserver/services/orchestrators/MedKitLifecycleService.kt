package org.kert0n.medappserver.services.orchestrators

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.db.repository.UsingRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.services.security.hashToken
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class MedKitLifecycleService(
    private val medKits: MedKitRepository,
    private val drugs: DrugRepository,
    private val plans: UsingRepository,
    private val users: UserRepository,
    private val security: SecurityService,
    private val medKitTokenCache: Cache<String, UUID>
) {

    @Transactional
    fun create(userId: UUID): UUID {
        val user = requireUser(userId)
        val medKit = medKits.save(MedKit())
        user.medKits.add(medKit)
        medKit.users.add(user)
        return medKit.id
    }

    @Transactional(readOnly = true)
    fun createInvitation(userId: UUID, medKitId: UUID): String {
        medKits.findAccessible(medKitId, userId) ?: notFound()
        val key = security.generateKey(16)
        medKitTokenCache[hashToken(key)] = medKitId
        return key
    }

    @Transactional
    fun join(userId: UUID, key: String): UUID {
        val medKitId = medKitTokenCache.getOrNull(hashToken(key))
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Share key has expired or does not exist"
            )
        if (medKits.findAccessible(medKitId, userId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "User is already a member")
        }

        val medKit = medKits.findByIdOrNull(medKitId) ?: notFound()
        val user = requireUser(userId)
        user.medKits.add(medKit)
        medKit.users.add(user)
        return medKitId
    }

    @Transactional
    fun leave(userId: UUID, medKitId: UUID) {
        lockAccessible(userId, medKitId)

        if (medKits.countMembers(medKitId) == 1L) {
            drugs.lockAllByMedKitIdOrderById(medKitId)
            medKits.deleteLockedById(medKitId)
        } else {
            plans.deleteByUserIdAndMedKitId(userId, medKitId)
            medKits.deleteMembership(medKitId, userId)
        }
    }

    @Transactional
    fun delete(userId: UUID, medKitId: UUID, transferToMedKitId: UUID? = null) {
        lockAccessible(userId, medKitId)
        drugs.lockAllByMedKitIdOrderById(medKitId)

        if (transferToMedKitId != null) {
            if (transferToMedKitId == medKitId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Target medkit must differ")
            }
            val target = medKits.findAccessibleWithUsers(transferToMedKitId, userId) ?: notFound()
            plans.deleteByMedKitIdAndUserIdNotIn(medKitId, target.users.mapTo(mutableSetOf()) { it.id })
            drugs.moveAllToMedKit(medKitId, transferToMedKitId)
        }
        medKits.deleteLockedById(medKitId)
    }

    private fun lockAccessible(userId: UUID, medKitId: UUID): MedKit =
        medKits.findAccessibleForUpdate(medKitId, userId) ?: notFound()

    private fun requireUser(userId: UUID) =
        users.findByIdOrNull(userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")

    private fun notFound(): Nothing =
        throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Medkit not found or user has insufficient privileges"
        )
}
