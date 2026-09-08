package org.kert0n.medappserver.services.orchestrator

import java.math.BigDecimal
import kotlin.uuid.Uuid
import org.kert0n.medappserver.domain.Drug
import org.kert0n.medappserver.domain.Reservation
import org.kert0n.medappserver.services.aggregate.DrugService
import org.kert0n.medappserver.services.aggregate.ReservationService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation.MANDATORY
import org.springframework.transaction.annotation.Transactional

/**
 * Заведение брони на упаковку.
 *
 * Правило взаимодействия: **бронь заводится в единице величины той пачки, на которую заведена,
 * и только пока вызывающий допущен к её аптечке.** Обе половины обеспечивает `DrugCommandAccess`:
 * он удерживает корень и перечитывает под ним упаковку.
 *
 * Читает чужой агрегат здесь, а не в `ReservationService`: сервису брони знать про упаковку не
 * положено, иначе он начнёт нести правила соседа. Своё правило — «одна бронь на пару человек и
 * пачка» — остаётся у него, и сюда не переезжает.
 *
 * Оркестратор: домен на входе и на выходе, про клиента не знает.
 */
@Service
class ReservationPlacement(
    private val access: DrugCommandAccess,
    private val reservationService: ReservationService
) {

    /**
     * Вход сценария: доступ к корню удерживается на всю команду.
     *
     * Совместимого режима достаточно, но обойтись без него нельзя: новая строка ссылается ключом
     * на membership, а версии у состава участников нет. Без удержания параллельный выход либо
     * отверг бы вставку нарушением ключа, либо унёс бы её каскадом уже после того, как она
     * попала в сохранённую сумму упаковки.
     */
    @Transactional(propagation = MANDATORY)
    fun place(drugId: Uuid, userId: Uuid, amount: BigDecimal, stated: Long): Reservation =
        placeUnderAccess(access.content(drugId, userId), userId, amount, stated)

    /** Для сценария, который доступ уже удерживает и упаковку под ним уже перечитал. */
    internal fun placeUnderAccess(drug: Drug, userId: Uuid, amount: BigDecimal, stated: Long): Reservation =
        reservationService.create(drug, userId, amount, stated)
}
