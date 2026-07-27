package org.kert0n.medappserver.integration

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.api.UsingCreateDTO
import org.kert0n.medappserver.db.model.Drug
import org.kert0n.medappserver.db.model.MedKit
import org.kert0n.medappserver.db.model.User
import org.kert0n.medappserver.db.model.toQuantityScale
import org.kert0n.medappserver.db.repository.DrugRepository
import org.kert0n.medappserver.db.repository.MedKitRepository
import org.kert0n.medappserver.db.repository.UserRepository
import org.kert0n.medappserver.services.models.UsingService
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID

/**
 * Сколько стоит арифметика на `BigDecimal` — и сколько она значит на фоне самой операции.
 *
 * Вопрос возник по делу: количества считаются в каждом приёме, и `BigDecimal` заметно дороже
 * `Double`. Но «дороже» само по себе решения не обосновывает — важна доля в стоимости запроса,
 * который целиком включает `SELECT ... FOR UPDATE`, `UPDATE`, пересчёт `@Formula` и коммит.
 * Поэтому здесь два измерения рядом:
 *
 *  1. чистая арифметика в той же последовательности, что в `UsingService.recordIntake`;
 *  2. тот же `recordIntake` против настоящего Postgres.
 *
 * Тест **измеряет, а не проверяет**, поэтому в обычном прогоне пропускается — иначе он
 * замедлял бы CI и падал бы от нагрузки на машине сборки. Запуск:
 *
 *     ./gradlew test --tests "*QuantityArithmeticBenchmarkTest*" -DrunBenchmark=true
 *
 * Точность честно ограниченная: это не JMH, замер даёт порядок величины, а не третий знак.
 * Для вопроса «арифметика или круговорот до базы» этого достаточно с большим запасом.
 *
 * Что получилось на машине разработчика (JDK 21, Postgres 18 в контейнере, 26.07.2026):
 * `BigDecimal` — 26 нс на операцию, `Double` — 6 нс, то есть в 4,4 раза дороже; сам
 * `recordIntake` — 2,0 мс. Арифметика занимает 0,0013 % запроса, откат на `Double` сэкономил
 * бы 0,001 % — одну стотысячную. Цифры машинно-зависимые, важен только порядок.
 */
@PostgresIntegrationTest
class QuantityArithmeticBenchmarkTest {

    @Autowired private lateinit var userRepository: UserRepository
    @Autowired private lateinit var medKitRepository: MedKitRepository
    @Autowired private lateinit var drugRepository: DrugRepository
    @Autowired private lateinit var usingService: UsingService

    @Test
    fun `доля арифметики в стоимости приёма`() {
        assumeTrue(
            System.getProperty("runBenchmark") == "true",
            "замер выключен; включается -DrunBenchmark=true"
        )

        val decimalNanos = measureArithmetic(::stepBigDecimal)
        val doubleNanos = measureArithmetic(::stepDouble)
        val intakeNanos = measureIntake()

        println(
            """
            |
            |Арифметика количеств, нс на одну операцию приёма
            |  BigDecimal(19,6) : ${"%.1f".format(decimalNanos)}
            |  Double           : ${"%.1f".format(doubleNanos)}
            |  отношение        : ${"%.1f".format(decimalNanos / doubleNanos)}x
            |
            |recordIntake против Postgres
            |  на вызов         : ${"%.3f".format(intakeNanos / 1_000_000.0)} мс
            |  из них арифметика: ${"%.4f".format(100.0 * decimalNanos / intakeNanos)} %
            |  выигрыш от Double: ${"%.4f".format(100.0 * (decimalNanos - doubleNanos) / intakeNanos)} %
            |
            """.trimMargin()
        )
    }

