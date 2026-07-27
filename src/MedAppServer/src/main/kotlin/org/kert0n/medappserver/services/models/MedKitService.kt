package org.kert0n.medappserver.services.models

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.api.MedKitSummaryDTO
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.services.security.SecurityService
import org.kert0n.medappserver.services.security.hashToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class MedKitService(
    private val medKitRepository: MedKitRepository,
    private val securityService: SecurityService,
    private val logger: Logger = LoggerFactory.getLogger(MedKitService::class.java),
    private val medKitTokenCache: Cache<String, UUID>,
    private val userService: UserService
) {
    @Transactional
    fun createNew(userId: UUID): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val user: User = userService.findById(userId)
        val medKit = medKitRepository.save(MedKit())
        user.medKits.add(medKit)
        medKit.users.add(user)
        return medKit
    }


    @Transactional(readOnly = true)
    fun findById(medKitId: UUID): MedKit {
        logger.debug("Finding medkit by ID: {}", medKitId)
        return medKitRepository.findByIdOrNull(medKitId) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "MedKit not found"
        )
    }

    @Transactional(readOnly = true)
    fun findByIdForUser(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        return medKitRepository.findByIdAndUserId(medKitId, userId) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Medkit not found or user has insufficient privileges"
        )

    }

    /**
     * Аптечка с загруженными участниками.
     *
     * Отдельный метод, а не флаг у [findByIdForUser]: форма выборки — часть контракта, и по
     * имени должно быть видно, что участники уже здесь и обращение к `users` не превратится
     * в запрос.
     */
    @Transactional(readOnly = true)
    fun findByIdForUserWithUsers(medKitId: UUID, userId: UUID): MedKit =
        medKitRepository.findByIdAndUsersIdWithUsers(medKitId, userId)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Medkit not found or user has insufficient privileges"
            )

    /**
     * Аптечка со всем, что нужно для удаления: участники, препараты и их планы.
     *
     * Граф здесь не оптимизация, а условие корректности: удаление идёт каскадом по
     * `medKit.drugs`, а по неинициализированной коллекции каскад проходит впустую.
     */
    @Transactional(readOnly = true)
    fun findByIdForUserForDeletion(medKitId: UUID, userId: UUID): MedKit =
        medKitRepository.findByIdAndUserIdForDeletion(medKitId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cant find deletion target")

    /** Удалить аптечку. Каскад по drugs и users — на стороне маппинга. */
    @Transactional
    fun delete(medKit: MedKit) = medKitRepository.delete(medKit)

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<MedKit> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKitRepository.findByUserId(userId)
    }

    @Transactional(readOnly = true)
    fun findMedKitSummaries(userId: UUID): Set<MedKitSummaryDTO> {
        logger.debug("Finding medkit summaries for user: {}", userId)
        return medKitRepository.findMedKitSummariesByUserId(userId)
    }

    @Transactional(readOnly = true)
    fun generateMedKitShareKey(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit $medKitId by user: $userId")
        // Checking access
        findByIdForUser(medKitId, userId)
        val key = securityService.generateKey(16)
        // Cache only a hash so the raw share key is never stored server-side.
        medKitTokenCache[hashToken(key)] = medKitId
        return key
    }

    @Transactional
    fun addUserToMedKit(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Adding user {} to medkit {}", userId, medKitId)
        val medKit = findById(medKitId)
        val user = userService.findById(userId)
        if (medKit.users.contains(user)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "User already exists")
        }
        medKit.users.add(user)
        user.medKits.add(medKit)
        return medKitRepository.save(medKit)
    }

    @Transactional
    fun joinMedKitByKey(key: String, userId: UUID): MedKit {
        val hashedKey = hashToken(key)
        val medKitId = medKitTokenCache.getOrNull(hashedKey) ?: throw ResponseStatusException(
            HttpStatus.NOT_FOUND, "Share key has expired or does not exist"
        )
        return addUserToMedKit(medKitId, userId)
    }

    @Transactional
    fun removeUserFromMedKit(medKit: MedKit, user: User) {
        logger.debug("Removing user {} from medkit {}", user.id, medKit.id)

        user.medKits.remove(medKit)
        medKit.users.remove(user)
        if (medKit.users.isEmpty()) {
            // This user was the last
            logger.debug("No users left in medkit {}, deleting", medKit.id)
            medKitRepository.delete(medKit)
        } else {
            medKitRepository.save(medKit)
        }
    }


}
