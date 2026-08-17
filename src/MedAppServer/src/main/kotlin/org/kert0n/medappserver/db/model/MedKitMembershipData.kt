package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import java.io.Serializable
import java.util.*

/**
 * Членство в аптечке — строка таблицы `user_med_kits`.
 *
 * Раньше эта таблица была скрыта внутри `@ManyToMany` и существовала только как связь двух
 * коллекций. Явная сущность нужна, чтобы участники не тянулись графом объектов: домен знает
 * идентификаторы, а запросы соединяются по этой таблице напрямую.
 *
 * Ссылки на аптечку и пользователя оставлены здесь ровно затем, чтобы имена ключей и каскад
 * совпадали с `db/schema.sql`: членство исчезает вместе с аптечкой, но не вместе с
 * пользователем — удаление пользователя не является операцией API.
 */
@Entity
@Table(name = "user_med_kits")
class MedKitMembershipData(
    @EmbeddedId
    var membershipKey: MedKitMembershipKey = MedKitMembershipKey(),

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("medKitId")
    @JoinColumn(
        name = "med_kit_id",
        foreignKey = ForeignKey(
            name = "user_med_kits_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKitData,

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", foreignKey = ForeignKey(name = "user_med_kits_user_fkey"))
    var user: UserData
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedKitMembershipData

        return membershipKey == other.membershipKey
    }

    override fun hashCode(): Int = membershipKey.hashCode()
}

@Suppress("JpaDataSourceORMInspection")
@Embeddable
class MedKitMembershipKey(
    @Column(name = "med_kit_id", nullable = false)
    var medKitId: UUID = UUID(0, 0),
    @Column(name = "user_id", nullable = false)
    var userId: UUID = UUID(0, 0)
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedKitMembershipKey

        return medKitId == other.medKitId && userId == other.userId
    }

    override fun hashCode(): Int = Objects.hash(medKitId, userId)
}
