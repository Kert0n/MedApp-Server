package org.kert0n.medappserver.testutil

import java.math.BigDecimal

/**
 * Количество для тестов: `qty(2.5)` вместо `BigDecimal.valueOf(2.5)`.
 *
 * Именно `valueOf`, а не конструктор `BigDecimal(Double)`: конструктор берёт точное двоичное
 * представление, поэтому `BigDecimal(0.1)` это
 * `0.1000000000000000055511151231257827...`, и такое значение не сойдётся ни с прочитанным
 * из базы, ни с ожидаемым в проверке. `valueOf` идёт через `Double.toString` и даёт ровно
 * `0.1`.
 */
fun qty(value: Double): BigDecimal = BigDecimal.valueOf(value)

/**
 * Сравнение количеств по значению, без учёта scale.
 *
 * `assertEquals` для [BigDecimal] использует `equals`, а он считает `2.5` и `2.500000`
 * разными. Из базы значение приходит с scale 6, в тесте пишется как `2.5` — поэтому обычный
 * assertEquals падал бы на верном результате.
 */
fun assertQty(expected: BigDecimal, actual: BigDecimal, message: String? = null) {
    if (expected.compareTo(actual) != 0) {
        throw AssertionError(
            (message?.plus(": ") ?: "") + "ожидалось $expected, получено $actual"
        )
    }
}

/**
 * Тот же assert для литерала в ожидании и nullable в факте.
 *
 * Nullable здесь не случайность: хелперы вроде `DatabaseTestHelper.drugQuantity` возвращают
 * null, когда препарат удалён, и часть тестов проверяет именно это. Отдельная перегрузка
 * вместо `!!` в каждом месте — и понятнее, и сообщение об ошибке осмысленное.
 *
 * Перегрузки с не-nullable вторым аргументом нет специально: она была бы неоднозначна с этой,
 * и вызовы перестали бы компилироваться с «Type inference failed».
 */
fun assertQty(expected: Double, actual: BigDecimal?, message: String? = null) {
    if (actual == null) {
        throw AssertionError((message?.plus(": ") ?: "") + "ожидалось ${qty(expected)}, получено null")
    }
    assertQty(qty(expected), actual, message)
}
