package org.kert0n.medappserver.api

import org.kert0n.medappserver.application.model.DrugResult
import org.kert0n.medappserver.application.model.DrugTemplateView
import org.kert0n.medappserver.application.model.IntakeResult
import org.kert0n.medappserver.application.model.MedKitContentView
import org.kert0n.medappserver.application.model.MedKitSummaryView
import org.kert0n.medappserver.application.model.TreatmentPlanResult
import org.kert0n.medappserver.application.model.UserSnapshotView

fun DrugResult.toDto(): DrugDTO = DrugDTO(
    id, medKitId, name, quantity, plannedQuantity, availableQuantity, quantityUnit,
    formType, category, manufacturer, country, description
)

fun TreatmentPlanResult.toDto(): TreatmentPlanDTO = TreatmentPlanDTO(userId, drugId, plannedAmount)

fun IntakeResult.toDto(): IntakeResultDTO = IntakeResultDTO(drug?.toDto(), plan?.toDto())

fun DrugTemplateView.toDto(): DrugTemplateDTO = DrugTemplateDTO(
    id, name, formType, category, quantityUnit, manufacturer, country, description
)

fun MedKitSummaryView.toDto(): MedKitSummaryDTO = MedKitSummaryDTO(id, userCount, drugCount)

fun MedKitContentView.toDto(): MedKitContentDTO = MedKitContentDTO(id, drugs.map(DrugResult::toDto))

fun UserSnapshotView.toDto(): UserSnapshotDTO = UserSnapshotDTO(id, medKits.map(MedKitContentView::toDto))
