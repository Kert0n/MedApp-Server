package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid

/** Аптечка — контейнер упаковок и проекция числа отдельных membership. */
data class MedKit(
    val id: Uuid = Uuid.random(),
    val userCount: Long = 1
) {

    /** Сущность: счётчик меняется, аптечка остаётся той же. */
    override fun equals(other: Any?): Boolean = this === other || (other is MedKit && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
