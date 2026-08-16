package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.MedKit
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface MedKitRepository : JpaRepository<MedKit, UUID>
