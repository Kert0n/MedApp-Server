package org.kert0n.medappserver.domain

import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Приём: запись о съеденном.
 *
 * Идентификатор придумывает клиент, и это не приём кеширования, а тождество: приём с таким
 * идентификатором либо записан, либо нет. Отсюда и идемпотентность повтора — она следствие, а
 * не отдельный механизм.
 *
 * В базе приёмов нет по замыслу: журнал приёма слишком персонален для таблицы. Живёт он вне
 * базы, и смена хранилища меняет журнал, а не эту запись.
 *
 * Упаковка приходит агрегатом: записывают приём той пачки, которую прочитали. Человек остаётся
 * идентификатором, и это не поблажка — правило про агрегат относится к тому, **чем** командуют,
 * а не к тому, **кто** командует. Вызывающий приходит из токена, его агрегат никто не читает, и
 * требовать `User` значило бы завести лишнее чтение на каждой команде ради единообразия.
 */
class Intake(
    val id: Uuid,
    val userId: Uuid,
    drug: Drug,
    val consumed: BigDecimal?,
    val reservedTo: BigDecimal?
) {
    val drugId: Uuid = drug.id

    /**
     * Тот же идентификатор с другим содержимым — не повтор, а другая команда.
     *
     * Считать её повтором значило бы подтвердить клиенту то, чего сервер не делал.
     */
    fun sameAs(other: Intake): Boolean =
        userId == other.userId &&
            drugId == other.drugId &&
            same(consumed, other.consumed) &&
            same(reservedTo, other.reservedTo)

    /** Сравнение по значению, а не по написанию: «5» и «5.000000» — одна и та же величина. */
    private fun same(left: BigDecimal?, right: BigDecimal?): Boolean =
        if (left == null || right == null) left == right else left.compareTo(right) == 0
}
