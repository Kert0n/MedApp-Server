package org.kert0n.medappserver.db.tables

import org.jetbrains.exposed.v1.core.ReferenceOption.CASCADE
import org.jetbrains.exposed.v1.core.ReferenceOption.NO_ACTION
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Таблицы приложения — единственное описание схемы.
 *
 * `db/schema.sql` порождается отсюда и руками не правится: его монтируют в Postgres compose-файлы,
 * но истина живёт здесь. То, чего Exposed в DDL не выражает, лежит в [SchemaSupplement].
 *
 * Здесь нет ни правил, ни решений: только колонки, ключи и индексы. Связей между таблицами как
 * объектов тоже нет — внешние ключи объявлены значениями, а соединения пишутся в запросе там,
 * где нужны. Именно это и убирает целый класс забот: нечего лениво подгружать, нечего держать
 * согласованным в памяти и нечего очищать после массового запроса.
 */
// Exposed по умолчанию пишет ON UPDATE RESTRICT там, где прежняя схема оставляла NO ACTION.
// Для наших случаев поведение то же — ключи родителей не меняются, — но схема должна совпадать
// с тем, что было, поэтому правило задано явно всюду.
/** Персональных данных нет по замыслу: только идентификатор и хеш ключа. */
object Users : Table("users") {
    val id = uuid("id")
    val hashedKey = varchar("hashed_key", 255).uniqueIndex("ix_users_hashed_key")

    override val primaryKey = PrimaryKey(id, name = "users_pkey")
}

/** У аптечки нет ни владельца, ни названия: участники равноправны. */
object MedKits : Table("med_kits") {
    val id = uuid("id")

    /** Токен предусловия аптечки. Двигают его вступление и выход: состав лежит в другой таблице. */
    val version = long("version").default(0)

    override val primaryKey = PrimaryKey(id, name = "med_kits_pkey")
}

/**
 * Членство. Каскад только со стороны аптечки: удалили аптечку — членства нет.
 *
 * Со стороны пользователя каскада намеренно нет, и это относится ко всем ключам на `users`:
 * удаление человека не является операцией API. Аптечки общие, и каскад молча вынес бы из чужой
 * аптечки чужие данные. Понадобится такая операция — будет явной, а не побочным эффектом
 * `DELETE`.
 */
object MedKitMemberships : Table("user_med_kits") {
    val medKitId = uuid("med_kit_id").references(MedKits.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "user_med_kits_med_kit_fkey")
    val userId = uuid("user_id").references(Users.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_med_kits_user_fkey")

    override val primaryKey = PrimaryKey(medKitId, userId, name = "user_med_kits_pkey")
}

/**
 * Словари формы выпуска и единицы измерения общие для справочника и заведённых руками упаковок.
 *
 * Обе таблицы создаются с `IF NOT EXISTS` — это не украшение: их же создаёт и наполняет дамп
 * справочника, который применяется раньше. Без этого второй по счёту скрипт падал бы на
 * «relation already exists», проверено в обе стороны. Определения совпадают с дамповыми, поэтому
 * схема остаётся самодостаточной и когда дампа нет.
 */
object FormTypes : Table("form_types") {
    val id = uuid("id")
    val name = varchar("name", 100).uniqueIndex("form_types_name_key")

    override val primaryKey = PrimaryKey(id, name = "form_types_pkey")
}

object QuantityUnits : Table("quantity_units") {
    val id = uuid("id")
    val name = varchar("name", 30).uniqueIndex("quantity_units_name_key")

