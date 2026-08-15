package org.kert0n.medappserver.queryplan

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Синтетика, на которой планы вообще имеют смысл.
 *
 * На десятке строк планировщик всегда выберет `Seq Scan` — и будет прав, так что проверять
 * там нечего. Объёмы ниже подобраны так, чтобы индексный доступ стал выгоднее
 * последовательного, оставаясь достаточно быстрым для сборки.
 *
 * Данные генерирует сама база через `generate_series`: гонять десятки тысяч вставок через
 * Hibernate значило бы ждать минуты ради фикстуры.
 */
@Component
class QueryPlanFixture(private val entityManager: EntityManager) {

    private var seeded = false

    /** Идентификаторы, за которые цепляются сценарии. Заполняет [seed]. */
    lateinit var ownerId: UUID
    lateinit var medKitId: UUID
    lateinit var drugId: UUID
    lateinit var catalogueName: String

    lateinit var snapshotUsers: Map<Int, UUID>
    lateinit var planUsers: Map<Int, UUID>
    lateinit var planDrugs: Map<Int, UUID>
    lateinit var contentMedKits: Map<Int, Pair<UUID, UUID>>
    lateinit var drugsByPlanCount: Map<Int, Pair<UUID, UUID>>

    /** Сколько аптечек у [ownerId]: от этого числа не должно зависеть число запросов. */
    var ownerMedKitCount: Int = 0

    @Transactional
    fun seed() {
        if (seeded) return
        entityManager.createNativeQuery(
            """
            INSERT INTO users (id, hashed_key)
            SELECT gen_random_uuid(), '{noop}k' || i FROM generate_series(1, $USERS) AS i
            """
        ).executeUpdate()

        entityManager.createNativeQuery(
            "INSERT INTO med_kits (id) SELECT gen_random_uuid() FROM generate_series(1, $MED_KITS)"
        ).executeUpdate()

        // По три участника на аптечку — как в жизни, семья или соседи. Раскладка «каждый в
        // каждой» дала бы сотни планов на препарат, план запроса поехал бы под этот объём, и
        // набор проверял бы нагрузку, которой у приложения не бывает.
        entityManager.createNativeQuery(
            """
            INSERT INTO user_med_kits (user_id, med_kit_id)
            SELECT u.id, m.id
            FROM (SELECT id, row_number() OVER () AS n FROM med_kits) m
            JOIN LATERAL (
                SELECT id FROM users OFFSET ((m.n * 3) % $USERS) LIMIT 3
            ) u ON true
            """
        ).executeUpdate()

        entityManager.createNativeQuery(
            """
            INSERT INTO user_drugs (id, name, quantity, quantity_unit, med_kit_id)
            SELECT gen_random_uuid(), 'Препарат ' || i, 1000, 'таб',
                   (SELECT id FROM med_kits OFFSET (i % $MED_KITS) LIMIT 1)
            FROM generate_series(1, $DRUGS) AS i
            """
        ).executeUpdate()

        // Планы только у тех пар «пользователь-препарат», где пользователь имеет доступ к
        // аптечке препарата: иначе выборки по доступу возвращали бы пустоту и план строился
        // бы не на тех данных.
        entityManager.createNativeQuery(
            """
            INSERT INTO usings (user_id, drug_id, planned_amount)
            SELECT umk.user_id, d.id, 10
            FROM user_drugs d
            JOIN user_med_kits umk ON umk.med_kit_id = d.med_kit_id
            """
        ).executeUpdate()

        // Справочные связи обязаны быть заполнены: VidalDrug.formType и quantityUnit
        // объявлены EAGER, и с пустыми ссылками набор просто не проверял бы этот путь.
        entityManager.createNativeQuery(
            "INSERT INTO form_types (id, name) SELECT gen_random_uuid(), 'форма ' || i " +
                "FROM generate_series(1, $FORM_TYPES) AS i"
        ).executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO quantity_units (id, name) SELECT gen_random_uuid(), 'ед ' || i " +
                "FROM generate_series(1, $QUANTITY_UNITS) AS i"
        ).executeUpdate()

