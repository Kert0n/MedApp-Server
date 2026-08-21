package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import java.io.Serializable
import java.util.*

/**
 * Членство в аптечке — строка `user_med_kits`.
 *
 * Сущность явная, а не скрытая внутри `@ManyToMany`: участники не тянутся графом объектов,
 * запросы соединяются по этой таблице напрямую.
 *
 * Каскад односторонний: членство исчезает вместе с аптечкой, но не вместе с пользователем —
 * удаление человека не является операцией API.
 */
@Entity
@Table(name = "user_med_kits")
class MedKitMembershipData(
    @EmbeddedId
    var membershipKey: MedKitMembershipKey = MedKitMembershipKey(),

    /**
     * Аптечка и пользователь — **только на чтение**.
     *
     * Строка пишется одним ключом: обе колонки в нём и так есть. Связи объявлены затем, чтобы
     * Hibernate построил внешние ключи в схеме для тестов, — а не затем, чтобы их выставлять.
     * Иначе хранилищу членства пришлось бы держать чужие репозитории и поднимать чужие строки
     * ради ссылки, которую оно и так знает.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "med_kit_id",
        insertable = false,
        updatable = false,
        foreignKey = ForeignKey(
            name = "user_med_kits_med_kit_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKitData? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "user_id",
        insertable = false,
        updatable = false,
        foreignKey = ForeignKey(name = "user_med_kits_user_fkey")
    )
    var user: UserData? = null
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
