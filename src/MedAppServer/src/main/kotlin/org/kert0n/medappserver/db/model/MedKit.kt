package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import java.util.*


@Entity
@Table(name = "med_kits")
class MedKit(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),
    @ManyToMany(mappedBy = "medKits", fetch = FetchType.LAZY, cascade = [CascadeType.PERSIST, CascadeType.MERGE])
    var users: MutableSet<User> = mutableSetOf()

) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MedKit

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}
