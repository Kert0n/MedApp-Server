package org.kert0n.medappserver.domain

import kotlin.uuid.Uuid

/**
 * Аптечка — контейнер упаковок и проекция числа отдельных membership.
 *
 * Идентификатор обязателен и умолчания не имеет: его придумывает клиент, а сервер только
 * записывает. Умолчание `Uuid.random()` здесь было бы тихим запасным путём к серверной
 * генерации — ровно к тому, из-за чего клиент не мог адресовать созданное, если ответ потерялся.
 */
data class MedKit(
    val id: Uuid,
    val userCount: Long = 1
) {

    /** Сущность: счётчик меняется, аптечка остаётся той же. */
    override fun equals(other: Any?): Boolean = this === other || (other is MedKit && id == other.id)

    override fun hashCode(): Int = id.hashCode()
}
