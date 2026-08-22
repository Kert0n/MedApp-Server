package org.kert0n.medappserver.services.orchestrator

import com.sksamuel.aedile.core.Cache
import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Intake
import org.kert0n.medappserver.domain.StaleVersion
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Съеденное и новая бронь применяются вместе.
 *
 * Клиент копит изменения офлайн и присылает итог, а не поток событий. Двумя запросами это
 * послать нельзя: порядок между ними не гарантирован, и между списанием и уменьшением брони
 * остаётся окно, в котором срабатывают уведомления «лекарства мало».
 *
 * Съеденное — дельта: она коммутативна, и складывать её можно в любом порядке. Бронь —
 * абсолютное значение: она не накопленное событие, а решение владельца, и клиент задаёт её
 * целиком.
 */
@Service
class DrugSynchronisation(
    private val reservationService: ReservationService,
    private val disposal: DrugDisposal,
    private val syncJournalCache: Cache<Uuid, Intake>
) {

    private val logger = LoggerFactory.getLogger(DrugSynchronisation::class.java)

    @Transactional(propagation = MANDATORY)
    fun apply(syncId: Uuid, drug: Drug, userId: Uuid, request: SyncRequest): Drug? {
        val intake = Intake(syncId, userId, drug, request.consumed, request.reservation?.amount)
        if (alreadyApplied(intake)) return drug

        // `null` — списание опустошило пачку, и её больше нет вместе с бронями на неё.
        val left = if (request.consumed != null) {
            // Списание есть — значит упаковку пишут, и версию к ней предъявить обязаны.
            // Списания нет — писать нечего, и предъявлять не к чему: часть про бронь несёт
            // свою версию сама.
            asSyncConflict { disposal.consume(drug, request.consumed, request.drugVersion ?: throw StaleSyncVersion()) }
        } else {
            drug
        }
        applyReservation(left, userId, request.reservation)

        rememberAfterCommit(intake)
        return left
    }

    /**
     * Повтор того же запроса ничего не делает.
     *
     * Это не приём кеширования, а тождество приёма: с таким идентификатором он либо записан,
     * либо нет. Тот же идентификатор с другим содержимым — не повтор, а другая команда, и
     * подтверждать её как выполненную нельзя.
     */
    private fun alreadyApplied(intake: Intake): Boolean {
        val applied = syncJournalCache.getOrNull(intake.id) ?: return false
        if (!applied.sameAs(intake)) throw ConflictingSync()

        logger.debug("Sync {} already applied, repeating the answer", intake.id)
        return true
    }

    /**
     * Бронь после списания.
     *
     * Если списание опустошило пачку, делать нечего: бронь ушла вместе с ней. Это не ошибка —
     * клиент офлайн и не знал, что пачка кончится.
     */
    private fun applyReservation(drug: Drug?, userId: Uuid, wanted: ReservationPart?) {
        if (drug == null || wanted == null) return

        val snapshot = reservationService.snapshotOn(drug, userId)
        // Версии нет — брони ещё не было, сверяться не с чем: за неизменность отвечает
        // прочитанное только что, и от одновременной записи предикат защищает всё равно.
        val stated = wanted.version ?: snapshot.version

        asSyncConflict {
            if (snapshot.mine == null) {
                reservationService.create(drug, userId, wanted.amount, stated)
            } else {
                reservationService.changeTo(userId, drug.id, wanted.amount, stated)
            }
        }
    }

    /**
     * Отказ по версии здесь — 409, а не 412.
     *
     * Версии едут телом только в синхронизации: запрос меняет два состояния сразу, и разложить
     * их по одному предусловию запроса нельзя. Раз предусловием запроса версия не была, то и
     * «предусловие не выполнено» ответить не о чем.
     */
    private fun <T> asSyncConflict(work: () -> T): T =
        try {
            work()
        } catch (stale: StaleVersion) {
            throw StaleSyncVersion(stale)
        }

    /**
     * Журнал пишется после коммита.
     *
     * Записать по ходу команды значило бы пережить откат: повтор получил бы подтверждение того,
     * чего в базе нет, а клиент вычистил бы офлайн-очередь.
     */
    private fun rememberAfterCommit(intake: Intake) {
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() {
                    syncJournalCache.put(intake.id, intake)
                }
            }
        )
    }

    data class SyncRequest(
        val consumed: BigDecimal?,
        val drugVersion: Long?,
        val reservation: ReservationPart?
    )

    data class ReservationPart(val amount: BigDecimal, val version: Long?)
}

/** Тот же идентификатор с другим содержимым: это не повтор, а другая команда. */
class ConflictingSync : RuntimeException("A different request was already applied under this identifier")

/** Версия из тела не сошлась: конфликт при записи, а не невыполненное предусловие запроса. */
class StaleSyncVersion(cause: Throwable? = null) :
    RuntimeException("The state has changed since the version stated in the body", cause)