        entityManager.createNativeQuery(
            """
            INSERT INTO parsed_drugs (id, name, name_lat, active_substance, manufacturer, otc,
                                      form_type_id, quantity_unit_id)
            SELECT gen_random_uuid(),
                   md5(i::text) || ' таблетки',
                   md5((i + 1)::text) || ' tabs',
                   'вещество ' || (i % 500),
                   'Производитель ' || (i % 300),
                   true,
                   (SELECT id FROM form_types OFFSET (i % $FORM_TYPES) LIMIT 1),
                   (SELECT id FROM quantity_units OFFSET (i % $QUANTITY_UNITS) LIMIT 1)
            FROM generate_series(1, $CATALOGUE) AS i
            """
        ).executeUpdate()

        snapshotUsers = listOf(0, 1, 5, 25).associateWith(::seedSnapshotUser)
        val seededPlans = listOf(0, 1, 250).associateWith(::seedPlanUser)
        planUsers = seededPlans.mapValues { it.value.first }
        planDrugs = seededPlans.mapValues { it.value.second }
        contentMedKits = listOf(0, 10, 100).associateWith(::seedContentMedKit)
        drugsByPlanCount = listOf(0, 10, 100).associateWith(::seedDrugWithPlans)

        // Без ANALYZE планировщик работает по умолчаниям и выбирает план, которого в проде
        // не будет: статистика после массовой вставки ещё не собрана.
        listOf("users", "med_kits", "user_med_kits", "user_drugs", "usings", "parsed_drugs",
            "form_types", "quantity_units")
            .forEach { entityManager.createNativeQuery("ANALYZE $it").executeUpdate() }

        // Название реального препарата из синтетики: искать по нему осмысленно, а по
        // общему префиксу — нет. Первая версия фикстуры давала всем именам общее начало
        // «Каталог », поэтому любой запрос совпадал почти со всей таблицей, и планировщик
        // справедливо выбирал Seq Scan. Тест тогда ловил дефект фикстуры, а не кода.
        catalogueName = entityManager
            .createNativeQuery("SELECT name FROM parsed_drugs OFFSET 500 LIMIT 1")
            .singleResult.toString()

