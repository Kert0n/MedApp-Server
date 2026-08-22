package org.kert0n.medappserver.services.aggregate

import java.math.BigDecimal
import kotlin.uuid.Uuid

/**
 * Чего просят у агрегата упаковки.
 *
 * Отдельно от `api`, хотя поля те же: агрегатный сервис не должен знать про контракт. Иначе
 * правка формы запроса тянется вниз до правил, а правило начинает зависеть от того, каким
 * способом о нём попросили. Здесь нет ни аннотаций проверки, ни описаний схемы — только то,
 * что нужно самой команде.
 *
 * Единица и форма приходят идентификаторами: разворачивает их сервис, потому что справочник —
 * его собеседник, а не вызывающего.
 */
data class NewDrug(
    val name: String,
    val quantity: BigDecimal,
    val quantityUnitId: Uuid,
    val formTypeId: Uuid? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)

/**
 * Частичное изменение: `null` значит «не трогать это поле».
 *
 * Версия здесь не поле формы, а часть команды: правка предъявлена к тому состоянию, которое
 * клиент видел. Собрать `DrugEdit`, не назвав его, нельзя — параметр обязательный и первый.
 */
data class DrugEdit(
    val stated: Long,
    val name: String? = null,
    val quantity: BigDecimal? = null,
    val quantityUnitId: Uuid? = null,
    val formTypeId: Uuid? = null,
    val category: String? = null,
    val manufacturer: String? = null,
    val country: String? = null,
    val description: String? = null
)
