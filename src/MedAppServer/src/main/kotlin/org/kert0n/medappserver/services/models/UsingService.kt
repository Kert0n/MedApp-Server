package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.api.UsingDTO
import org.kert0n.medappserver.api.UsingUpdateDTO
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.isZero
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.repository.UsingRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.*

@Service
class UsingService(
    private val usingRepository: UsingRepository,
    val logger: Logger = LoggerFactory.getLogger(UsingService::class.java),
    private val userService: UserService,
    private val drugService: DrugService
) {


    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<Using> {
        logger.debug("Finding all usings for user: {}", userId)
        return usingRepository.findAllByUsingKeyUserId(userId)
    }

    @Transactional
    fun deleteAllByUserIdInMedkit(userId: UUID, medKitId: UUID) {
        logger.debug("Deleting all usings for user: {}", userId)
        usingRepository.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    @Transactional(readOnly = true)
    fun findAllByDrug(drugId: UUID): List<Using> {
        logger.debug("Finding all usings for drug: {}", drugId)
        return usingRepository.findAllByUsingKeyDrugId(drugId)
    }

    /**
     * План или `null`, если его нет.
     *
     * Для чтения отсутствие плана — это не ошибка. Tombstone'ов проект не ведёт, поэтому
     * старый клиент вполне законно приходит за планом, который уже удалён (например
     * приёмом, забравшим остаток целиком), и должен получить пустоту, а не 404: по 404 он
     * не отличит «плана нет» от «эндпоинт сломался» и будет повторять запрос.
     *
     * Мутирующие пути пользуются [findByUserAndDrug]: там отсутствие плана — действительно
     * ошибка вызова.
     */
    @Transactional(readOnly = true)
    fun findByUserAndDrugOrNull(userId: UUID, drugId: UUID): Using? {
        logger.debug("Finding using for user {} and drug {}", userId, drugId)
        return usingRepository.findByUserIdAndDrugId(userId, drugId)
    }

    /** План или 404. Для путей, где отсутствие плана делает операцию бессмысленной. */
    @Transactional(readOnly = true)
    fun findByUserAndDrug(userId: UUID, drugId: UUID): Using =
        findByUserAndDrugOrNull(userId, drugId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "There is no such using")

    @Transactional
    fun createTreatmentPlan(userId: UUID, createDTO: UsingCreateDTO): Using {
        logger.debug("Creating treatment for user {} and drug {}", userId, createDTO.drugId)


        val user = userService.findById(userId)
        val drug = drugService.findByIdForUserForUpdate(createDTO.drugId, userId)

        if (usingRepository.findByUserIdAndDrugId(userId, createDTO.drugId) != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "using already exists for this user and drug")
        }

        // Validate planned quantity against currently reserved amounts to avoid overbooking stock.
        val currentPlanned = drug.totalPlannedAmount
        val availableQuantity = drug.quantity - currentPlanned

        if (createDTO.plannedAmount > availableQuantity) {
            // No amounts in the message or the log line: both end up somewhere readable,
            // and a drug plus a quantity is the kind of detail this server does not hand out.
            logger.warn("Rejected treatment plan: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        val using = Using(
            usingKey = UsingKey(userId, createDTO.drugId),
            user = user,
            drug = drug,
            plannedAmount = createDTO.plannedAmount
        )

        val saved = usingRepository.save(using)
        // Обе стороны связи, а не только владеющая. План сохраняется репозиторием, но
        // Drug.usings объявлена с CascadeType.ALL и orphanRemoval — если не добавить, коллекция
        // до конца транзакции показывает состояние без нового плана, и весь код, который на неё
        // опирается (каскадное удаление, перенос препарата между аптечками), работает по
        // устаревшему набору.
        drug.usings.add(saved)
        return saved
    }

    @Transactional
    fun updateTreatmentPlan(userId: UUID, drugId: UUID, updateDTO: UsingUpdateDTO): Using {
        logger.debug("Updating using for user {} and drug {}", userId, drugId)

        // Lock the drug row to prevent concurrent plan modifications
        drugService.findByIdForUserForUpdate(drugId, userId)
        val using = findByUserAndDrug(userId, drugId)
        // Exclude the current plan when checking availability.
        val otherPlanned = using.drug.totalPlannedAmount - using.plannedAmount
        val availableQuantity = using.drug.quantity - otherPlanned

        if (updateDTO.plannedAmount > availableQuantity) {
            logger.warn("Rejected treatment plan update: requested amount exceeds availability")
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient quantity available")
        }

        using.plannedAmount = updateDTO.plannedAmount

        return usingRepository.save(using)
    }



    /** Сохранить план. Нужен оркестраторам: репозиторий им напрямую недоступен. */
    @Transactional
    fun save(using: Using): Using = usingRepository.save(using)

    @Transactional
    fun deleteTreatmentPlan(userId: UUID, drugId: UUID) {
        logger.debug("Deleting using for user {} and drug {}", userId, drugId)
        val using = findByUserAndDrug(userId, drugId)
        // Тоже через коллекцию: удаление плана — это исключение элемента из drug.usings,
        // а не независимая операция над таблицей. orphanRemoval доводит её до DELETE.
        using.drug.usings.remove(using)
    }


}
