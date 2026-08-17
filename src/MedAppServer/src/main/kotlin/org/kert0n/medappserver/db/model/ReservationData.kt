package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import java.io.Serializable
import java.math.BigDecimal
import java.util.*
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Отображение брони на таблицу `reservations`. Правил здесь нет.
 *
 * Ссылки на пользователя и упаковку остаются `@ManyToOne`: это работа ORM — держать внешние
 * ключи и их каскады, и по ним же Hibernate строит схему для тестов. Домен при этом ссылается
 * на упаковку идентификатором, потому что для него это другой агрегат; разное представление
 * одной и той же связи в двух слоях — не противоречие, а разделение обязанностей.
 *
 * Обратной коллекции в `DrugData` нет намеренно: упаковка бронями не владеет. Поэтому здесь
 * нет ни каскада со стороны JPA, ни `orphanRemoval` — исчезновение брони вслед за пачкой
 * держит внешний ключ.
 */
@Entity
@Table(
    name = "reservations",
    indexes = [
        Index(name = "ix_reservations_user_id", columnList = "user_id"),
        Index(name = "ix_reservations_drug_id", columnList = "drug_id")
    ]
)
class ReservationData(

    @EmbeddedId
    var reservationKey: ReservationKey = ReservationKey(),

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("userId")
    // Имя задано явно, чтобы схема Hibernate и db/schema.sql совпадали и по именам ключей.
    // Каскада здесь намеренно нет: бронь не удаляется вслед за пользователем.
    @JoinColumn(name = "user_id", foreignKey = ForeignKey(name = "reservations_user_fkey"))
    var userData: UserData,

    @ManyToOne(fetch = FetchType.EAGER)
    @MapsId("drugId")
    @JoinColumn(
        name = "drug_id",
        // Как и у Drug.medKit: явное определение держит схему Hibernate и db/schema.sql в
        // одном каскадном контракте.
        foreignKey = ForeignKey(
            name = "reservations_drug_fkey",
            foreignKeyDefinition =
                "FOREIGN KEY (drug_id) REFERENCES user_drugs (id) ON DELETE CASCADE"
        )
    )
    var drugData: DrugData,

    @NotNull
    @Column(name = "amount", nullable = false, precision = QUANTITY_PRECISION, scale = QUANTITY_SCALE)
    var amount: BigDecimal
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
