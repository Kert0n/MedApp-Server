package org.kert0n.medappserver.services.orchestrators

import org.kert0n.medappserver.services.models.DrugCreation
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.models.UserService
import org.kert0n.medappserver.services.models.UsingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*

@Service
class MedKitDrugServices(
    private val drugService: DrugService,
    private val drugCommands: DrugCommandService,
    private val medKitService: MedKitService,
    private val userService: UserService,
    private val usingService: UsingService
) {

    private val logger = LoggerFactory.getLogger(MedKitDrugServices::class.java)

    @Transactional
    fun createDrugInMedkit(medKitId: UUID, command: DrugCreation, userId: UUID): Drug {
        logger.debug("Creating drug: {} for user: {}", command.name, userId)
        return drugCommands.create(userId, medKitId, command)
    }

    @Transactional
    fun moveDrug(drugId: UUID, targetMedKitId: UUID, userId: UUID): Drug {
        logger.debug("Moving drug {} to medkit {}", drugId, targetMedKitId)
        return drugCommands.move(userId, drugId, targetMedKitId)
    }

    @Transactional
    fun removeUserFromMedKit(medKitId: UUID, userId: UUID) {
        logger.debug("Removing user {} from MedKit {}",userId, medKitId)
        val medKit = medKitService.findByIdForUser(medKitId, userId)
        val user = userService.findById(userId)
        // Одним оператором вместо выборки всех препаратов аптечки со всеми их планами ради
        // вычистки коллекций: это была загрузка всего содержимого плюс DELETE на каждый план.
        // Препараты здесь не нужны вовсе — уходит только участник и его планы.
        usingService.deleteAllByUserIdInMedkit(userId, medKitId)
        medKitService.removeUserFromMedKit(medKit, user)
    }

    /**
     * Удаляет аптечку — с переносом препаратов в другую или вместе с ними.
     *
     * Две ветки грузят аптечку **разными** запросами, и это не оптимизация, а условие
     * корректности.
     *
     * Без переноса нужен граф с препаратами и планами: удаление идёт каскадом по
     * `medKit.drugs`, а по неинициализированной коллекции каскад проходит впустую и упирается
     * во внешний ключ.
     *
     * С переносом препараты переезжают bulk-операторами. Прежний код делал то же циклом по
     * `medKit.drugs` с ручной синхронизацией трёх коллекций — работало, но держалось на том,
     * что автор помнит порядок строк.
     *
     * Отсюда жёсткий порядок: доступ проверяется **до** любых изменений, дальше идут
     * bulk-операторы, и только потом аптечка читается заново. Читать её раньше нельзя:
     * bulk проходит мимо контекста персистентности, и `medKit.drugs`, загруженная где угодно
     * выше по транзакции, после переноса показывала бы препараты, которых в аптечке уже нет —
     * а каскад при удалении снёс бы только что перенесённое. Так и случилось при первой
     * попытке: тест миграции упал, потому что вызов `findAllByUser` в самом тесте успел
     * инициализировать эту коллекцию своим графом.
     */
    @Transactional
    fun delete(medKitId: UUID, userId: UUID, transferToMedKitId: UUID? = null) {
        if (transferToMedKitId != null) {
            // Доступ к обеим аптечкам — до изменений. Иначе bulk успел бы отработать раньше,
            // чем выяснится, что удалять нечего.
            medKitService.findByIdForUser(medKitId, userId)
            val usersWithAccess = medKitService.findByIdForUserWithUsers(transferToMedKitId, userId)
                .users.map { it.id }.toSet()

            // Порядок обязателен: сначала планы, потом препараты. После переноса условие по
            // med_kit_id уже не нашло бы эти планы.
            usingService.deleteAllInMedkitForUsersOtherThan(medKitId, usersWithAccess)
            medKitService.reassignAllDrugs(medKitId, transferToMedKitId)
        }

        // Только теперь: после bulk контекст очищен, и это актуальное состояние.
        val medKit = if (transferToMedKitId == null) {
            medKitService.findByIdForUserForDeletion(medKitId, userId)
        } else {
            medKitService.findByIdForUserWithUsers(medKitId, userId)
        }

        medKit.users.forEach { user ->
            user.medKits.remove(medKit)
        }
        medKitService.delete(medKit)
    }

    /**
     * Препараты аптечки вместе с планами — одним запросом.
     *
     * Раньше метод назывался `toMedKitDTO` и сам собирал ответ. Маппинг уехал в `api`, здесь
     * осталась только загрузка: это и есть работа оркестратора — знать, каким запросом
     * достать нужную форму данных. Планы фетчатся графом, потому что `DrugDTO` несёт
     * плановое количество, а без графа оно тянуло бы по запросу на препарат.
     */
    /**
     * Аптечка вместе с её содержимым — одной транзакцией.
     *
     * Раньше контроллер звал `medKitService.findByIdForUser`, затем `drugsWithPlans`, и
     * маппил результат. Каждый из двух вызовов открывал свою транзакцию, между ними
     * состояние могло измениться, а сущность передавалась из одной в другую и маппилась уже
     * вне сессии. Число запросов то же, атомарность — нет.
     */
    @Transactional(readOnly = true)
    fun medKitContent(medKitId: UUID, userId: UUID): MedKitContent {
        val medKit = medKitService.findByIdForUser(medKitId, userId)
        return MedKitContent(medKit, drugsWithPlans(medKit))
    }

    /** Вступление в аптечку по ключу и её содержимое — одной транзакцией. */
    @Transactional
    fun joinByKey(key: String, userId: UUID): MedKitContent {
        val medKit = medKitService.joinMedKitByKey(key, userId)
        return MedKitContent(medKit, drugsWithPlans(medKit))
    }

    /**
     * Полный снимок пользователя: все его аптечки с содержимым.
     *
     * Два запроса при любом числе аптечек — за аптечками и за препаратами всех сразу.
     * Раскладка по аптечкам делается в памяти: она дешевле лишнего обращения к базе.
     */
    @Transactional(readOnly = true)
    fun userSnapshot(userId: UUID): List<MedKitContent> {
        val medKits = medKitService.findAllByUser(userId)
        val drugsByMedKit = drugsWithPlansByMedKit(medKits)
        return medKits.map { MedKitContent(it, drugsByMedKit[it.id].orEmpty()) }
    }

    @Transactional(readOnly = true)
    private fun drugsWithPlans(medKit: MedKit): List<Drug> =
        drugService.findAllWithPlansByMedKit(medKit.id)

    /**
     * Препараты сразу нескольких аптечек, разложенные по аптечкам.
     *
     * Для выдачи синхронизации: вызов [drugsWithPlans] в цикле по аптечкам давал `1 + M`
     * операторов, где M — число аптечек пользователя. Здесь два запроса при любом M, а
     * раскладка делается в памяти: она дешевле лишнего обращения к базе.
     */
    @Transactional(readOnly = true)
    private fun drugsWithPlansByMedKit(medKits: Collection<MedKit>): Map<UUID, List<Drug>> =
        drugService.findAllWithPlansByMedKits(medKits.map { it.id })
            .groupBy { it.medKit.id }
}

/**
 * Аптечка и её препараты, прочитанные вместе.
 *
 * Пара, а не два вызова из контроллера: содержимое имеет смысл только вместе со своей
 * аптечкой, и читать их порознь — значит собирать ответ из двух моментов времени.
 */
data class MedKitContent(val medKit: MedKit, val drugs: List<Drug>)