    override val primaryKey = PrimaryKey(id, name = "quantity_units_pkey")
}

/**
 * Препарат в аптечке.
 *
 * `numeric`, а не `double precision`: количество — точная величина, и половина таблетки не
 * должна превращаться в `0.49999999999999994`.
 */
object Drugs : Table("user_drugs") {
    val id = uuid("id")
    val name = varchar("name", 300).index("ix_user_drugs_name")
    val quantity = decimal("quantity", QUANTITY_PRECISION, QUANTITY_SCALE)
    // Ссылки в тот же словарь, которым пользуется справочник: «шт» у заведённой руками упаковки
    // и «шт» у карточки каталога должны быть одной единицей, а не двумя одинаковыми строками.
    // Каскада нет намеренно — словарь переживает упаковки, а упаковка без единицы измерения
    // бессмысленна, поэтому удалить используемую единицу база не даст.
    val quantityUnitId = uuid("quantity_unit_id").references(QuantityUnits.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_drugs_quantity_unit_fkey")
    val formTypeId = uuid("form_type_id").references(FormTypes.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_drugs_form_type_fkey").nullable()
    val category = varchar("category", 200).nullable()
    val manufacturer = varchar("manufacturer", 300).nullable()
    val country = varchar("country", 100).nullable()
    val description = text("description").nullable()
    val medKitId = uuid("med_kit_id")
        .references(MedKits.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "user_drugs_med_kit_fkey")
        .index("ix_user_drugs_med_kit_id")

    /** Токен предусловия упаковки: количество, описание, принадлежность аптечке. */
    val version = long("version").default(0)

    /**
     * Состояние снимка броней, а не упаковки.
     *
     * Колонки соседствуют с упаковкой физически, но принадлежат `ReservationSnapshot`: агрегат
     * `Drug` их не читает, и запрет «броней, их суммы и доступного остатка в агрегате нет и не
     * будет» остаётся в силе. Двигают их сервисы, а не база: ни триггеров, ни вычисляемых
     * колонок здесь нет.
     */
    val reservationsVersion = long("reservations_version").default(0)
    val reservationsTotal = decimal("reservations_total", QUANTITY_PRECISION, QUANTITY_SCALE)
        .default(java.math.BigDecimal.ZERO)

    override val primaryKey = PrimaryKey(id, name = "user_drugs_pkey")

    init {
        // Избыточна при первичном ключе по id, но составной ключ брони может сослаться только
        // на объявленную уникальность. Существует ради него.
        uniqueIndex("user_drugs_id_med_kit_key", id, medKitId)

        // Пустой упаковки не бывает: опустевшая уничтожается, а не остаётся нулём. Правило
        // держит домен, здесь оно продублировано затем, что колонку может тронуть и не он:
        // массовый UPDATE, миграция, рука в psql.
        check("user_drugs_quantity_positive") { quantity greater java.math.BigDecimal.ZERO }

        // Заявленное не бывает отрицательным. Ноль бывает: броней может не быть вовсе.
        check("user_drugs_reservations_total_not_negative") {
            reservationsTotal greaterEq java.math.BigDecimal.ZERO
        }
    }
}

/**
 * Бронь: сколько из этой упаковки человек считает своим.
 *
 * `med_kit_id` продублирован из упаковки не ради удобства чтения, а чтобы бронь могла
 * сослаться на членство: **брони без доступа не существует**, и это держит ключ, а не уборка.
 * Переезд пачки тянет копию за собой, `ON UPDATE CASCADE`.
 */
object Reservations : Table("reservations") {
    val userId = uuid("user_id")
    val drugId = uuid("drug_id")
    val medKitId = uuid("med_kit_id")
    val amount = decimal("amount", QUANTITY_PRECISION, QUANTITY_SCALE)

    override val primaryKey = PrimaryKey(drugId, userId, name = "reservations_pkey")

    init {
        foreignKey(
            drugId to Drugs.id, medKitId to Drugs.medKitId,
            onUpdate = CASCADE, onDelete = CASCADE, name = "reservations_drug_med_kit_fkey"
        )
        foreignKey(
            medKitId to MedKitMemberships.medKitId, userId to MedKitMemberships.userId,
            onDelete = CASCADE, onUpdate = NO_ACTION, name = "reservations_membership_fkey"
        )
        index("ix_reservations_med_kit_user_id", false, medKitId, userId)
        index("ix_reservations_user_id", false, userId)
        // Брони с нулём не бывает: отмена выражается удалением строки.
        check("reservations_amount_positive") { amount greater java.math.BigDecimal.ZERO }
    }
}

/** Справочник из скраппера. Поисковый документ считает сама база, поэтому колонка только читается. */
object DrugTemplates : Table("parsed_drugs") {
    val id = uuid("id")
    val name = varchar("name", 300).index("ix_parsed_drugs_name")
    val nameLat = varchar("name_lat", 300).nullable()
    val formTypeId = uuid("form_type_id")
        .references(FormTypes.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "parsed_drugs_form_type_fkey")
        .nullable()
        .index("ix_parsed_drugs_form_type_id")
    val quantity = integer("quantity").nullable()
    val quantityUnitId = uuid("quantity_unit_id")
        .references(QuantityUnits.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "parsed_drugs_quantity_unit_fkey")
        .nullable()
        .index("ix_parsed_drugs_quantity_unit_id")
    val activeSubstance = varchar("active_substance", 300).nullable()
    val category = varchar("category", 300).nullable()
    val manufacturer = varchar("manufacturer", 300)
    val country = varchar("country", 100).nullable()
    val description = text("description").nullable()
    val otc = bool("otc")

    override val primaryKey = PrimaryKey(id, name = "parsed_drugs_pkey")
}

/**
 * Все таблицы приложения в порядке зависимостей.
 *
 * Порядок значим: составной ключ брони ссылается на уникальность `user_drugs`, и та обязана
 * существовать раньше. Перечисление здесь одно на всех — и для порождения схемы, и для тестовой
 * оснастки, — чтобы новая таблица не оказалась заведена в одном месте и забыта в другом.
 */
val APPLICATION_TABLES: Array<Table> = arrayOf(
    Users, MedKits, MedKitMemberships, FormTypes, QuantityUnits,
    Drugs, Reservations, DrugTemplates
)
