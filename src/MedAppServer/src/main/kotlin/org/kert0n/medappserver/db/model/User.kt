package org.kert0n.medappserver.db.model

import jakarta.persistence.*
import jakarta.validation.constraints.NotNull
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.*

@Entity
@Table(
    name = "users",
    indexes = [
        Index(name = "ix_users_hashed_key", columnList = "hashed_key", unique = true)
    ]
)
class User(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID = UUID.randomUUID(),
    @NotNull
    @Column(name = "hashed_key", nullable = false)
    var hashedKey: String,
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "user_med_kits",
        joinColumns = [JoinColumn(name = "user_id")],
        inverseJoinColumns = [JoinColumn(name = "med_kit_id")]
    )
    var medKits: MutableSet<MedKit> = mutableSetOf(),
    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    var usings: MutableSet<Using> = mutableSetOf()
) : UserDetails {


    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as User

        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    override fun getAuthorities(): Collection<GrantedAuthority> {
        return emptyList()
    }

    override fun getPassword(): String {
        return hashedKey
    }

    override fun getUsername(): String {
        return id.toString()
    }

}

