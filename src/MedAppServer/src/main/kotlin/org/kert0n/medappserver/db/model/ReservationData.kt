package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import java.io.Serializable
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Отображение брони на `reservations`. Правил здесь нет.
 *
 * Ключи здесь — страховка под правилами, а не сами правила: правила читаются в домене и в
 * сценариях, которые их применяют. Ключ лишь не даёт забыть шаг и закрывает гонку.
 *
 * Обе связи объявлены **только на чтение**: колонки `drug_id`, `user_id` и `med_kit_id` пишутся
 * своими полями, а связи существуют затем, чтобы Hibernate построил составные ключи в схеме для
 * тестов — иначе тесты проверяли бы схему без тех ограничений, что стоят в проде.
 */
@Entity
@Table(
    name = "reservations",
    indexes = [
        Index(name = "ix_reservations_user_id", columnList = "user_id"),
        Index(name = "ix_reservations_med_kit_user_id", columnList = "med_kit_id, user_id")
    ]
)
class ReservationData(

    @EmbeddedId
    var reservationKey: ReservationKey = ReservationKey(),

    /**
     * Аптечка пачки, скопированная сюда.
     *
     * Не свойство брони: назначение живёт парой «человек и пачка», а хранилище своё у пачки.
     * Копия нужна двум ключам ниже — без неё членство и бронь связать нечем. В домен не выходит.
     */
    @NotNull
    @Column(name = "med_kit_id", nullable = false)
    var medKitId: UUID,

    @NotNull
    @Column(name = "amount", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var amount: BigDecimal,

    /**
     * Пачка вместе со своей аптечкой.
     *
     * Ключ составной, и это даёт два следствия. Первое: `med_kit_id` брони не рассогласовать с
     * настоящей аптечкой пачки — такой пары просто нет в родителе. Второе: переезд пачки тянет
     * копию за собой, `ON UPDATE CASCADE`. Правило, которое за этим стоит, написано в
     * `Reservation.survivesRelocationTo` и в `DrugRelocation`; здесь только его страховка.
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumns(
        value = [
            JoinColumn(name = "drug_id", referencedColumnName = "id", insertable = false, updatable = false),
            JoinColumn(name = "med_kit_id", referencedColumnName = "med_kit_id", insertable = false, updatable = false)
        ],
        foreignKey = ForeignKey(
            name = "reservations_drug_med_kit_fkey",
            foreignKeyDefinition = "FOREIGN KEY (drug_id, med_kit_id) REFERENCES user_drugs (id, med_kit_id) " +
                "ON UPDATE CASCADE ON DELETE CASCADE"
        )
    )
    var drugData: DrugData? = null,

    /**
     * Членство владельца в аптечке пачки.
     *
     * Читать её незачем — связь объявлена ради ключа: «нет членства — нет брони». Правило живёт
     * в выходе из аптечки, который снимает брони сам; ключ страхует от забывчивости и закрывает
     * гонку — вставка удерживает строку членства до конца транзакции.
     *
     * Отдельного ключа на `users` больше нет: членство и так на них ссылается.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(
        value = [
            JoinColumn(name = "med_kit_id", referencedColumnName = "med_kit_id", insertable = false, updatable = false),
            JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
        ],
        foreignKey = ForeignKey(
            name = "reservations_membership_fkey",
            foreignKeyDefinition = "FOREIGN KEY (med_kit_id, user_id) REFERENCES user_med_kits (med_kit_id, user_id) " +
                "ON DELETE CASCADE"
        )
    )
    var membership: MedKitMembershipData? = null,

    /** Версией распоряжается Hibernate. Единственное поле брони меняет её же строку. */
    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReservationData

        return reservationKey == other.reservationKey
    }

    override fun hashCode(): Int {
        return reservationKey.hashCode()
    }
}

@Suppress("JpaDataSourceORMInspection")
@Embeddable
class ReservationKey(
    @Column(name = "user_id")
    var userId: UUID = UUID(0, 0),
    @Column(name = "drug_id")
    var drugId: UUID = UUID(0, 0)
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReservationKey

        return userId == other.userId && drugId == other.drugId
    }

    override fun hashCode(): Int {
        return Objects.hash(userId, drugId)
    }
}
