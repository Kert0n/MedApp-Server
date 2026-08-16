@file:Suppress("FunctionName")

package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.Drug
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface DrugRepository : JpaRepository<Drug, UUID>
