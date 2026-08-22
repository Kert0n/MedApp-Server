package org.kert0n.medappserver.testutil

import java.io.File
import org.springframework.transaction.PlatformTransactionManager

/**
 * Сколько запросов стоило одно обращение — с записью в отчёт.
 *
 * Гейты утверждают равенство между размерами, а не точное число: константу пришлось бы подгонять
 * после каждой безобидной правки. Но равенство пропускает постоянный перерасход — «всегда пять
 * вместо трёх», — поэтому числа выкладываются рядом, и рост с трёх до пяти виден в отчёте, даже
 * когда все гейты зелёные.
 *
 * Отчёт складывается в `build/reports/queryCounts`, а не в репозиторий: снимок в git шумел бы от
 * любой правки запроса и ничего бы не доказывал. В CI это артефакт — посмотреть, когда числа
 * поехали.
 */
object QueryCount {

    private val report = File("build/reports/queryCounts/counts.txt").apply {
        parentFile.mkdirs()
        writeText("")
    }

    fun of(transactionManager: PlatformTransactionManager, what: String, work: () -> Unit): Int {
        val statements = RecordedSql.inTransaction(transactionManager, work)
        report.appendText("%-52s %3d\n".format(what, statements.size))
        return statements.size
    }
}