    /**
     * Последовательность из `recordIntake`: две проверки границ, три вычитания, отсечение по
     * нулю и приведение scale (его делают сеттеры количеств).
     *
     * Возвращает накопитель, чтобы результат нельзя было выбросить как мёртвый код.
     */
    private fun stepBigDecimal(iterations: Int): Any {
        var quantity = SEED_DECIMAL
        var planned = SEED_DECIMAL
        var total = SEED_DECIMAL
        for (i in 0 until iterations) {
            // Расход меняется по индексу: с константой JIT вынес бы вычисление из цикла.
            val consumed = CONSUMED_DECIMAL[i and 7]
            // Проверки границ входят в замер: они тоже часть операции. Сработать они не могут —
            // запас на порядки больше суммарного расхода, — а error вместо return нужен, чтобы
            // укороченный цикл не выдал заниженное время за хороший результат.
            if (consumed > planned || consumed > quantity) error("запас кончился на итерации $i")
            planned = maxOf(BigDecimal.ZERO, planned - consumed).toQuantityScale()
            quantity = (quantity - consumed).toQuantityScale()
            total = (total - consumed).toQuantityScale()
            if (planned.signum() == 0) planned = SEED_DECIMAL
        }
        return listOf(quantity, planned, total)
    }

    /** Тот же расчёт на `Double` — то, к чему предлагалось откатиться. */
    private fun stepDouble(iterations: Int): Any {
        var quantity = SEED_DOUBLE
        var planned = SEED_DOUBLE
        var total = SEED_DOUBLE
        for (i in 0 until iterations) {
            val consumed = CONSUMED_DOUBLE[i and 7]
            if (consumed > planned || consumed > quantity) error("запас кончился на итерации $i")
            planned = maxOf(0.0, planned - consumed)
            quantity -= consumed
            total -= consumed
            if (planned == 0.0) planned = SEED_DOUBLE
        }
        return listOf(quantity, planned, total)
    }

    /**
     * Прогрев обязателен: без него в замер попадает интерпретатор и компиляция, и разница
     * между типами оказывается вдвое-втрое больше настоящей.
     */
    private fun measureArithmetic(step: (Int) -> Any): Double {
        repeat(3) { step(WARMUP_ITERATIONS) }
        val started = System.nanoTime()
        val sink = step(MEASURED_ITERATIONS)
        val elapsed = System.nanoTime() - started
        // Ссылка на результат после замера времени — иначе JIT вправе удалить весь цикл.
        check(sink.toString().isNotEmpty())
        return elapsed.toDouble() / MEASURED_ITERATIONS
    }

    /**
     * Настоящий приём: HTTP-слой не участвует, но всё, что ниже сервиса, — да. Класс не
     * `@Transactional` намеренно, иначе все вызовы шли бы в одной транзакции без коммитов и
     * замер потерял бы главную составляющую.
     */
    private fun measureIntake(): Double {
        val stock = qty(INTAKE_CALLS * 2.0)
        val user = userRepository.save(User(hashedKey = "{noop}bench-${UUID.randomUUID()}"))
        val medKit = medKitRepository.save(MedKit())
        medKit.users.add(user)
        user.medKits.add(medKit)
        medKitRepository.save(medKit)
        val drug = drugRepository.save(
            Drug(
                name = "Бенчмарк", quantity = stock, quantityUnit = "таб",
                formType = null, category = null, manufacturer = null,
                country = null, description = null, medKit = medKit
            )
        )
        usingService.createTreatmentPlan(user.id, UsingCreateDTO(drug.id, stock))

        val one = qty(1.0)
        repeat(INTAKE_WARMUP) { usingService.recordIntake(user.id, drug.id, one) }

        val started = System.nanoTime()
        repeat(INTAKE_CALLS) { usingService.recordIntake(user.id, drug.id, one) }
        return (System.nanoTime() - started).toDouble() / INTAKE_CALLS
    }

    private companion object {
        const val WARMUP_ITERATIONS = 200_000
        const val MEASURED_ITERATIONS = 2_000_000
        const val INTAKE_WARMUP = 50
        const val INTAKE_CALLS = 300

        // Со scale 6 — ровно как значения, приходящие из numeric(19,6).
        val SEED_DECIMAL: BigDecimal = BigDecimal("1000000.000000")
        const val SEED_DOUBLE = 1_000_000.0

        // Расход мал настолько, что за все итерации запас не кончится: ветка пополнения
        // существует только чтобы значения не стали отрицательными на длинном прогоне.
        val CONSUMED_DECIMAL: List<BigDecimal> = (1..8).map { BigDecimal.valueOf(it.toLong(), 6) }
        val CONSUMED_DOUBLE: DoubleArray = DoubleArray(8) { (it + 1) * 1e-6 }
    }
}
