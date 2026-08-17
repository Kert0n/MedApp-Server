package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import java.util.*

/**
 * Отображение аптечки на таблицу `med_kits`.
 *
 * Ни участников, ни препаратов здесь нет: и то и другое — отдельные агрегаты, а держать на
 * них коллекции значит разрешить менять их мимо собственных правил. Участники живут строками
 * [MedKitMembershipData], препараты ссылаются на аптечку сами.
 */
@Entity
@Table(name = "med_kits")
class MedKitData(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),

    /**
     * Токен оптимистичной блокировки.
     *
     * Своих изменяемых полей у аптечки нет, поэтому Hibernate сам эту версию не продвинет:
     * состав участников лежит в других строках. Продвигает её команда членства, присваивая
     * значение явно, — см. `MedKitStore.save`.
     */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedKitData

        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
