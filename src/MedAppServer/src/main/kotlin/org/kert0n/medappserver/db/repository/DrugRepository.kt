@file:Suppress("FunctionName")

package org.kert0n.medappserver.db.repository

import jakarta.persistence.LockModeType
import org.kert0n.medappserver.db.model.Drug
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.*

interface DrugRepository : JpaRepository<Drug, UUID> {

    fun findAllByMedKitId(@Param("medKitId") medKitId: UUID): List<Drug>

    @Query(
        """
        SELECT DISTINCT d FROM Drug d 
        JOIN d.usings u
        WHERE u.user.id = :userId
    """
    )
    fun findByUsingsUserId(@Param("userId") userId: UUID): List<Drug>

    @EntityGraph(attributePaths = ["usings"])
    @Query(
        """
    SELECT d FROM Drug d 
    JOIN d.medKit mk
    JOIN mk.users u
    WHERE d.id = :drugId AND u.id = :userId
"""
    )
    fun findByIdAndMedKitUsersIdWithUsings(drugId: UUID, userId: UUID): Drug?

    /**
     * Берёт препарат под блокировку на запись.
     *
     * **Без `@EntityGraph` намеренно, и это не мелочь.** С fetch join по коллекции Hibernate
     * не может поставить блокировку в тот же оператор и переходит на follow-on locking: сперва
     * читает данные соединением с `usings`, потом отдельным запросом берёт
     * `for no key update of tbl`. Строка в итоге заперта, но между двумя операторами препарат
     * успевает изменить кто угодно — то есть блокировка перестаёт защищать от гонки, ради
     * которой стоит. Проверено по сгенерированному SQL; поведенческий тест этого не ловит,
     * он смотрит на конечное состояние, а не на атомарность.
     *
     * Кому нужны планы — догружает их [findWithUsingsById] уже под этой блокировкой.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """
        SELECT d FROM Drug d
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun findByIdAndMedKitUsersIdForUpdate(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    /**
     * Тот же препарат, но с инициализированной коллекцией планов.
     *
     * Нужен там, где работает каскад: `Drug.usings` объявлена с `CascadeType.ALL` и
     * `orphanRemoval`, но по неинициализированной коллекции каскад проходит по пустому набору,
     * и удаление препарата упирается в внешний ключ `usings_drug_fkey`.
     *
     * Дело именно в семантике, а не в числе запросов: `usings.user` в графе не указан
     * намеренно — `Using.user` объявлен EAGER, и Hibernate присоединяет пользователей сам,
     * одним оператором. Замерено, см. `StatementCountTest`.
     *
     * Проверять доступ здесь не нужно — вызывается после
     * [findByIdAndMedKitUsersIdForUpdate], который его и проверил.
     */
    @EntityGraph(attributePaths = ["usings"])
    fun findWithUsingsById(@Param("id") id: UUID): Drug?

    @Query(
        """
        SELECT d FROM Drug d 
        JOIN d.medKit mk
        JOIN mk.users u
        WHERE d.id = :drugId AND u.id = :userId
    """
    )
    fun findByIdAndMedKitUsersId(@Param("drugId") drugId: UUID, @Param("userId") userId: UUID): Drug?

    // In DrugRepository
    @EntityGraph(attributePaths = ["usings"])
    fun findAllWithUsingsByMedKitId(medKitId: UUID): List<Drug>

//    @Lock(LockModeType.PESSIMISTIC_WRITE)
//    @EntityGraph(attributePaths = ["usings"])
//    @Query("SELECT d FROM Drug d LEFT JOIN FETCH d.usings WHERE d.id = :id")
//    fun findWithUsingsByIdForUpdate(drugId: UUID): Drug?


//    @Query("SELECT COALESCE(SUM(u.plannedAmount), 0.0) FROM Using u WHERE u.drug.id = :drugId")
//    fun sumPlannedAmount(@Param("drugId") drugId: UUID): Double


}