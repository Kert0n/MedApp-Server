package org.kert0n.medappserver.testutil

import java.math.BigDecimal
import java.util.UUID
import org.kert0n.medappserver.api.DrugPatchRequest
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.MedKit
import org.kert0n.medappserver.domain.TreatmentPlan
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.services.orchestrators.MedKitDrugOrchestrator

/**
 * Команды, выполняемые по текущей версии агрегата.
 *
 * Нужны тестам поведения. Они проверяют правила — сколько осталось, что стало с планами, кому
 * что видно, — и версия в них не предмет проверки, а обязательный аргумент: протаскивая его
 * через каждый вызов, тест перестал бы читаться о том, ради чего написан.
 *
 * Само предусловие при этом не остаётся непроверенным, и в этом смысл разделения: устаревшая
 * версия, отсутствующий и негодный заголовок проверяются там, где они и есть предмет —
 * `PreconditionMatrixTest` и тесты гонок. Там версия предъявляется руками, и никаких
 * помощников не используется.
 *
 * Возвращают то же, что возвращали команды до появления версий: тесты писались под тот вид, и
 * менять их вместе с сигнатурами значило бы смешать две разные правки в одном диффе.
 */

fun DrugService.updateLatest(drugId: UUID, request: DrugPatchRequest, userId: UUID): Drug =
    update(drugId, request, userId, versionOf(drugId, userId))

fun DrugService.deleteLatest(drugId: UUID, userId: UUID) =
    delete(drugId, userId, versionOf(drugId, userId))

fun DrugService.consumeLatest(drugId: UUID, quantity: BigDecimal, userId: UUID): Drug? =
    consume(drugId, quantity, userId, versionOf(drugId, userId))

fun DrugService.createPlanLatest(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan =
    createPlan(userId, drugId, plannedAmount, versionOf(drugId, userId)).requirePlanOf(userId)

fun DrugService.changePlanLatest(userId: UUID, drugId: UUID, plannedAmount: BigDecimal): TreatmentPlan =
    changePlan(userId, drugId, plannedAmount, versionOf(drugId, userId)).requirePlanOf(userId)

fun DrugService.cancelPlanLatest(userId: UUID, drugId: UUID) =
    cancelPlan(userId, drugId, versionOf(drugId, userId))

/** `null` означает то же, что и раньше: план исчерпан либо препарат кончился. */
fun DrugService.recordIntakeLatest(userId: UUID, drugId: UUID, quantityConsumed: BigDecimal): TreatmentPlan? =
    recordIntake(userId, drugId, quantityConsumed, versionOf(drugId, userId)).plan

fun MedKitService.leaveLatest(medKitId: UUID, userId: UUID): MedKit? =
    leave(medKitId, userId, requireById(medKitId).version)

/**
 * Версия аптечки читается её же сервисом, а не через `medKitWithDrugs`.
 *
 * Разница не косметическая: чтение с содержимым подняло бы препараты в persistence context, и
 * последующее удаление аптечки уронило бы flush ссылкой на исчезнувшую строку. В приложении
 * такой последовательности нет — там удаление ничего не читает, — и тест не должен создавать
 * её на пустом месте.
 */
fun MedKitDrugOrchestrator.leaveMedKitLatest(medKits: MedKitService, medKitId: UUID, userId: UUID) =
    leaveMedKit(medKitId, userId, medKits.requireById(medKitId).version)

fun MedKitDrugOrchestrator.deleteLatest(
    medKits: MedKitService,
    medKitId: UUID,
    userId: UUID,
    transferToMedKitId: UUID? = null
) = delete(medKitId, userId, medKits.requireById(medKitId).version, transferToMedKitId)

/**
 * Переезд предъявляет версию препарата, а не аптечки, — и прочитать её оркестратору нечем.
 * Поэтому сервис препарата передаётся аргументом: подменять предмет проверки удобством здесь
 * было бы неправильно.
 */
fun MedKitDrugOrchestrator.moveDrugLatest(
    drugs: DrugService,
    drugId: UUID,
    targetMedKitId: UUID,
    userId: UUID
): Drug = moveDrug(drugId, targetMedKitId, userId, drugs.versionOf(drugId, userId))

private fun DrugService.versionOf(drugId: UUID, userId: UUID): Long = require(drugId, userId).version
