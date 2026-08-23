@file:UseSerializers(BigDecimalAsString::class)

package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

/**
 * Как выглядит величина на проводе: до 13 цифр до точки и до 6 после — та же разрядность, что у
 * колонок `numeric(19, 6)`.
 *
 * Образцом, а не `maximum` с `multipleOf`: величина едет строкой, а на строке числовые границы
 * ничего не значат. Разрядность проверяет валидация, но springdoc не переносит `@Digits` в
 * опубликованную схему — без образца контракт умалчивал бы о правиле, которое сервер применяет.
 * Согласие с `QUANTITY_PRECISION` и `QUANTITY_SCALE` сторожит тест.
 */
const val QUANTITY_PATTERN: String = "^\\d{1,13}(\\.\\d{1,6})?$"

/*
 * Примеры величин пишутся через `examples`, а не `example`.
 *
 * Вид примера springdoc выводит не из объявленного `type`, а из типа свойства: у `BigDecimal` он
 * разбирает `example = "100.000000"` в число и печатает `100.0` — хвостовые нули пропадают, и
 * контракт показывает не то, что отдаёт сервер. `examples` он оставляет текстом как есть.
 *
 * В ответах пример написан так, как значение придёт: с шестью знаками. В запросах — так, как его
 * шлют: клиент вправе прислать «5.0», и образец это допускает.
 */

/**
 * То же, но строго больше нуля: столько можно попросить, а не столько может лежать.
 *
 * Отдельным образцом, потому что ноль в ответе законен — пачку допили, броней нет, — а в
 * запросе нет: «принял ноль таблеток» и «забронировал ноль» не события. Числовых границ на
 * строке не бывает, поэтому без второго образца контракт умолчал бы о правиле, которое сервер
 * применяет через `@PositiveQuantity`.
 */
const val POSITIVE_QUANTITY_PATTERN: String = "^(?!0+(\\.0+)?$)\\d{1,13}(\\.\\d{1,6})?$"

@Schema(description = "Drug stock and descriptive fields")
@Serializable
data class DrugDTO(
    @Schema(description = "Drug identifier")
    val id: Uuid,
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @Schema(description = "Current stock", examples = ["100.000000"], type = "string", pattern = QUANTITY_PATTERN)
    val quantity: BigDecimal,
    /**
     * Только идентификаторы: имя единицы и формы клиент разворачивает сам.
     *
     * Словари он тянет один раз и держит у себя; посылать «имя» рядом с «идентификатором для
     * получения имени» в каждой пачке — платить трафиком за то, что у клиента уже есть.
     */
    @Schema(description = "Quantity unit identifier")
    val quantityUnitId: Uuid,
    @Schema(description = "Dosage form identifier")
    val formTypeId: Uuid?,
    @Schema(description = "Category", example = "painkiller")
    val category: String?,
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @Schema(description = "Country", example = "Germany")
    val country: String?,
    @Schema(description = "Description")
    val description: String?,
    @Schema(description = "Medicine kit the drug belongs to")
    val medKitId: Uuid,
    /**
     * Версия состояния самой пачки — то, что команда упаковки обязана предъявить.
     *
     * Полем, а не заголовком: ответ несёт два независимых состояния — саму пачку и заявленное
     * на неё, — и подгонять модель под один `ETag` незачем. Списочные чтения тега и вовсе не
     * несут, а клиенту, уходящему в офлайн, версии нужны именно оттуда.
     */
    @Schema(description = "Version of the package state; state it back when commanding", example = "3")
    val version: Long
)

/** Аптечка задаётся путём, поэтому её идентификатора в теле нет. */
@Schema(description = "Request to add a drug to a medicine kit")
@Serializable
data class DrugCreateRequest(
    @field:NotNull
    @field:Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String,

    @field:NotNull
    @field:PositiveQuantity
    @Schema(
        description = "Initial stock, greater than zero", examples = ["100.0"]
    )
    val quantity: BigDecimal,

    @field:NotNull
    @Schema(description = "Quantity unit identifier from the shared vocabulary")
    val quantityUnitId: Uuid,

    @Schema(description = "Dosage form identifier from the shared vocabulary")
    val formTypeId: Uuid? = null,

    @field:Size(max = 200)
    @Schema(description = "Category", example = "painkiller")
    val category: String? = null,

    @field:Size(max = 300)
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String? = null,

    @field:Size(max = 100)
    @Schema(description = "Country", example = "Germany")
    val country: String? = null,

    @field:Size(max = 4000)
    @Schema(description = "Description")
    val description: String? = null
)

