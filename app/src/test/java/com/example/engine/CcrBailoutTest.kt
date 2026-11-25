package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CCR Best GAS Mix and worst-point open-circuit bailout (one test per behaviour). */
class CcrBailoutTest {

    private val ccrBest = GeneralSettings(planningType = PlanningType.CCR, gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
    private val ccrUser = GeneralSettings(planningType = PlanningType.CCR, gasSelectionMode = GasSelectionMode.USER_INPUT_GAS)
    private val env = UnitsAndPressure.buildEnvironment(GeneralSettings())
    private val dive = DiveSettings()

    private fun rec(depth: Double, general: GeneralSettings = ccrBest) =
        GasCalculator.recommendBestMix(depth, general, dive, levels = listOf(DiveLevel("a", depth, 25.0)))

    private fun bestPlan(depth: Double, time: Double, contingencies: Boolean = false): DivePlanResult {
        val r = rec(depth)
        return DivePlannerEngine.planDive(
            DivePlanInput(general = ccrBest, gases = r.plannerGases, levels = listOf(DiveLevel("a", depth, time))),
            includeContingencies = contingencies
        )
    }

    private fun g(id: String, o2: Int, he: Int, role: GasRole, l: Double, n: Int = 1) =
        Gas.fromPercent(id, o2, he, role, cylinder = CylinderConfig(l, 200.0, n))

    @Test
    fun diluentIsSizedForPpO2AboutOnePointOneAtDepth() {
        for (depth in listOf(45.0, 60.0, 80.0, 100.0)) {
            val r = rec(depth)
            val ppO2 = UnitsAndPressure.ambientPressure(depth, env) * r.bottomGas.o2
            assertEquals(GasRole.DILUENT, r.bottomGas.role)
            assertTrue("diluent ppO2 $ppO2 at $depth m", ppO2 <= Constants.CCR_DILUENT_PPO2 + 0.005 && ppO2 >= 0.95)
            assertTrue("diluent END at $depth m", GasCalculator.endM(r.bottomGas, depth, env, dive.o2Narcotic) <= dive.maxEndM + 1.0)
        }
    }

    @Test
    fun shallowDiluentIsAirNotNitrox() {
        val r = rec(30.0)
        assertEquals(21, r.bottomGas.o2Pct)
        assertEquals(0, r.bottomGas.hePct)
    }

    @Test
    fun bestMixHasBottomBailoutAndBailoutRolesAndNoTravel() {
        val r = rec(60.0)
        val bb = r.bailoutBottomGas
        assertNotNull(bb)
        assertEquals(GasRole.BAILOUT, bb!!.role)
        val p = UnitsAndPressure.ambientPressure(60.0, env)
        assertTrue("bottom bailout breathable at 60 m", GasCalculator.minDepthM(bb, dive.ppO2Min, env) <= 60.0 && p * bb.o2 <= dive.ppO2Bottom + 0.005)
        assertTrue(r.plannerGases.none { it.role == GasRole.TRAVEL || it.role == GasRole.BOTTOM || it.role == GasRole.DECO })
        assertTrue(r.plannerGases.drop(1).all { it.role == GasRole.BAILOUT })
        assertEquals(1, r.plannerGases.count { it.role == GasRole.DILUENT })
        assertFalse(r.isRecreational)
        // OC Best Mix is unchanged: still a travel/deco kit with DECO roles
        val oc = rec(60.0, GeneralSettings())
        assertNull(oc.bailoutBottomGas)
        assertTrue(oc.decoGases.all { it.role == GasRole.DECO })
    }

    @Test
    fun deepBailoutStageIsAddedBelow54mAndIsIcdSafe() {
        val r = rec(80.0)
        val deep = r.deepDecoGas
        assertNotNull(deep)
        assertEquals(GasRole.BAILOUT, deep!!.role)
        val icd = GasCalculator.evaluateIcd(r.bailoutBottomGas!!, deep, deep.modOverrideM!!, env, AlertSettings())
        assertFalse(icd.violatesDeltaN2)
    }

    @Test
    fun loopNeverBreathesBailoutGases() {
        val res = bestPlan(60.0, 25.0)
        assertTrue(res.segments.all { it.gas.role == GasRole.DILUENT && it.isCcr })
    }

    @Test
    fun bailoutStartsAtDepthAtTheEndOfTheBottomPhase() {
        val res = bestPlan(60.0, 25.0)
        val a = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!!
        val bo = a.worst!!
        val bottomEnd = res.levelEnds.last().runtimeMin
        assertEquals(bottomEnd, bo.input.bailoutStartRuntimeMin!!, 1e-9)
        assertTrue("no descent in a bailout", bo.segments.none { it.type == SegmentType.DESCENT })
        assertEquals(60.0, bo.segments.first().fromDepthM, 1e-9)
        assertEquals(bottomEnd + Constants.CCR_BAILOUT_PROBLEM_SOLVING_MIN, bo.segments.first().runtimeMin, 1e-6)
        assertFalse("bailout is on open circuit", bo.segments.any { it.isCcr })
        assertTrue(bo.totalRuntimeMin > bottomEnd + Constants.CCR_BAILOUT_PROBLEM_SOLVING_MIN)
        // tissues carried over from the loop, not reset: OC bailout deco is longer than the loop deco
        assertTrue(bo.ttsMin > res.ttsMin)
        // CNS continues from the loop
        assertTrue(bo.oxygen.startCnsPct > 0.0)
        // no repetitive-dive alerts in a bailout plan
        assertTrue(bo.alerts.none { it.code == AlertCode.SHORT_SURFACE_INTERVAL || it.code == AlertCode.REVERSE_PROFILE })
    }

    @Test
    fun bestModeSizesBailoutCylindersFromTheBailoutPlan() {
        val res = bestPlan(60.0, 25.0)
        val a = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!!
        for (gas in res.input.gases.filter { it.role == GasRole.BAILOUT }) {
            val need = a.requiredById[gas.id] ?: 0.0
            assertTrue("${gas.displayName} need $need", need > 0.0)
            assertTrue("${gas.displayName} holds ${gas.cylinder.availableLitres} for $need", gas.cylinder.availableLitres + 0.5 >= need)
        }
        assertTrue(res.alerts.none { it.code == AlertCode.CCR_BAILOUT_RISK })
    }

    @Test
    fun bailoutContingencyIsReportedForBestPlan() {
        val res = bestPlan(60.0, 25.0, contingencies = true)
        val bo = res.ccrBailout
        assertNotNull(bo)
        assertFalse("sized bailout must be sufficient", bo!!.hasErrors)
        assertTrue(bo.subtitle.contains("60 m"))
        assertTrue(bo.stops.isNotEmpty())
        val again = DivePlannerEngine.generateCcrBailout(res.input)
        assertEquals(bo.totalRuntimeMin, again.totalRuntimeMin, 1e-6)
    }

    @Test
    fun worstPointIsTheLevelWithTheLongestBailoutAscent() {
        val input = DivePlanInput(
            general = ccrUser,
            gases = listOf(
                g("dil", 15, 55, GasRole.DILUENT, 3.0),
                g("bo_tx", 18, 45, GasRole.BAILOUT, 11.0, 2),
                g("bo_50", 50, 0, GasRole.BAILOUT, 11.0),
                g("bo_o2", 100, 0, GasRole.BAILOUT, 7.0)
            ),
            levels = listOf(DiveLevel("a", 75.0, 15.0), DiveLevel("b", 50.0, 10.0))
        )
        val res = DivePlannerEngine.planDive(input)
        assertEquals(2, res.levelEnds.size)
        val a = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!!
        // requirement per gas is the max over both level ends
        val perLevel = res.levelEnds.map { le ->
            DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, listOf(le))!!
        }
        for ((id, need) in a.requiredById) {
            assertEquals(perLevel.maxOf { it.requiredById[id] ?: 0.0 }, need, 1e-6)
        }
        val worstTts = perLevel.maxOf { it.worst!!.ttsMin }
        assertEquals(worstTts, a.worst!!.ttsMin, 1e-6)
    }

