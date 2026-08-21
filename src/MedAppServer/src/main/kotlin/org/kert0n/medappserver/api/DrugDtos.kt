package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Digits
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

/**
 * Числовая граница контракта, та же, что у колонок: `numeric(19, 6)`.
 *
 * Указана в схемах отдельно от `@Digits` намеренно. Проверяет её валидация, но springdoc не
 * переносит `@Digits` в опубликованную схему — без этих двух атрибутов контракт умалчивал бы о
 * правиле, которое сервер применяет. Согласие с `QUANTITY_PRECISION` и `QUANTITY_SCALE`
 * сторожит тест.
 */
const val QUANTITY_MAX: String = "9999999999999.999999"

/** Шаг величины: шесть знаков после точки. */
const val QUANTITY_STEP: Double = 0.000001

@Schema(description = "Drug stock and descriptive fields")
data class DrugDTO(
    @Schema(description = "Drug identifier")
    val id: UUID,
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @Schema(description = "Current stock", example = "100.000000")
    val quantity: BigDecimal,
    /**
     * Только идентификаторы: имя единицы и формы клиент разворачивает сам.
     *
     * Словари он тянет один раз и держит у себя; посылать «имя» рядом с «идентификатором для
     * получения имени» в каждой пачке — платить трафиком за то, что у клиента уже есть.
     */
    @Schema(description = "Quantity unit identifier")
    val quantityUnitId: UUID,
    @Schema(description = "Dosage form identifier", nullable = true)
    val formTypeId: UUID?,
    @Schema(description = "Category", example = "painkiller")
    val category: String?,
    @Schema(description = "Manufacturer", example = "Bayer")
    val manufacturer: String?,
    @Schema(description = "Country", example = "Germany")
    val country: String?,
    @Schema(description = "Description")
    val description: String?,
    @Schema(description = "Medicine kit the drug belongs to")
    val medKitId: UUID
)

/** Аптечка задаётся путём, поэтому её идентификатора в теле нет. */
@Schema(description = "Request to add a drug to a medicine kit")
data class DrugCreateRequest(
    @field:NotNull
    @field:Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin", required = true)
    val name: String,

    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 13, fraction = 6)
    @Schema(
        description = "Initial stock, greater than zero", example = "100.0", required = true,
        maximum = QUANTITY_MAX, multipleOf = QUANTITY_STEP
    )
    val quantity: BigDecimal,

    @field:NotNull
    @Schema(description = "Quantity unit identifier from the shared vocabulary", required = true)
    val quantityUnitId: UUID,

    @Schema(description = "Dosage form identifier from the shared vocabulary")
    val formTypeId: UUID? = null,

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
data class DrugPatchRequest(
    @field:Size(min = 1, max = 300)
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String? = null,

    @field:DecimalMin(value = "0.0", inclusive = false)
    @field:Digits(integer = 13, fraction = 6)
    @Schema(
        description = "Corrected stock: you recounted the package and saw a different number. " +
            "This is a correction, not a refill — a new pack is a new package. Reservations are " +
            "left alone.",
        example = "120.0", maximum = QUANTITY_MAX, multipleOf = QUANTITY_STEP
    )
    val quantity: BigDecimal? = null,

    @Schema(description = "Quantity unit identifier from the shared vocabulary")
    val quantityUnitId: UUID? = null,

    @Schema(description = "Dosage form identifier from the shared vocabulary")
    val formTypeId: UUID? = null,

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

@Schema(description = "Catalogue entry used as a template for a new drug")
data class DrugTemplateDTO(
    @Schema(description = "Template identifier")
    val id: UUID,
    @Schema(description = "Drug name", example = "Аспирин")
    val name: String,
    // Поля, по которым идёт поиск: без них выдача не объясняет, почему запись нашлась.
    @Schema(description = "International name in Latin script", example = "Aspirin")
    val nameLat: String?,
    @Schema(description = "Active substance", example = "Acetylsalicylic acid")
    val activeSubstance: String?,
    @Schema(description = "Dosage form identifier", nullable = true)
    val formTypeId: UUID?,
    @Schema(description = "Category")
    val category: String?,
    @Schema(description = "Quantity unit identifier", nullable = true)
    val quantityUnitId: UUID?,
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
data class VocabularyEntryDTO(
    @Schema(description = "Identifier")
    val id: UUID,
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
data class ReservationsDTO(
    @Schema(description = "Sum of all reservations; may exceed the stock", example = "40.000000")
    val total: BigDecimal,
    @Schema(description = "Reserved by the caller; absent when they claimed nothing", nullable = true)
    val mine: BigDecimal?
)

/**
 * Упаковка вместе с тем, что на неё заявлено.
 *
 * Разделено намеренно: `drug` — состояние самой пачки, и только за него она отвечает;
 * `reservations` считается снаружи и меняется от чужих действий. Слитые в один объект, они
 * дали бы тег версии, который меняется от того, к чему пачка отношения не имеет.
 */
@Schema(description = "Package with what is claimed on it")
data class DrugSnapshotDTO(
    @Schema(description = "The package itself")
    val drug: DrugDTO,
    @Schema(description = "What is claimed on it")
    val reservations: ReservationsDTO
)