        // Владелец — тот, кто состоит в наибольшем числе аптечек. Со случайным
        // пользователем сценарий выдачи мог бы затронуть одну аптечку, и рост числа
        // запросов от их количества просто не проявился бы.
        ownerId = single(
            """
            SELECT user_id FROM user_med_kits
            GROUP BY user_id ORDER BY count(*) DESC LIMIT 1
            """
        )
        ownerMedKitCount = entityManager
            .createNativeQuery("SELECT count(*) FROM user_med_kits WHERE user_id = '$ownerId'")
            .singleResult.toString().toInt()
        medKitId = single("SELECT med_kit_id FROM user_med_kits WHERE user_id = '$ownerId' LIMIT 1")
        drugId = single("SELECT id FROM user_drugs WHERE med_kit_id = '$medKitId' LIMIT 1")
        seeded = true
    }

    private fun seedSnapshotUser(medKitCount: Int): UUID {
        val userId = UUID.randomUUID()
        insertUser(userId, "snapshot-$medKitCount")
        entityManager.createNativeQuery(
            """
            INSERT INTO user_med_kits (user_id, med_kit_id)
            SELECT '$userId'::uuid, id FROM med_kits ORDER BY id LIMIT $medKitCount
            """
        ).executeUpdate()
        return userId
    }

    private fun seedPlanUser(planCount: Int): Pair<UUID, UUID> {
        val userId = UUID.randomUUID()
        insertUser(userId, "plans-$planCount")
        entityManager.createNativeQuery(
            """
            INSERT INTO user_med_kits (user_id, med_kit_id)
            SELECT DISTINCT '$userId'::uuid, med_kit_id
            FROM (SELECT med_kit_id FROM user_drugs ORDER BY id LIMIT $planCount) selected
            """
        ).executeUpdate()
        entityManager.createNativeQuery(
            """
            INSERT INTO usings (user_id, drug_id, planned_amount)
            SELECT '$userId'::uuid, id, 1 FROM user_drugs ORDER BY id LIMIT $planCount
            """
        ).executeUpdate()
        val drug = if (planCount == 0) UUID.randomUUID() else
            single("SELECT drug_id FROM usings WHERE user_id = '$userId' ORDER BY drug_id LIMIT 1")
        return userId to drug
    }

    private fun seedContentMedKit(drugCount: Int): Pair<UUID, UUID> {
        val userId = UUID.randomUUID()
        val medKitId = UUID.randomUUID()
        insertUser(userId, "content-$drugCount")
        entityManager.createNativeQuery("INSERT INTO med_kits (id) VALUES ('$medKitId')").executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES ('$userId', '$medKitId')"
        ).executeUpdate()
        entityManager.createNativeQuery(
            """
            INSERT INTO user_drugs (id, name, quantity, quantity_unit, med_kit_id)
            SELECT gen_random_uuid(), 'Content ' || i, 100, 'таб', '$medKitId'
            FROM generate_series(1, $drugCount) AS i
            """
        ).executeUpdate()
        return userId to medKitId
    }

    private fun seedDrugWithPlans(planCount: Int): Pair<UUID, UUID> {
        val ownerId = UUID.randomUUID()
        val medKitId = UUID.randomUUID()
        val drugId = UUID.randomUUID()
        insertUser(ownerId, "drug-read-$planCount")
        entityManager.createNativeQuery("INSERT INTO med_kits (id) VALUES ('$medKitId')").executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES ('$ownerId', '$medKitId')"
        ).executeUpdate()
        entityManager.createNativeQuery(
            """
            INSERT INTO user_drugs (id, name, quantity, quantity_unit, med_kit_id)
            VALUES ('$drugId', 'Read fixture', 1000, 'таб', '$medKitId')
            """
        ).executeUpdate()
        repeat(planCount) {
            val planUserId = UUID.randomUUID()
            insertUser(planUserId, "drug-read-plan-${UUID.randomUUID()}")
            entityManager.createNativeQuery(
                "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES ('$planUserId', '$medKitId')"
            ).executeUpdate()
            entityManager.createNativeQuery(
                "INSERT INTO usings (user_id, drug_id, planned_amount) VALUES ('$planUserId', '$drugId', 1)"
            ).executeUpdate()
        }
        return ownerId to drugId
    }

    @Transactional
    fun createDrugCommandFixture(planCount: Int): DrugCommandFixture {
        val ownerId = UUID.randomUUID()
        val medKitId = UUID.randomUUID()
        val drugId = UUID.randomUUID()
        insertUser(ownerId, "drug-command-${UUID.randomUUID()}")
        entityManager.createNativeQuery("INSERT INTO med_kits (id) VALUES ('$medKitId')").executeUpdate()
        entityManager.createNativeQuery(
            "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES ('$ownerId', '$medKitId')"
        ).executeUpdate()
        entityManager.createNativeQuery(
            """
            INSERT INTO user_drugs (id, name, quantity, quantity_unit, med_kit_id)
            VALUES ('$drugId', 'Command fixture', ${maxOf(planCount, 1) * 20}, 'таб', '$medKitId')
            """
        ).executeUpdate()
        repeat(planCount) {
            val planUserId = UUID.randomUUID()
            insertUser(planUserId, "drug-command-plan-${UUID.randomUUID()}")
            entityManager.createNativeQuery(
                "INSERT INTO user_med_kits (user_id, med_kit_id) VALUES ('$planUserId', '$medKitId')"
            ).executeUpdate()
            entityManager.createNativeQuery(
                "INSERT INTO usings (user_id, drug_id, planned_amount) VALUES ('$planUserId', '$drugId', 10)"
            ).executeUpdate()
        }
        return DrugCommandFixture(ownerId, drugId, maxOf(planCount, 1) * 20)
    }

    private fun insertUser(userId: UUID, suffix: String) {
        entityManager.createNativeQuery(
            "INSERT INTO users (id, hashed_key) VALUES ('$userId', '{noop}scale-$suffix')"
        ).executeUpdate()
    }

    private fun single(sql: String): UUID =
        UUID.fromString(entityManager.createNativeQuery(sql).singleResult.toString())

    private companion object {
        const val USERS = 200
        const val MED_KITS = 300
        const val DRUGS = 10_000
        const val CATALOGUE = 18_000
        const val FORM_TYPES = 210
        const val QUANTITY_UNITS = 11
    }
}

data class DrugCommandFixture(val ownerId: UUID, val drugId: UUID, val stock: Int)