/**
 * Частичное изменение: `null` значит «не трогать это поле».
 *
 * Поэтому очистить необязательное поле этим запросом нельзя — отличить «не передал» от
 * «передал пустое» в такой схеме невозможно, и притворяться, что можно, хуже, чем сказать.
 */
@Schema(description = "Partial update of a drug")
@Serializable
data class DrugPatchRequest(
    @field:Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String? = null,

    @field:PositiveQuantity
    @Schema(
        description = "Corrected stock: you recounted the package and saw a different number. " +
            "This is a correction, not a refill — a new pack is a new package. Reservations are " +
            "left alone.",
        examples = ["120.0"]
    )
    val quantity: BigDecimal? = null,

    @Schema(description = "Quantity unit identifier from the shared vocabulary")
    val quantityUnitId: Uuid? = null,

    @Schema(description = "Dosage form identifier from the shared vocabulary")
    val formTypeId: Uuid? = null,

    @field:Size(max = 200)
    @Schema(description = "Category", example = "painkiller")
    val category: String? = null,

    @field:Size(max = 300)
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String? = null,

    @field:Size(max = 100)
    @Schema(description = "Country", example = "Germany")
    val country: String? = null,

    @field:Size(max = 4000)
    @Schema(description = "Description")
    val description: String? = null,

    @Schema(
        description =
            "Version the command acts on; taken from the last read. Absent means 428, mismatched means 412",
        example = "3"
    )
    val version: Long? = null
)

@Schema(description = "Catalogue entry used as a template for a new drug")
@Serializable
data class DrugTemplateDTO(
    @Schema(description = "Template identifier")
    val id: Uuid,
    @Schema(description = "Drug name", example = "Аспирин")
    val name: String,
    // Поля, по которым идёт поиск: без них выдача не объясняет, почему запись нашлась.
    @Schema(description = "International name in Latin script", example = "Aspirin")
    val nameLat: String?,
    @Schema(description = "Active substance", example = "Acetylsalicylic acid")
    val activeSubstance: String?,
    @Schema(description = "Dosage form identifier")
    val formTypeId: Uuid?,
    @Schema(description = "Category")
    val category: String?,
    @Schema(description = "Quantity unit identifier")
    val quantityUnitId: Uuid?,
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @Schema(description = "Country", example = "Германия")
    val country: String?,
    @Schema(description = "Description")
    val description: String?
)

/**
 * Запись общего словаря — единица измерения или форма выпуска.
 *
 * Клиент выбирает из списка и присылает идентификатор: имя у единицы одно на всю систему.
 */
@Schema(description = "Shared vocabulary entry")
@Serializable
data class VocabularyEntryDTO(
    @Schema(description = "Identifier")
    val id: Uuid,
    @Schema(description = "Display name", example = "mg")
    val name: String
)

/**
 * Что заявлено на упаковку бронями.
 *
 * Две величины, а не одна: общая сумма нужна, чтобы понять, разобрана пачка или нет, а своя
 * доля — чтобы показать её владельцу. Сумма может превышать остаток, и это не ошибка: чью
 * бронь ужать, решает её владелец, а не сервер.
 */
@Schema(description = "What is claimed on the package")
@Serializable
data class ReservationsDTO(
    @Schema(description = "Sum of all reservations; may exceed the stock", examples = ["40.000000"], type = "string", pattern = QUANTITY_PATTERN)
    val total: BigDecimal,
    @Schema(
        description = "Reserved by the caller; absent when they claimed nothing",
        type = "string", pattern = POSITIVE_QUANTITY_PATTERN
    )
    val mine: BigDecimal?,
    /**
     * Версия всей картины броней на этой упаковке.
     *
     * Принадлежит картине, а не отдельной брони: изменить свою долю, не увидев, сколько
     * заявлено всего, нельзя — иначе решение принимается вслепую.
     */
    @Schema(description = "Version of the claims on this package; state it back when commanding", example = "5")
    val version: Long
)

/**
 * Упаковка вместе с тем, что на неё заявлено.
 *
 * Разделено намеренно: `drug` — состояние самой пачки, и только за него она отвечает;
 * `reservations` считается снаружи и меняется от чужих действий. Слитые в один объект, они
 * дали бы тег версии, который меняется от того, к чему пачка отношения не имеет.
 */
@Schema(description = "Package with what is claimed on it")
@Serializable
data class DrugSnapshotDTO(
    @Schema(description = "The package itself")
    val drug: DrugDTO,
    @Schema(description = "What is claimed on it")
    val reservations: ReservationsDTO
)
