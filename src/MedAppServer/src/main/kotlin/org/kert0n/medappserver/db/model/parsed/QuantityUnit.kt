package org.kert0n.medappserver.db.model.parsed

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.*

/**
 * Справочная таблица, крошечная и почти неизменная.
 *
 * `@BatchSize`: поиск по каталогу — нативный запрос, и присоединить к нему EAGER-связи
 * Hibernate не может, поэтому добирает их отдельными SELECT — по одному на каждое
 * различающееся значение в выдаче. Пакет схлопывает их в один запрос.
 */
@org.hibernate.annotations.BatchSize(size = 64)
@Entity
@Table(
    name = "quantity_units", uniqueConstraints = [UniqueConstraint(
        name = "quantity_units_name_key",
        columnNames = ["name"]
    )]
)
class QuantityUnit(
    @Id
    @Column(name = "id", nullable = false) var id: UUID = UUID.randomUUID(),

    @Size(max = 30)
    @NotNull
    @Column(name = "name", nullable = false, length = 30) var name: String

) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as QuantityUnit

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}