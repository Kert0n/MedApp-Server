package org.kert0n.medappserver.queryplan

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Данные, на которых план запроса вообще имеет смысл.
 *
 * На десятке строк планировщик честно предпочитает полный проход: читать всю таблицу дешевле,
 * чем ходить в индекс. Мерить на таком объёме — значит проверять не запрос, а самого себя.
 *
 * Наполняется одним оператором на таблицу, а не построчно через приложение: сорок тысяч строк
 * через сервисы это минуты, а проверяем мы не их. После наполнения — `ANALYZE`: без свежей
 * статистики планировщик выбирает вслепую, и падения были бы случайными.
 */
@Component
class LargeFixture(private val jdbc: JdbcTemplate) {

    private val logger = LoggerFactory.getLogger(LargeFixture::class.java)

    fun ensurePopulated() {
        if (count("user_drugs") >= DRUGS) {
            logger.debug("Fixture already in place")
            return
        }

        logger.info("Populating the query-plan fixture")
        STATEMENTS.forEach(jdbc::execute)
        TABLES.forEach { jdbc.execute("ANALYZE $it") }
        logger.info(
            "Fixture ready: {} drugs, {} reservations, {} catalogue entries",
            count("user_drugs"), count("reservations"), count("parsed_drugs")
        )
    }

    fun count(table: String): Long =
        jdbc.queryForObject("SELECT count(*) FROM $table", Long::class.java) ?: 0

    private companion object {
        const val USERS = 200
        const val MED_KITS = 300
        const val MEMBERS_PER_KIT = 3
        const val DRUGS = 10_000
        const val CATALOGUE = 18_000

        val TABLES = listOf(
            "users", "med_kits", "user_med_kits", "quantity_units", "form_types",
            "user_drugs", "reservations", "parsed_drugs"
        )

        val STATEMENTS = listOf(
            """
            INSERT INTO users (id, hashed_key)
            SELECT gen_random_uuid(), 'hash-' || g FROM generate_series(1, $USERS) g
            """,
            """
            INSERT INTO med_kits (id, version)
            SELECT gen_random_uuid(), 0 FROM generate_series(1, $MED_KITS) g
            """,
            """
            INSERT INTO quantity_units (id, name)
            SELECT gen_random_uuid(), 'unit-' || g FROM generate_series(1, 5) g
            """,
            """
            INSERT INTO form_types (id, name)
            SELECT gen_random_uuid(), 'form-' || g FROM generate_series(1, 5) g
            """,
            // Каждая аптечка получает несколько участников: без них бронь невыразима — она
            // ссылается на членство составным ключом.
            """
            INSERT INTO user_med_kits (med_kit_id, user_id)
            SELECT k.id, u.id
            FROM (SELECT id, row_number() OVER () AS n FROM med_kits) k
            CROSS JOIN generate_series(0, ${MEMBERS_PER_KIT - 1}) AS s(i)
            JOIN (SELECT id, row_number() OVER () AS n FROM users) u
              ON u.n = ((k.n * $MEMBERS_PER_KIT + s.i) % $USERS) + 1
            """,
            """
            INSERT INTO user_drugs (
                id, name, quantity, quantity_unit_id, med_kit_id, version, reservations_version, reservations_total
            )
            SELECT gen_random_uuid(), 'Препарат ' || g.i, 100, un.id, k.id, 0, 0, 0
            FROM generate_series(1, $DRUGS) AS g(i)
            JOIN (SELECT id, row_number() OVER () AS n FROM med_kits) k
              ON k.n = ((g.i - 1) % $MED_KITS) + 1
            CROSS JOIN (SELECT id FROM quantity_units LIMIT 1) un
            """,
            // Бронь каждого участника на каждую пачку его аптечки: столько их и бывает в жизни.
            """
            INSERT INTO reservations (user_id, drug_id, med_kit_id, amount)
            SELECT m.user_id, d.id, d.med_kit_id, 1.5
            FROM user_drugs d
            JOIN user_med_kits m ON m.med_kit_id = d.med_kit_id
            """,
            // Сумма приводится к строкам: приложение ведёт её дельтами, а фикстур пишет напрямую.
            """
            UPDATE user_drugs d
            SET reservations_total = COALESCE(
                (SELECT sum(amount) FROM reservations r WHERE r.drug_id = d.id), 0
            )
            """,
            """
            INSERT INTO parsed_drugs (id, name, name_lat, active_substance, manufacturer, otc)
            SELECT gen_random_uuid(), 'Аспирин ' || g, 'Aspirin ' || g,
                   'вещество ' || (g % 500), 'Завод ' || (g % 200), false
            FROM generate_series(1, $CATALOGUE) g
            """
        ).map { it.trimIndent() }
    }
}