    @Test
    fun missingBottomBailoutFallsBackToDiluentAndAlerts() {
        val res = DivePlannerEngine.planDive(
            DivePlanInput(
                general = ccrUser,
                gases = listOf(g("dil", 18, 45, GasRole.DILUENT, 3.0), g("bo", 50, 0, GasRole.BAILOUT, 11.0)),
                levels = listOf(DiveLevel("a", 60.0, 25.0))
            )
        )
        assertTrue(res.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.CCR_BAILOUT_RISK && it.message.contains("diluent") })
        assertTrue(res.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.CCR_BAILOUT_RISK && it.message.contains("TX 18/45") })
    }

    @Test
    fun undersizedUserBailoutIsCritical() {
        val res = DivePlannerEngine.planDive(
            DivePlanInput(
                general = ccrUser,
                gases = listOf(
                    g("dil", 18, 45, GasRole.DILUENT, 3.0),
                    g("bo_tx", 18, 45, GasRole.BAILOUT, 3.0),
                    g("bo_50", 50, 0, GasRole.BAILOUT, 11.0),
                    g("bo_o2", 100, 0, GasRole.BAILOUT, 7.0)
                ),
                levels = listOf(DiveLevel("a", 60.0, 25.0))
            )
        )
        assertTrue(res.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.CCR_BAILOUT_RISK && it.message.contains("Insufficient bailout gas TX 18/45") })
        assertTrue(res.alerts.none { it.message.contains("diluent") && it.code == AlertCode.CCR_BAILOUT_RISK })
    }

    @Test
    fun bailoutStressRmvAndReserveRulesAreApplied() {
        val base = bestPlan(60.0, 25.0).input.copy(general = ccrUser)
        fun need(c: ConsumptionSettings): Double {
            val r = DivePlannerEngine.planDive(base.copy(consumption = c))
            return DivePlannerEngine.ccrBailoutAnalysis(r.input, r.segments, r.levelEnds)!!.requiredById.values.sum()
        }
        val normal = need(ConsumptionSettings())
        assertTrue(need(ConsumptionSettings(stressFactor = 2.0)) > normal)
        assertTrue(need(ConsumptionSettings(bottomRmvLMin = 25.0)) > normal)
        assertTrue(need(ConsumptionSettings(bottomReserveRule = ReserveRuleBottom.HALF)) > normal)
        // lost-gas and rock-bottom rules are mapped for the bailout and must not fail
        assertTrue(need(ConsumptionSettings(decoReserveRule = ReserveRuleDeco.LOST_GAS, bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM)) > 0.0)
    }

    @Test
    fun ccrPlanIsDeterministic() {
        val a = bestPlan(70.0, 20.0, contingencies = true)
        val b = bestPlan(70.0, 20.0, contingencies = true)
        assertEquals(a.totalRuntimeMin, b.totalRuntimeMin, 0.0)
        assertEquals(a.input.gases, b.input.gases)
        assertEquals(a.ccrBailout, b.ccrBailout)
    }

    @Test
    fun multiDiveDayWithCcrStillPlansEveryDive() {
        val r = rec(60.0)
        val day = DivePlannerEngine.planMultiDiveDay(
            DivePlanInput(general = ccrBest, gases = r.plannerGases),
            listOf(
                MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("a", 60.0, 20.0))),
                MultiDiveEntry("d2", "Dive 2", 120.0, listOf(DiveLevel("b", 30.0, 30.0)))
            ),
            includeContingenciesForIndex = 1
        )
        assertEquals(2, day.diveResults.size)
        assertTrue(day.diveResults.all { it.abortedReason == null })
        assertNotNull(day.diveResults[1].ccrBailout)
    }
}
