package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Best GAS Mix by diving agency (Settings › Diving Agency). */
class GasAgencyTest {

    private val env = UnitsAndPressure.buildEnvironment(GeneralSettings())

    private fun rec(agency: GasAgency, depth: Double, levels: Boolean = false, ccr: Boolean = false, dive: DiveSettings = DiveSettings()) =
        GasCalculator.recommendBestMix(
            depth,
            GeneralSettings(gasAgency = agency, planningType = if (ccr) PlanningType.CCR else PlanningType.OPEN_CIRCUIT),
            dive,
            levels = if (levels) listOf(DiveLevel("a", depth, 5.0)) else emptyList()
        )

    private fun mix(g: Gas) = g.o2Pct to g.hePct

    @Test
    fun tdiUsesAirToFiftyFiveWithEan50AndOxygenOnly() {
        for (d in listOf(40.0, 50.0, 55.0)) {
            val r = rec(GasAgency.TDI, d)
            assertEquals("bottom at $d m", 21 to 0, mix(r.bottomGas))
            assertEquals(listOf(50 to 0, 100 to 0), r.decoGases.map { mix(it) })
            assertNull(r.travelGas)
            assertNull(r.deepDecoGas)
            assertEquals(3, r.plannerGases.size)
            assertTrue(r.notes.first().startsWith("TDI method"))
        }
    }

    @Test
    fun deepAirUsesEan32WhenShallowEnough() {
        assertEquals(32 to 0, mix(rec(GasAgency.TDI, 30.0).bottomGas))
    }

    @Test
    fun deepAirSwitchesToTrimixBeyondTheAgencyLimit() {
        assertTrue(rec(GasAgency.TDI, 60.0).bottomGas.hePct > 0)
        assertEquals(21 to 0, mix(rec(GasAgency.PADI, 50.0).bottomGas))
        assertTrue(rec(GasAgency.PADI, 52.0).bottomGas.hePct > 0)
        assertEquals(21 to 0, mix(rec(GasAgency.IANTD, 52.0).bottomGas))
        assertTrue(rec(GasAgency.IANTD, 54.0).bottomGas.hePct > 0)
        // air must also respect your ppO2 Bottom limit: 1.37 bar at 55 m is above 1.2
        assertTrue(rec(GasAgency.TDI, 55.0, dive = DiveSettings(ppO2Bottom = 1.2)).bottomGas.hePct > 0)
    }

    @Test
    fun gueAndUtdUseStandardGasesByDepth() {
        val gue = mapOf(33.0 to (30 to 30), 45.0 to (21 to 35), 55.0 to (18 to 45), 70.0 to (15 to 55), 90.0 to (10 to 70))
        for ((d, m) in gue) assertEquals("GUE at $d m", m, mix(rec(GasAgency.GUE, d).bottomGas))
        assertEquals(25 to 25, mix(rec(GasAgency.UTD, 38.0).bottomGas))
        val r = rec(GasAgency.GUE, 55.0)
        assertEquals(listOf(50 to 0, 100 to 0), r.decoGases.map { mix(it) })
        assertNull(r.travelGas)
        assertNull(r.deepDecoGas)
    }

    @Test
    fun hypoxicStandardGasGetsA2135Travel() {
        val r = rec(GasAgency.GUE, 70.0)
        assertEquals(15 to 55, mix(r.bottomGas))
        assertNotNull(r.travelGas)
        assertEquals(21 to 35, mix(r.travelGas!!))
        assertEquals(GasRole.TRAVEL, r.travelGas!!.role)
        assertEquals(r.travelGas, r.plannerGases.first())
    }

    @Test
    fun gueRecreationalDiveUsesTriox() {
        val r = rec(GasAgency.GUE, 35.0, levels = true)
        assertTrue(r.isRecreational)
        assertEquals(30 to 30, mix(r.bottomGas))
        val tdi = rec(GasAgency.TDI, 35.0, levels = true)
        assertTrue(tdi.isRecreational)
        assertEquals(0, tdi.bottomGas.hePct)
    }

    @Test
    fun densityAndEndAgenciesUseTheirLimits() {
        val bsac = rec(GasAgency.BSAC, 60.0)
        assertTrue(GasCalculator.densityGL(bsac.bottomGas, 60.0, env) <= 5.25)
        val naui = rec(GasAgency.NAUI, 50.0, dive = DiveSettings(maxEndM = 40.0))
        assertTrue(GasCalculator.endM(naui.bottomGas, 50.0, env, false) <= 31.0)
        // Optimized keeps your own Max END
        val opt = rec(GasAgency.OPTIMIZED, 50.0, dive = DiveSettings(maxEndM = 40.0))
        assertTrue(opt.bottomGas.hePct < naui.bottomGas.hePct)
    }

    @Test
    fun deepAirPlanAlertsAreHighNotCritical() {
        fun plan(agency: GasAgency): DivePlanResult {
            val g = GeneralSettings(gasAgency = agency)
            val r = GasCalculator.recommendBestMix(55.0, g.copy(gasAgency = GasAgency.TDI), DiveSettings())
            return DivePlannerEngine.planDive(DivePlanInput(general = g, gases = r.plannerGases, levels = listOf(DiveLevel("a", 55.0, 20.0))))
        }
        val tdi = plan(GasAgency.TDI)
        assertFalse(tdi.alerts.any { it.level == AlertLevel.CRITICAL && (it.code == AlertCode.DENSITY_HIGH || it.code == AlertCode.END_HIGH) })
        assertTrue(tdi.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.DENSITY_HIGH && it.message.contains("TDI") })
        assertTrue(tdi.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.END_HIGH && it.message.contains("Deep air") })
        // the same air plan under GUE is still CRITICAL
        val gue = plan(GasAgency.GUE)
        assertTrue(gue.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.DENSITY_HIGH })
    }

    @Test
    fun ccrBottomBailoutFollowsTheAgency() {
        assertEquals(21 to 0, mix(rec(GasAgency.TDI, 50.0, ccr = true).bailoutBottomGas!!))
        assertEquals(18 to 45, mix(rec(GasAgency.GUE, 55.0, ccr = true).bailoutBottomGas!!))
        assertEquals(GasRole.BAILOUT, rec(GasAgency.GUE, 55.0, ccr = true).bailoutBottomGas!!.role)
    }

    @Test
    fun everyAgencyProducesAPlannableKitAtEveryDepth() {
        for (a in GasAgency.entries) for (d in listOf(20.0, 35.0, 45.0, 55.0, 70.0, 90.0)) {
            val g = GeneralSettings(gasAgency = a)
            val r = GasCalculator.recommendBestMix(d, g, DiveSettings(), levels = listOf(DiveLevel("a", d, 20.0)))
            val res = DivePlannerEngine.planDive(DivePlanInput(general = g, gases = r.plannerGases, levels = listOf(DiveLevel("a", d, 20.0))))
            assertNull("$a at $d m: ${res.abortedReason}", res.abortedReason)
            val p = UnitsAndPressure.ambientPressure(d, env)
            assertTrue("$a at $d m bottom ppO2", p * r.bottomGas.o2 <= 1.6 + 1e-6)
        }
    }
}
