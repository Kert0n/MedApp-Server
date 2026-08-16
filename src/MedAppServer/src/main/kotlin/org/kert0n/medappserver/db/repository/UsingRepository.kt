package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.TreatmentPlan
import org.kert0n.medappserver.db.model.TreatmentPlanKey
import org.springframework.data.jpa.repository.JpaRepository

interface UsingRepository : JpaRepository<TreatmentPlan, TreatmentPlanKey>
