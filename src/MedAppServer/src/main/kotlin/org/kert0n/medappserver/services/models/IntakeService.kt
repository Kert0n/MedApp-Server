package org.kert0n.medappserver.services.models

import com.sksamuel.aedile.core.Cache
import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.domain.IntakeAlreadyRecorded
import org.kert0n.medappserver.domain.IntakeOutcome
import org.kert0n.medappserver.services.IntakeReceipt
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Приём с обещанием идемпотентности.
 *
 * Идентификатор приёма приходит от клиента, и `PUT` этим обещает: повтор того же запроса не
 * списывает второй раз. Само списание идемпотентным не бывает — два вызова уменьшат остаток
 * дважды, — а повтор при обрыве связи для мобильного клиента обычное дело. Поэтому обещание
 * держится здесь, поверх команды, а не внутри неё.
 *
 * Транзакции у этого класса намеренно нет. Результат кладётся в хранилище только после того,
 * как команда закоммитилась: внутри транзакции запись означала бы, что откат оставил после
 * себя «успешный» ответ, который повтор честно вернёт.
 *
 * Разделение обязанностей строгое и его стоит держать в голове при чтении: кеш отвечает за
 * повтор одного и того же запроса, версия агрегата — за две разные одновременные команды.
 * Подменить одно другим нельзя. Два одновременных запроса с одним идентификатором оба
 * промахнутся мимо кеша, и разойтись им не даст именно версия: второй получит 409.
 */
@Service
class IntakeService(
    private val drugs: DrugService,
    private val receipts: Cache<UUID, IntakeReceipt>
) {

    private val logger = LoggerFactory.getLogger(IntakeService::class.java)

    fun record(
        intakeId: UUID,
        userId: UUID,
        drugId: UUID,
        quantityConsumed: BigDecimal,
        expectedVersion: Long
    ): IntakeOutcome {
        val fingerprint = fingerprintOf(userId, drugId, quantityConsumed, expectedVersion)

        receipts.getOrNull(intakeId)?.let { recorded ->
            if (recorded.fingerprint != fingerprint) throw IntakeAlreadyRecorded()
            logger.debug("Replaying intake {} for user {}", intakeId, userId)
            return recorded.outcome
        }

        val outcome = drugs.recordIntake(userId, drugId, quantityConsumed, expectedVersion)
        receipts[intakeId] = IntakeReceipt(fingerprint, outcome)
        return outcome
    }

    /**
     * Что делает два запроса «одним и тем же».
     *
     * Версия входит в отпечаток вместе с остальным: честный повтор шлёт тот самый заголовок,
     * что и первая попытка, поэтому совпадёт. А запрос, отличающийся хоть чем-то, повтором не
     * является — и выполнить его под уже занятым идентификатором нельзя.
     *
     * Количество сравнивается по значению, а не по написанию: `1.0` и `1.00` — одно и то же
     * списание, и объявлять их разными командами значило бы придираться к форме записи.
     */
    private fun fingerprintOf(
        userId: UUID,
        drugId: UUID,
        quantityConsumed: BigDecimal,
        expectedVersion: Long
    ): String = "$userId|$drugId|${quantityConsumed.stripTrailingZeros().toPlainString()}|$expectedVersion"
}
