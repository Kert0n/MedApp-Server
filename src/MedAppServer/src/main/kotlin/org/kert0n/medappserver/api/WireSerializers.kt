package org.kert0n.medappserver.api

import java.math.BigDecimal
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Величина едет строкой, а не числом.
 *
 * Читать нас будет клиент на kotlinx, а у него нет сериализатора `BigDecimal` — свой пришлось
 * бы писать при любой форме на проводе. Раз так, выбор решает то, насколько легко написать его
 * неправильно: из строки значение достаётся ровно одним способом, а из JSON-числа `decodeDouble`
 * тоже компилируется и молча округляет. Наш предел `numeric(19, 6)` в `Double` не помещается,
 * так что округление здесь — не мелочь, а потерянная половина таблетки.
 *
 * `toPlainString`, а не `toString`: второй при большом отрицательном масштабе переходит на
 * экспоненциальную запись, и одно и то же количество получало бы два разных написания.
 */
object BigDecimalAsString : KSerializer<BigDecimal> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    /**
     * Разбор переводится в `SerializationException` намеренно: её конвертер Spring понимает как
     * испорченное тело и отвечает 400. `NumberFormatException` пролетела бы наружу как 500 —
     * ошибкой сервера вместо ошибки запроса.
     */
    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = decoder.decodeString()
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw SerializationException("Not a decimal number: '$text'", e)
        }
    }
}
