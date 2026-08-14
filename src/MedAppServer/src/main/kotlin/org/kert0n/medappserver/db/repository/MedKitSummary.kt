package org.kert0n.medappserver.db.repository

import java.util.UUID

/**
 * Проекция количества участников и препаратов без загрузки коллекций аптечки.
 */
data class MedKitSummary(
    val id: UUID,
    val userCount: Long,
    val drugCount: Long
)
