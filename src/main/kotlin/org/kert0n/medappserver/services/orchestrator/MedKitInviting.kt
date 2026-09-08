package org.kert0n.medappserver.services.orchestrator

import com.sksamuel.aedile.core.Cache
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Invitation
import org.kert0n.medappserver.services.aggregate.MedKitAccessService
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Выдача ключа приглашения.
 *
 * Ключ не доменное понятие, а секрет с временем жизни, поэтому живёт рядом с тем, кто его
 * выдаёт, а не в агрегате.
 *
 * Режим доступа — совместимый: приглашение ничего не пишет в базу и состав участников не
 * трогает. Удержание нужно ровно затем, чтобы право пригласить проверялось и оставалось верным
 * до конца команды.
 */
@Service
class MedKitInviting(
    private val access: MedKitAccessService,
    private val securityService: SecurityService,
    private val medKitTokenCache: Cache<String, Invitation>
) {

    private val logger = LoggerFactory.getLogger(MedKitInviting::class.java)

    @Transactional(propagation = MANDATORY)
    fun invite(medKitId: Uuid, userId: Uuid): String {
        access.holdContentAccess(setOf(medKitId), userId)
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)

        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = Invitation(medKitId, userId)
        return key
    }
}
