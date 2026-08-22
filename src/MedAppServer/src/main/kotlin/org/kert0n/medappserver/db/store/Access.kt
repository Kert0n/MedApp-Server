package org.kert0n.medappserver.db.store

import kotlin.uuid.Uuid
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.jdbc.select
import org.kert0n.medappserver.db.tables.MedKitMemberships

/**
 * Что значит «доступно вызывающему» — в одном месте на все хранилища.
 *
 * Доступ даёт членство в аптечке, и любая строка, у которой есть аптечка, проверяется
 * одинаково — упаковка, бронь, что угодно дальше. Написать это условие второй раз значило бы
 * завести второе место, где его можно поправить наполовину.
 */
internal fun Column<Uuid>.inMedKitsOf(userId: Uuid): Op<Boolean> =
    this inSubQuery MedKitMemberships
        .select(MedKitMemberships.medKitId)
        .where { MedKitMemberships.userId eq userId }
