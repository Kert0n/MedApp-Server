package org.kert0n.medappserver.db.model.parsed

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.util.*

/**
 * Ссылочный справочник.
 *
 * `@BatchSize`: поиск по каталогу — нативный запрос, join fetch к нему не приделать, и
 * EAGER-связь давала бы SELECT на каждую строку выдачи. С батчем пятьдесят результатов стоят
 * одного лишнего запроса вместо пятидесяти.
 */
@org.hibernate.annotations.BatchSize(size = 64)
@Entity
@Table(
    name = "form_types", uniqueConstraints = [UniqueConstraint(
        name = "form_types_name_key",
        columnNames = ["name"]
    )]
)
class FormTypeData(
    @Id
    @Column(name = "id", nullable = false) var id: UUID = UUID.randomUUID(),

    @Size(max = 100)
    @NotNull
    @Column(name = "name", nullable = false, length = 100) var name: String

) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FormTypeData

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}