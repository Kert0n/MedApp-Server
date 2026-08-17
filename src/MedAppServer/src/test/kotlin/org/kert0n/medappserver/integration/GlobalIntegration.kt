package org.kert0n.medappserver.integration

import org.kert0n.medappserver.services.models.ReservationService
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.kert0n.medappserver.PostgresIntegrationTest
import org.kert0n.medappserver.services.models.DrugService
import org.kert0n.medappserver.services.models.MedKitService
import org.kert0n.medappserver.testutil.DatabaseTestHelper
import org.kert0n.medappserver.testutil.assertQty
import org.kert0n.medappserver.testutil.qty
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional

/**
 * Tracks planned quantity precisely through every operation.
 *
 * Core invariant that must always hold:
 *   drug.dbHelper.storedPlannedTotal <= drug.quantity
 *
 * After a PLANNED intake (recordIntake):
 *   - The consuming user's plan shrinks by exactly quantityConsumed
 *   - Every other user's plan is UNCHANGED
 *   - drug.quantity shrinks by exactly quantityConsumed
 *   - dbHelper.storedPlannedTotal shrinks by exactly quantityConsumed
 *   - The invariant is preserved trivially (no emergency scaling needed)
 *
 * After an EMERGENCY consumption (consumeDrug directly, bypassing a plan):
 *   - drug.quantity shrinks by quantityConsumed
 *   - If dbHelper.storedPlannedTotal now exceeds quantity, ALL plans scale down proportionally
 *   - dbHelper.storedPlannedTotal == drug.quantity after scaling
 */
@PostgresIntegrationTest
@Transactional
class PlannedQuantityTrackingTests {

    @Autowired
    private lateinit var drugService: DrugService

    @Autowired
    private lateinit var reservationService: ReservationService

    @Autowired
    private lateinit var medKitService: MedKitService


    @Autowired
    private lateinit var dbHelper: DatabaseTestHelper

    // ──────────────────────────────────────────────────────────
    // Test 1 — Planned intake with slack: no scaling should ever occur
    // ──────────────────────────────────────────────────────────


    // ──────────────────────────────────────────────────────────
    // Test 2 — Planned intakes on a FULLY BOOKED drug
    // ──────────────────────────────────────────────────────────


    // ──────────────────────────────────────────────────────────
    // Test 3 — Emergency consumption triggers proportional scaling
    // ──────────────────────────────────────────────────────────


    // ──────────────────────────────────────────────────────────
    // Test 4 — Mixed workflow: planned intakes then emergency
    // ──────────────────────────────────────────────────────────

}
