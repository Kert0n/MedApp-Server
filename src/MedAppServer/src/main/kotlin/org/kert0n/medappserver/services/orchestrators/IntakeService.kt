package org.kert0n.medappserver.services.orchestrators

import com.sksamuel.aedile.core.Cache
import org.kert0n.medappserver.controller.UsingDTO
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.services.security.hashToken
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

/**
 * Приём препарата с защитой от повторного применения.
 *
 * Живёт отдельно от [UsingService] по одной причине: списание должно быть закоммичено до того,
 * как результат попадёт в кеш. Если писать в кеш внутри транзакционного `recordIntake`, запись
 * останется даже при откате — и повтор получит результат операции, которой не было. Поэтому
 * метод здесь **не** транзакционный и вызывает транзакционный сервис как единицу работы.
 *
 * В контроллере этого быть не должно: решение «применять или отдать прежний результат» — это
 * поведение сервера, а не деталь HTTP-слоя. Контроллер только разбирает запрос и отдаёт ответ.
 */
@Service
class IntakeService(
    private val usingService: UsingService,
    @Qualifier("intakeResultsCache") private val intakeResultsCache: Cache<String, IntakeOutcome>
) {

    private val logger = LoggerFactory.getLogger(IntakeService::class.java)

    fun record(
        userId: UUID,
        drugId: UUID,
        quantityConsumed: BigDecimal,
        intakeId: UUID
    ): IntakeOutcome {
        // Ключ включает пользователя: intakeId генерирует клиент, и без этого один клиент мог
        // бы получить результат чужой операции, подобрав совпадающий идентификатор.
        //
        // Хеш, а не пара значений: в кеше не остаётся ни идентификатора пользователя, ни
        // клиентского intakeId в читаемом виде — тем же приёмом закрыты ключи по адресу
        // клиента. Пара сворачивается в один хеш, поэтому и разделитель не нужен.
        val key = hashToken("$userId$intakeId")

        intakeResultsCache.getOrNull(key)?.let { seen ->
            logger.debug("Повторный intakeId, отдаю прежний результат без повторного списания")
            return seen
        }

        val plan = usingService.recordIntake(userId, drugId, quantityConsumed)
            ?.let { usingService.toUsingDTO(it) }

        // Ошибки не кешируются сознательно: отказ выводится из состояния и при повторе
        // повторится сам, а вот закешированный отказ переживёт исправление причины.
        val outcome = IntakeOutcome(plan)
        intakeResultsCache.put(key, outcome)
        return outcome
    }
}

/**
 * Результат приёма для кеша идемпотентности.
 *
 * Обёртка нужна потому, что приём может завершиться удалением плана, и тогда результат — его
 * отсутствие, то есть null. Caffeine null-значения не принимает, а различать «не видели такой
 * intakeId» и «видели, и план исчез» обязательно: без этого повтор вернул бы 404 вместо
 * первого ответа.
 */
data class IntakeOutcome(val plan: UsingDTO?)
