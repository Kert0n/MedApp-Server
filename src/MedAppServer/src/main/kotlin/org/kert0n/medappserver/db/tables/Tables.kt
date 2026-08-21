package org.kert0n.medappserver.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.ReferenceOption.CASCADE
import org.jetbrains.exposed.sql.ReferenceOption.NO_ACTION
import org.kert0n.medappserver.domain.QUANTITY_PRECISION
import org.kert0n.medappserver.domain.QUANTITY_SCALE

/**
 * Таблицы приложения — один в один с `db/schema.sql`.
 *
 * Здесь нет ни правил, ни решений: только колонки, ключи и индексы. Связей между таблицами как
 * объектов тоже нет — внешние ключи объявлены значениями, а соединения пишутся в запросе там,
 * где нужны. Именно это и убирает целый класс забот: нечего лениво подгружать, нечего держать
 * согласованным в памяти и нечего очищать после массового запроса.
 */
// Exposed по умолчанию пишет ON UPDATE RESTRICT там, где прежняя схема оставляла NO ACTION.
// Для наших случаев поведение то же — ключи родителей не меняются, — но схема должна совпадать
// с тем, что было, поэтому правило задано явно всюду.
object Users : Table("users") {
    val id = uuid("id")
    val hashedKey = varchar("hashed_key", 255).uniqueIndex("ix_users_hashed_key")

    override val primaryKey = PrimaryKey(id, name = "users_pkey")
}

/** У аптечки нет ни владельца, ни названия: участники равноправны. */
object MedKits : Table("med_kits") {
    val id = uuid("id")

    override val primaryKey = PrimaryKey(id, name = "med_kits_pkey")
}

/**
 * Членство. Каскад только со стороны аптечки: удалили аптечку — членства нет.
 *
 * Со стороны пользователя каскада намеренно нет: удаление человека не является операцией API,
 * и каскад молча вынес бы из чужой аптечки чужие данные.
 */
object MedKitMemberships : Table("user_med_kits") {
    val medKitId = uuid("med_kit_id").references(MedKits.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "user_med_kits_med_kit_fkey")
    val userId = uuid("user_id").references(Users.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_med_kits_user_fkey")

    override val primaryKey = PrimaryKey(medKitId, userId, name = "user_med_kits_pkey")
}

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
    val quantityUnitId = uuid("quantity_unit_id").references(QuantityUnits.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_drugs_quantity_unit_fkey")
    val formTypeId = uuid("form_type_id").references(FormTypes.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "user_drugs_form_type_fkey").nullable()
    val category = varchar("category", 200).nullable()
    val manufacturer = varchar("manufacturer", 300).nullable()
    val country = varchar("country", 100).nullable()
    val description = text("description").nullable()
    val medKitId = uuid("med_kit_id")
        .references(MedKits.id, onDelete = CASCADE, onUpdate = NO_ACTION, fkName = "user_drugs_med_kit_fkey")
        .index("ix_user_drugs_med_kit_id")

    override val primaryKey = PrimaryKey(id, name = "user_drugs_pkey")

    init {
        // Существует ради составного ключа брони: сослаться можно только на объявленную
        // уникальность. Пустой упаковки не бывает — опустевшая уничтожается, а не остаётся нулём.
        uniqueIndex("user_drugs_id_med_kit_key", id, medKitId)
        check("user_drugs_quantity_positive") { quantity greater java.math.BigDecimal.ZERO }
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
    val formTypeId = uuid("form_type_id").references(FormTypes.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "parsed_drugs_form_type_fkey").nullable()
    val quantity = integer("quantity").nullable()
    val quantityUnitId = uuid("quantity_unit_id")
        .references(QuantityUnits.id, onDelete = NO_ACTION, onUpdate = NO_ACTION, fkName = "parsed_drugs_quantity_unit_fkey").nullable()
    val activeSubstance = varchar("active_substance", 300).nullable()
    val category = varchar("category", 300).nullable()
    val manufacturer = varchar("manufacturer", 300)
    val country = varchar("country", 100).nullable()
    val description = text("description").nullable()
    val otc = bool("otc")

    override val primaryKey = PrimaryKey(id, name = "parsed_drugs_pkey")
}
