package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** One test per fix from the Nov 2026 engine audit (items 1 to 7 and 9). */
class AuditFixesTest {

    private val tx1845 = Gas.fromPercent("tx", 18, 45, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 232.0, 2))
    private val ean50 = Gas.fromPercent("ean50", 50, 0, GasRole.DECO, cylinder = CylinderConfig(11.0, 200.0, 1))
    private val o2 = Gas.fromPercent("o2", 100, 0, GasRole.DECO, cylinder = CylinderConfig(7.0, 200.0, 1))
    private val base = DivePlanInput(
        general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
        dive = DiveSettings(),
        gases = listOf(tx1845, ean50, o2),
        levels = listOf(DiveLevel("l1", 60.0, 25.0))
    )

    // 1. CCR descent setpoint is used down to the switch depth
    @Test
    fun fix1_ccrDescentSetpointIsUsed() {
        val ccr = base.copy(general = base.general.copy(planningType = PlanningType.CCR))
        val r07 = DivePlannerEngine.planDive(ccr)
        val r05 = DivePlannerEngine.planDive(ccr.copy(ccr = ccr.ccr.copy(setpointDescent = 0.5)))
        val firstDescent = r07.segments.first { it.type == SegmentType.DESCENT }
        assertEquals("Descent split at the switch depth", ccr.ccr.switchToHighDepthM, firstDescent.toDepthM, 1e-6)
        assertEquals("Descent leg uses the descent setpoint", ccr.ccr.setpointDescent, firstDescent.setpoint!!, 1e-9)
        assertNotEquals(
            "Changing the descent setpoint must change tissue loading",
            r07.endTissues.compartments.first().pN2,
            r05.endTissues.compartments.first().pN2
        )
    }

    // 2. Surface Interval Calculator uses the NEXT dive's gas
    @Test
    fun fix2_nextDiveGasComesFromNextDive() {
        val env = UnitsAndPressure.buildEnvironment(GeneralSettings())
        val dive = DiveSettings()
        val ean39 = Gas.fromPercent("best_bottom", 39, 0, GasRole.BOTTOM)
        assertEquals("EAN39", GasCalculator.defaultNextDiveGas(listOf(ean39), 25.0, dive, env).displayName)
        assertEquals("EAN32", GasCalculator.defaultNextDiveGas(null, 25.0, dive, env).displayName)
        assertEquals("Air", GasCalculator.defaultNextDiveGas(null, 40.0, dive, env).displayName)

        // Reported day: Dive 1 trimix to 82 m, Dive 2 at 25 m. Dive 2's planned gas must not be trimix.
        val day = DivePlannerEngine.planMultiDiveDay(
            DivePlanInput(general = GeneralSettings(gfLow = 70, gfHigh = 70, gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)),
            listOf(
                MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("a", 43.0, 15.0), DiveLevel("b", 82.0, 20.0))),
                MultiDiveEntry("d2", "Dive 2", 90.0, listOf(DiveLevel("c", 25.0, 25.0)))
            ),
            includeContingenciesForIndex = -1
        )
        val next = GasCalculator.defaultNextDiveGas(day.diveResults[1].input.gases, 25.0, dive, env)
        assertEquals("Dive 2 gas is nitrox, not Dive 1's trimix", 0, next.hePct)
    }

    // 3. Rock-bottom uses the first ASCENT gas switch and applies to the bottom gas only
    @Test
    fun fix3_rockBottomUsesAscentSwitch() {
        val rb = base.copy(
            levels = listOf(DiveLevel("l1", 60.0, 12.0)),
            consumption = ConsumptionSettings(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM)
        )
        val travel = Gas.fromPercent("travel", 32, 0, GasRole.TRAVEL, cylinder = CylinderConfig(11.0, 200.0, 1))
        val a = DivePlannerEngine.planDive(rb)
        val b = DivePlannerEngine.planDive(rb.copy(dive = rb.dive.copy(mixChangeExtraTimeMin = 0.0)))
        val c = DivePlannerEngine.planDive(rb.copy(gases = listOf(travel, tx1845, ean50, o2)))
        val env = a.env
        val switchDepth = a.segments.dropWhile { it.type != SegmentType.LEVEL }.first { it.gas.id != "tx" }.fromDepthM
        assertEquals("First ascent switch is EAN50 at 21 m", 21.0, switchDepth, 0.5)
        val expectedRb = OxygenAndConsumption.rockBottomReserveLitres(60.0, switchDepth, rb, env)
        fun reserve(r: DivePlanResult, id: String) = r.gasConsumption.first { it.gas.id == id }.reserveLitres
        val usedA = a.gasConsumption.first { it.gas.id == "tx" }.usedLitres
        assertEquals(max(expectedRb, usedA * 0.5), reserve(a, "tx"), 1.0)
        assertEquals("Mix Change Extra Time must not change rock-bottom", expectedRb, OxygenAndConsumption.rockBottomReserveLitres(60.0, 21.0, rb, env), 1.0)
        assertTrue("Rock-bottom stays from 60 m to 21 m without switch extra time", reserve(b, "tx") >= expectedRb - 1.0)
        val travelGc = c.gasConsumption.first { it.gas.id == "travel" }
        assertEquals("Travel gas gets the 1/3 reserve, not rock-bottom", travelGc.usedLitres * 0.5, travelGc.reserveLitres, 1.0)
    }

    // 4. Lost-gas reserve is a real lost-gas calculation
    @Test
    fun fix4_lostGasDiffersFromMultiplier() {
        val mult = DivePlannerEngine.planDive(base)
        val lost = DivePlannerEngine.planDive(base.copy(consumption = ConsumptionSettings(decoReserveRule = ReserveRuleDeco.LOST_GAS)))
        fun req(r: DivePlanResult, id: String) = r.gasConsumption.first { it.gas.id == id }.totalRequiredLitres
        assertTrue(
            "Losing one deco gas raises what the other must carry (EAN50 ${req(mult, "ean50")} vs ${req(lost, "ean50")}, O2 ${req(mult, "o2")} vs ${req(lost, "o2")})",
            req(lost, "ean50") > req(mult, "ean50") + 1.0 || req(lost, "o2") > req(mult, "o2") + 1.0
        )
        val worst = DivePlannerEngine.lostGasWorstCaseUsage(base)
        val usedEan50 = mult.gasConsumption.first { it.gas.id == "ean50" }.usedLitres
        assertEquals(max(usedEan50, worst["ean50"] ?: 0.0) * 1.5, req(lost, "ean50"), 1.0)
        val lost2 = DivePlannerEngine.planDive(base.copy(consumption = ConsumptionSettings(decoReserveRule = ReserveRuleDeco.LOST_GAS, stressFactor = 2.0)))
        assertTrue("Stress factor scales the lost-gas requirement", req(lost2, "ean50") > req(lost, "ean50"))
    }

    // 5. Travel gas metrics are shown where the travel gas is breathed
    @Test
    fun fix5_travelGasDisplayDepth() {
        val env = UnitsAndPressure.buildEnvironment(GeneralSettings())
        val dive = DiveSettings()
        val travel = Gas.fromPercent("t", 23, 37, GasRole.TRAVEL)
        val d = GasCalculator.displayEvalDepthM(travel, 82.0, dive, env)
        assertTrue("Travel evaluated at or above its MOD ($d m)", d <= GasCalculator.modExactM(travel, dive.ppO2Bottom, env) + 1e-6)
        assertTrue("Travel END at its depth is within 30 m", GasCalculator.endM(travel, d, env, dive.o2Narcotic) < 30.0)
        assertEquals(82.0, GasCalculator.displayEvalDepthM(Gas.fromPercent("b", 15, 67, GasRole.BOTTOM), 82.0, dive, env), 1e-9)
        assertEquals(21.0, GasCalculator.displayEvalDepthM(ean50, 82.0, dive, env), 1e-9)
    }

    // 6. Red Sea density cannot silently become a stale custom value
    @Test
    fun fix6_redSeaDensity() {
        assertEquals(1030.0, GeneralSettings(salinityPreset = SalinityPreset.RED_SEA, customSalinityKgM3 = 1000.0).effectiveSalinity, 1e-9)
        assertEquals(1028.0, GeneralSettings(salinityPreset = SalinityPreset.RED_SEA, customSalinityKgM3 = 1028.0).effectiveSalinity, 1e-9)
        assertEquals(1000.0, GeneralSettings(salinityPreset = SalinityPreset.CUSTOM, customSalinityKgM3 = 1000.0).effectiveSalinity, 1e-9)
    }

    // 7. PPN2 Max Deco now raises a MEDIUM alert
    @Test
    fun fix7_ppN2DecoAlert() {
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val airDive = base.copy(gases = listOf(air), levels = listOf(DiveLevel("a", 40.0, 30.0)))
        val normal = DivePlannerEngine.planDive(airDive)
        assertNull(normal.alerts.firstOrNull { it.code == AlertCode.PPN2_HIGH_DECO })
        val strict = DivePlannerEngine.planDive(airDive.copy(dive = airDive.dive.copy(ppN2MaxDeco = 1.5)))
        val alert = strict.alerts.firstOrNull { it.code == AlertCode.PPN2_HIGH_DECO }
        assertNotNull("ppN2 on deco above the limit must alert", alert)
        assertEquals(AlertLevel.MEDIUM, alert!!.level)
    }

    // 9. Best GAS Mix never proposes trimix with less than 10% helium
    @Test
    fun fix9_noImpracticalLowHelium() {
        val general = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        for (depth in listOf(42.0, 45.0, 50.0, 55.0, 60.0, 70.0, 90.0)) {
            val rec = GasCalculator.recommendBestMix(depth, general, DiveSettings(), levels = listOf(DiveLevel("x", depth, 20.0)))
            rec.plannerGases.forEach { g ->
                assertTrue("${g.displayName} at $depth m has impractical helium", g.hePct == 0 || g.hePct >= 10)
            }
        }
        val env = UnitsAndPressure.buildEnvironment(general)
        val fixed = GasCalculator.practicalHelium(Gas.fromPercent("t", 34, 1, GasRole.TRAVEL), 21.0, 3.95, DiveSettings(), env)
        assertEquals("TX 34/1 at 21 m becomes EAN34", "EAN34", fixed.displayName)
        assertTrue(abs(fixed.o2 - 0.34) < 1e-9)
    }
}
