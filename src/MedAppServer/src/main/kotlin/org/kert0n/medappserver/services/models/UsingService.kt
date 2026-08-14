package org.kert0n.medappserver.services.models

import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.repository.UsingRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.*

@Service
class UsingService(
    private val usingRepository: UsingRepository
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

    /**
     * Сохранить план. Нужен оркестраторам: репозиторий им напрямую недоступен.
     *
     * Заведение и правка плана живут в TreatmentPlanService, а не здесь: они обязаны взять
     * строку препарата под блокировку, чтобы прочитать инвариант «сумма планов не больше
     * остатка», — а это чужой агрегат и чужой репозиторий.
     */
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
