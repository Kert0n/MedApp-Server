package org.kert0n.medappserver

import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.testutil.allSources

/**
 * Пессимистическая блокировка разрешена только корню аптечки.
 *
 * Упаковки и снимки броней по-прежнему держатся на версиях. Корень блокируется в двух режимах:
 * исключительном — под жизненный цикл membership и переезд упаковок, и совместимом — под
 * записи, чья строка ссылается ключом на состав участников. Обе формы живут в одном методе
 * `MedKitStore`, и требование ниже проверяет, что оба режима из него не разошлись.
 *
 * `JdbcTemplate` в проде запрещён отдельно: запрос перестаёт быть выражением, а типы — своими.
 * Единственное законное обращение к драйверу живёт в тесте, который нарочно ходит мимо
 * приложения.
 */
class NoPessimisticLockingTest {

    private val production: List<Path> =
        allSources()

    @Test
    fun `в проде нет прямого обращения к драйверу`() {
        assertTrue(production.isNotEmpty(), "рабочих файлов не найдено — тест смотрит не туда")

        assertNothingMentions(
            "JdbcTemplate",
            "хранилище пошло в обход Exposed: запрос перестал быть выражением, а типы — своими"
        )
    }

    @Test
    fun `пессимистическая блокировка не выходит за хранилище аптечки`() {
        val outsideRootProtocol = production.filterNot { it.name == "MedKitStore.kt" }
        listOf("FOR UPDATE", "forUpdate", "LockMode", "PESSIMISTIC").forEach { forbidden ->
            assertNothingMentions(
                forbidden,
                "оба режима блокировки корня разрешены только одному запросу MedKitStore",
                outsideRootProtocol
            )
        }

        val medKitStore = production.single { it.name == "MedKitStore.kt" }.readText()
        assertTrue(medKitStore.contains(".forUpdate("), "корень аптечки не блокируется")
        listOf("ForUpdateOption.PostgreSQL.ForUpdate", "ForUpdateOption.PostgreSQL.ForKeyShare").forEach { mode ->
            assertTrue(medKitStore.contains(mode), "режим $mode уехал из единственного блокирующего запроса")
        }
        assertTrue(
            medKitStore.contains("ForUpdate(null, MedKits)") && medKitStore.contains("ForKeyShare(null, MedKits)"),
            "блокировать полагается только med_kits: без OF под замок уходит и строка membership"
        )
    }

    /**
     * Ищется по тексту, а не по типам: слово в комментарии тоже считается нарушением.
     *
     * Написать «здесь мог бы быть FOR UPDATE» и оставить — ровно тот способ, которым запрет
     * размывается; сообщение называет файл и строку, так что разобраться легко.
     */
    private fun assertNothingMentions(forbidden: String, why: String, sources: List<Path> = production) {
        val offenders = sources.flatMap { file ->
            file.readText().lines().withIndex()
                .filter { (_, line) -> line.contains(forbidden) }
                .map { (number, _) -> "${file.name}:${number + 1}" }
        }

        assertTrue(offenders.isEmpty(), "$forbidden в рабочем коде: $why\n${offenders.joinToString("\n")}")
    }
}
