package org.kert0n.medappserver.services.models

import com.sksamuel.aedile.core.Cache
import java.util.UUID
import org.kert0n.medappserver.db.store.MedKitStore
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.MedKitOverview
import org.kert0n.medappserver.domain.MedKitRef
import org.kert0n.medappserver.domain.NotAMember
import org.kert0n.medappserver.services.security.SecurityService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Аптечка: жизненный цикл участников.
 *
 * Правила членства живут в `domain.medkit.MedKit`; здесь — транзакция, проверка доступа и
 * ключ приглашения. Ключ не доменное понятие: это одноразовый секрет с временем жизни, и
 * место ему рядом с тем, кто умеет его выдавать и хранить.
 */
@Service
class MedKitService(
    private val medKits: MedKitStore,
    private val securityService: SecurityService,
    private val medKitTokenCache: Cache<String, UUID>
) {

    private val logger = LoggerFactory.getLogger(MedKitService::class.java)

    @Transactional
    fun create(userId: UUID): MedKit {
        logger.debug("Creating new medkit for user: {}", userId)
        val medKit = MedKit(members = setOf(userId))
        medKits.insert(medKit)
        return medKit
    }

    /** Аптечка или `null`, если её нет. */
    @Transactional(readOnly = true)
    fun findById(medKitId: UUID): MedKit? = medKits.findById(medKitId)

    /** Аптечка или 404. */
    @Transactional(readOnly = true)
    fun requireById(medKitId: UUID): MedKit = findById(medKitId) ?: throw NotAMember()

    /** Аптечка, доступная вызывающему, или 404 — недоступная и несуществующая неотличимы. */
    @Transactional(readOnly = true)
    fun requireAccessible(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Finding medkit {} for user {}", medKitId, userId)
        val medKit = requireById(medKitId)
        medKit.requireMember(userId)
        return medKit
    }

    /**
     * Аптечки участника — имя и версия, без состава.
     *
     * Состав не показывает ни один ответ, а раньше он всё равно поднимался — запросом на
     * каждую аптечку. Версия нужна: с ней снимок сразу годится как основа для выхода.
     */
    @Transactional(readOnly = true)
    fun refsOfUser(userId: UUID): List<MedKitRef> {
        logger.debug("Finding all medkits for user: {}", userId)
        return medKits.findRefsOfUser(userId)
    }

    @Transactional(readOnly = true)
    fun overviews(userId: UUID): List<MedKitOverview> {
        logger.debug("Finding medkit overviews for user: {}", userId)
        return medKits.overviewsOf(userId)
    }

    @Transactional(readOnly = true)
    fun invite(medKitId: UUID, userId: UUID): String {
        logger.debug("Sharing medkit {} by user: {}", medKitId, userId)
        requireAccessible(medKitId, userId)
        val key = securityService.generateKey(16)
        // Кешируется только хеш: сырой ключ приглашения на сервере не хранится.
        medKitTokenCache[securityService.hashToken(key)] = medKitId
        return key
    }

    /**
     * Вступление предусловия не требует.
     *
     * Терять здесь нечего: команда не переписывает прочитанное состояние, а добавляет к нему
     * себя, и чужое вступление, случившееся между чтением и записью, ничему не мешает. Версия
     * аптечки при этом всё равно продвигается — иначе чужой выход, решавшийся по составу без
     * новичка, прошёл бы как ни в чём не бывало.
     */
    @Transactional
    fun join(medKitId: UUID, userId: UUID): MedKit {
        logger.debug("Adding user {} to medkit {}", userId, medKitId)
        return medKits.save(requireById(medKitId).join(userId))
    }

    @Transactional
    fun joinByInvitation(key: String, userId: UUID): MedKit {
        val medKitId = medKitTokenCache.getOrNull(securityService.hashToken(key))
            ?: throw NotAMember()
        return join(medKitId, userId)
    }

    /**
     * Выход участника.
     *
     * Возвращает `null`, когда вышел последний: аптечка удалена вместе с содержимым. Планы
     * выходящего в препаратах этой аптечки — забота оркестратора, они лежат в чужом агрегате.
     *
     * Предусловие обязательно, и здесь оно не формальность: решение «я последний» принимается
     * по составу, который клиент прочитал, а вместе с этим решением уходит вся аптечка с
     * содержимым. Версия — единственное, что отличает «последний» от «последним только
     * казался».
     */
    @Transactional
    fun leave(medKitId: UUID, userId: UUID, expectedVersion: Long): MedKit? {
        logger.debug("Removing user {} from medkit {}", userId, medKitId)
        val left = requireAccessible(medKitId, userId).requireVersion(expectedVersion).leave(userId)

        if (left == null) {
            medKits.delete(medKitId)
            return null
        }
        return medKits.save(left)
    }

    /**
     * Удаление аптечки.
     *
     * Доступ проверяется здесь, а не только у вызывающего: команда, удаляющая аптечку по
     * одному идентификатору, рано или поздно будет вызвана и без проверки.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, expectedVersion: Long) {
        requireAccessible(medKitId, userId).requireVersion(expectedVersion)
        medKits.delete(medKitId)
    }
}
