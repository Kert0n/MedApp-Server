package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.api.UsingUpdateDTO
import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.kert0n.medappserver.db.repository.UsingRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class UsingService(
    private val usingRepository: UsingRepository,
    private val userService: UserService,
    private val drugService: DrugService
) {

    private val logger = LoggerFactory.getLogger(UsingService::class.java)

    @Transactional(readOnly = true)
    fun findAllByUser(userId: UUID): List<Using> {
        logger.debug("Finding all usings for user: {}", userId)
        // JOIN FETCH, а не простая выборка по user_id. Using.drug объявлен EAGER, но
        // производный запрос его не присоединяет: Hibernate достаёт каждый препарат
        // отдельным SELECT. Замерено — 167 планов давали 169 операторов.
        //
        // Метод findAllByUserIdWithDrug для этого и был написан, но вызывать его забыли.
        return usingRepository.findAllByUserIdWithDrug(userId)
    }

    @Transactional
    fun deleteAllByUserIdInMedkit(userId: UUID, medKitId: UUID) {
        logger.debug("Deleting all usings for user: {}", userId)
        usingRepository.deleteByUserIdAndMedKitId(userId, medKitId)
    }

    /**
     * Планы участников, которых нет в переданном списке, по всем препаратам аптечки.
     *
     * Для переноса препаратов: у кого нет доступа к целевой аптечке, у того не должно остаться
     * и плана. Вызывающий обязан не звать это с пустым списком — `NOT IN ()` в SQL невыразим.
     */
    @Transactional
    fun deleteAllInMedkitForUsersOtherThan(medKitId: UUID, userIds: Collection<UUID>) {
        require(userIds.isNotEmpty()) { "userIds must not be empty: NOT IN () is not valid SQL" }
        logger.debug("Deleting usings in medkit {} outside {} users", medKitId, userIds.size)
        usingRepository.deleteByMedKitIdAndUserIdNotIn(medKitId, userIds)
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

        // Блокировка первым действием, и её результат используется дальше. Раньше он
        // отбрасывался, а количества читались через using.drug — тот же экземпляр из
        // контекста, но по коду этого не видно, и выглядело так, будто запрос сделан ради
        // побочного эффекта.
        val drug = drugService.findByIdForUserForUpdate(drugId, userId)
        val using = findByUserAndDrug(userId, drugId)
        // Свой план исключается из суммы: он же и переписывается.
        val otherPlanned = drug.totalPlannedAmount - using.plannedAmount
        val availableQuantity = drug.quantity - otherPlanned

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
