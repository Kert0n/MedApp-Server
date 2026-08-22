package org.kert0n.medappserver.services

import kotlin.uuid.Uuid
import org.springframework.core.convert.converter.Converter
import org.springframework.stereotype.Component

/**
 * Идентификатор в пути и в параметре запроса — тот же `Uuid`, что в теле и в колонке.
 *
 * Без этого Spring умеет разобрать строку только в `java.util.UUID`: свой преобразователь у
 * него зашит, а котлиновский тип он не знает. Тогда идентификатор на границе HTTP пришлось бы
 * объявлять джавовым и переводить в каждом контроллере — то есть держать два типа для одного
 * значения ровно там, где их проще всего перепутать.
 *
 * Разбор бросает `IllegalArgumentException`, Spring заворачивает его в несовпадение типа
 * аргумента и отвечает 400: испорченный путь — ошибка запроса, а не сервера.
 */
@Component
class StringToUuidConverter : Converter<String, Uuid> {

    override fun convert(source: String): Uuid = Uuid.parse(source)
}
