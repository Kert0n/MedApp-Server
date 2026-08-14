package org.kert0n.medappserver.db.repository

import org.kert0n.medappserver.db.model.Using
import org.kert0n.medappserver.db.model.UsingKey
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface UsingRepository : JpaRepository<Using, UsingKey> {

    @Query("SELECT u FROM Using u WHERE u.usingKey.userId = :userId")
    fun findAllByUserId(userId: UUID): List<Using>

    @Query("SELECT u FROM Using u WHERE u.usingKey.drugId = :drugId ORDER BY u.usingKey.userId")
    fun findAllByDrugId(drugId: UUID): List<Using>

    @Query(
        """
        SELECT u FROM Using u
        WHERE u.user.id = :userId AND u.drug.id = :drugId
    """
    )
    fun findByUserIdAndDrugId(@Param("userId") userId: UUID, @Param("drugId") drugId: UUID): Using?

    /**
     * Все планы одного участника во всех препаратах аптечки — одним оператором.
     *
     * Альтернатива — загрузить препараты аптечки с планами и вычистить коллекции: это выборка
     * всего содержимого плюс DELETE на каждый план. Здесь работу делает БД.
     *
     * `flushAutomatically`: bulk идёт мимо контекста персистентности, поэтому несохранённые
     * изменения обязаны попасть в базу раньше, иначе они перезапишут результат. `clearAutomatically`
     * намеренно **не** включён — он отцепил бы все сущности, включая аптечку и пользователя,
     * которых вызывающий правит следующей строкой.
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM Using u WHERE u.user.id = :userId AND u.drug.medKit.id = :medKitId")
    fun deleteByUserIdAndMedKitId(userId: UUID, medKitId: UUID)

    /**
     * Планы всех, кто не входит в переданный список, по всем препаратам аптечки.
     *
     * Нужен при переносе препаратов в другую аптечку: планы участников, которых в целевой
     * аптечке нет, обязаны исчезнуть. Список не бывает пустым — в целевой аптечке всегда есть
     * как минимум тот, кто перенос затеял.
     *
     * `clearAutomatically` по той же причине, что у `reassignMedKit`: дальше по этому пути
     * идёт удаление аптечки каскадом, и любая коллекция планов, загруженная выше по
     * транзакции, после bulk врёт.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Using u WHERE u.drug.medKit.id = :medKitId AND u.user.id NOT IN :userIds")
    fun deleteByMedKitIdAndUserIdNotIn(medKitId: UUID, userIds: Collection<UUID>)

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM Using u WHERE u.drug.id = :drugId AND u.user.id NOT IN :userIds")
    fun deleteByDrugIdAndUserIdNotIn(drugId: UUID, userIds: Collection<UUID>): Int


}
