package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

/**
 * Заведение и правка плана лечения.
 *
 * Оркестратор, потому что операция стоит на стыке двух агрегатов: план принадлежит
 * [UsingService], но инвариант «сумма планов не больше остатка» — свойство препарата, и
 * прочитать его можно только взяв строку препарата под блокировку. Читать чужой агрегат
 * модельный сервис не вправе, а без блокировки инвариант не держится: двое участников общей
 * аптечки, заводящие планы на один препарат, читают одну и ту же сумму и оба проходят
 * проверку.
 *
 * Удаление плана здесь отсутствует намеренно и живёт в [UsingService]: оно сумму только
 * уменьшает, нарушить инвариант не может и обходится своим репозиторием.
 *
 * Существование этого бина целиком держится на пессимистичной блокировке. Если проект когда
 * -нибудь перейдёт на `@Version` с принудительным инкрементом при правке плана, обе операции
 * вернутся в [UsingService]: всё остальное, что им нужно, достаётся по графу — `Using.drug`
 * и `Using.user` объявлены EAGER.
 */
@Service
class TreatmentPlanService(
    private val drugService: DrugService,
    private val userService: UserService,
    private val usingService: UsingService
) {

    private val logger = LoggerFactory.getLogger(TreatmentPlanService::class.java)

    @Transactional
    fun create(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): Using {
        logger.debug("Creating treatment for user {} and drug {}", userId, drugId)

        val user = userService.findById(userId)
        val drug = drugService.findByIdForUserForUpdate(drugId, userId)

        if (usingService.findByUserAndDrugOrNull(userId, drugId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "using already exists for this user and drug")
        }

        if (plannedAmount > drug.availableQuantity) {
            // No amounts in the message or the log line: both end up somewhere readable,
            // and a drug plus a quantity is the kind of detail this server does not hand out.
            logger.warn("Rejected treatment plan: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        val saved = usingService.save(
            Using(
                usingKey = UsingKey(userId, drugId),
                user = user,
                drug = drug,
                plannedAmount = plannedAmount
            )
        )
        // Обе стороны связи, а не только владеющая. План сохраняется репозиторием, но
        // Drug.usings объявлена с CascadeType.ALL и orphanRemoval — если не добавить, коллекция
        // до конца транзакции показывает состояние без нового плана, и весь код, который на неё
        // опирается (каскадное удаление, перенос препарата между аптечками), работает по
        // устаревшему набору.
        drug.usings.add(saved)
        return saved
    }

    @Transactional
    fun update(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): Using {
        logger.debug("Updating using for user {} and drug {}", userId, drugId)

        // Блокировка первым действием, и её результат используется дальше. Раньше он
        // отбрасывался, а количества читались через using.drug — тот же экземпляр из
        // контекста, но по коду этого не видно, и выглядело так, будто запрос сделан ради
        // побочного эффекта.
        val drug = drugService.findByIdForUserForUpdate(drugId, userId)
        val using = usingService.findByUserAndDrug(userId, drugId)
        // Свой план исключается из суммы: он же и переписывается.
        val availableQuantity = drug.availableQuantity + using.plannedAmount

        if (plannedAmount > availableQuantity) {
            logger.warn("Rejected treatment plan update: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        using.plannedAmount = plannedAmount
        return usingService.save(using)
    }
}
