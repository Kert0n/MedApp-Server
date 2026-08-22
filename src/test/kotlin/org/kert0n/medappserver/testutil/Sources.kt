package org.kert0n.medappserver.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.streams.asSequence

/**
 * Файлы рабочего кода для правил, которые читают исходники.
 *
 * Правила части 2 проверяются по написанному, а не рефлексией, и каждое начинается с одного и
 * того же обхода каталога. Обход собран здесь по одной причине: `Files.walk` держит открытыми
 * каталоги, пока поток жив, а `asSequence().toList()` его не закрывает — забыть `use` можно
 * ровно один раз на файл, и об этом никто не узнает.
 */
fun sourcesIn(directory: String, suffix: String = ".kt"): List<Path> =
    Files.walk(Path.of("src/main/kotlin/org/kert0n/medappserver").resolve(directory)).use { paths ->
        paths.asSequence().filter { it.name.endsWith(suffix) }.toList()
    }

/** Весь рабочий код — для правил, которым нет дела до слоёв. */
fun allSources(): List<Path> = sourcesIn("")
