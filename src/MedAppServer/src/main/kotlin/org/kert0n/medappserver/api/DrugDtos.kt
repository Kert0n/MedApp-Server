package org.kert0n.medappserver.api

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.util.UUID

@Schema(description = "Drug stock and descriptive fields")
data class DrugDTO(
    @Schema(description = "Drug identifier")
    val id: UUID,
    @Schema(description = "Drug name", example = "Aspirin")
    val name: String,
    @Schema(description = "Current stock", example = "100.000000")
    val quantity: BigDecimal,
    /**
     * Сколько на эту пачку заявлено бронями.
     *
     * Справка, а не ограничение: заявленное может превышать остаток, и это нормальное
     * состояние — чью бронь ужать, решает её владелец, а не сервер. «Доступного остатка» как
     * понятия больше нет, поэтому и поля такого нет.
     */
    @Schema(description = "Sum of all reservations on this package; may exceed the stock", example = "40.000000")
    val reservedQuantity: BigDecimal,
    @Schema(description = "Quantity unit identifier")
    val quantityUnitId: UUID,
    @Schema(description = "Quantity unit name", example = "mg")
    val quantityUnit: String,
    @Schema(description = "Dosage form identifier", nullable = true)
    val formTypeId: UUID?,
    @Schema(description = "Dosage form name", example = "tablet")
    val formType: String?,
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
    @Schema(description = "Initial stock, greater than zero", example = "100.0", required = true)
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
    @Schema(
        description = "Corrected stock: you recounted the package and saw a different number. " +
            "This is a correction, not a refill — a new pack is a new package. Reservations are " +
            "left alone.",
        example = "120.0"
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

@Schema(description = "Unplanned consumption of a drug")
data class ConsumptionRequest(
    @field:NotNull
    @field:DecimalMin(value = "0.0", inclusive = false)
    @Schema(description = "Amount consumed, greater than zero", example = "2.0", required = true)
    val quantity: BigDecimal
)

@Schema(description = "Catalogue entry used as a template for a new drug")
data class DrugTemplateDTO(
    @Schema(description = "Template identifier")
    val id: UUID,
    @Schema(description = "Drug name", example = "Аспирин")
    val name: String,
    // Латинское название и действующее вещество — поля, по которым идёт поиск. Без них
    // выдача не объясняет, почему запись нашлась.
    @Schema(description = "International name in Latin script", example = "Aspirin")
    val nameLat: String?,
    @Schema(description = "Active substance", example = "Acetylsalicylic acid")
    val activeSubstance: String?,
    @Schema(description = "Dosage form identifier", nullable = true)
    val formTypeId: UUID?,
    @Schema(description = "Dosage form name", example = "таблетки")
    val formType: String?,
    @Schema(description = "Category")
    val category: String?,
    @Schema(description = "Quantity unit identifier", nullable = true)
    val quantityUnitId: UUID?,
    @Schema(description = "Quantity unit name", example = "шт")
    val quantityUnit: String?,
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
 * Клиент выбирает из списка и присылает идентификатор: имя у одной и той же единицы должно
 * быть одно на всю систему, а не столько, сколько её написали руками.
 */
@Schema(description = "Shared vocabulary entry")
data class VocabularyEntryDTO(
    @Schema(description = "Identifier")
    val id: UUID,
    @Schema(description = "Display name", example = "mg")
    val name: String
)
