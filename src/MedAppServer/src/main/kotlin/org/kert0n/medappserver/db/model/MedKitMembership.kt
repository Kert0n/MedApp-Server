package org.kert0n.medappserver.db.model

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import java.io.Serializable
import java.util.UUID

@Entity
@Table(name = "user_med_kits")
class MedKitMembership(
    @EmbeddedId var key: MedKitMembershipKey = MedKitMembershipKey(),
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("userId")
    @JoinColumn(name = "user_id", foreignKey = ForeignKey(name = "user_med_kits_user_fkey"))
    var user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("medKitId")
    @JoinColumn(
        name = "med_kit_id",
        foreignKey = ForeignKey(
            name = "user_med_kits_med_kit_fkey",
            foreignKeyDefinition = "FOREIGN KEY (med_kit_id) REFERENCES med_kits (id) ON DELETE CASCADE"
        )
    )
    var medKit: MedKit
)

@Embeddable
data class MedKitMembershipKey(
    @Column(name = "user_id") var userId: UUID = UUID(0, 0),
    @Column(name = "med_kit_id") var medKitId: UUID = UUID(0, 0)
) : Serializable
