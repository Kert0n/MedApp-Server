package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import java.util.*

/**
 * Отображение аптечки на `med_kits`.
 *
 * Коллекций здесь нет: держать их значило бы разрешить менять чужие агрегаты мимо их правил.
 * Участники живут строками [MedKitMembershipData], упаковки ссылаются на аптечку сами.
 */
@Entity
@Table(name = "med_kits")
class MedKitData(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedKitData

        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
