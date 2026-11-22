package com.example.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Verifies Section 14 test vectors (14.1 Unit Tests, 14.2 Golden Schedules, 14.3 Property Tests)
 * plus Multi-Dive Day nitrogen carry-over.
 */
class DecompressionEngineTest {

    private val saltSeaEnv = UnitsAndPressure.buildEnvironment(
        GeneralSettings(salinityPreset = SalinityPreset.SALT, elevationM = 0)
    )

    // ---- Section 14.1: Exact Unit Tests ----

    @Test
    fun testPressureAndAltitude() {
        val p30 = UnitsAndPressure.ambientPressure(30.0, saltSeaEnv)
        assertEquals(4.0435, p30, 0.001)

        val pAlt2000 = UnitsAndPressure.surfacePressureBar(2000)
        assertEquals(0.7950, pAlt2000, 0.001)
    }

    @Test
    fun testModEndAndDensity() {
        val ean50 = Gas.fromPercent("ean50", 50, 0, GasRole.DECO)
        assertEquals(21.6, GasCalculator.modExactM(ean50, 1.6, saltSeaEnv), 0.15)
        assertEquals(21.0, GasCalculator.modDisplayM(ean50, 1.6, saltSeaEnv), 0.001)

        val o2 = Gas.fromPercent("o2", 100, 0, GasRole.DECO)
        assertEquals(5.8, GasCalculator.modExactM(o2, 1.6, saltSeaEnv), 0.1)
        assertEquals(5.0, GasCalculator.modDisplayM(o2, 1.6, saltSeaEnv), 0.001)

        val tx1845 = Gas.fromPercent("tx1845", 18, 45, GasRole.BOTTOM)
        assertEquals(28.5, GasCalculator.endM(tx1845, 60.0, saltSeaEnv, o2Narcotic = true), 0.2)

        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val density40 = GasCalculator.densityGL(air, 40.0, saltSeaEnv)
        assertEquals(6.4, density40, 0.15)
        assertTrue(density40 > 6.2)
    }

    @Test
    fun testHaldaneSchreinerAndGfFormula() {
        val haldaneVal = TissuesAndBuhlmann.haldane(0.7405, 3.0, 4.0, 4.0)
        assertEquals(1.8703, haldaneVal, 0.001)

        val schreinerZeroRate = TissuesAndBuhlmann.schreiner(0.7405, 3.0, 0.0, 4.0, 4.0)
        assertEquals(haldaneVal, schreinerZeroRate, 1e-9)

        val coeff = Coefficients.tableFor(DecoModel.ZHL16C)[0]
        val tissue = Tissue(pN2 = 3.2, pHe = 0.0)
        val gf1 = TissuesAndBuhlmann.toleratedAmbientBar(tissue, coeff, gf = 1.0)
        val pureBuhlmann = (tissue.pInert - coeff.aN2) * coeff.bN2
        assertEquals(pureBuhlmann, gf1, 1e-9)
    }

    @Test
    fun testCnsAndOtu() {
        val cns = OxygenAndConsumption.segmentCnsPct(1.4, 1.4, 30.0)
        assertEquals(20.0, cns, 0.05)

        val otu = OxygenAndConsumption.segmentOtu(1.4, 1.4, 30.0)
        assertEquals(48.9, otu, 0.3)
    }

    // ---- Section 14.2: Golden Schedules ----

    private fun goldenInput(
        depthM: Double,
        timeMin: Double,
        bottomGas: Gas,
        gfLow: Int,
        gfHigh: Int,
        includeDecoGases: Boolean = true,
        model: DecoModel = DecoModel.ZHL16C
    ): DivePlanInput {
        val gases = mutableListOf(bottomGas)
        if (includeDecoGases) {
            gases += Gas.fromPercent("ean50", 50, 0, GasRole.DECO)
            gases += Gas.fromPercent("o2", 100, 0, GasRole.DECO)
        }
        return DivePlanInput(
            general = GeneralSettings(
                decoModel = model,
                gfLow = gfLow,
                gfHigh = gfHigh,
                salinityPreset = SalinityPreset.SALT,
                elevationM = 0,
                btIncludesDescent = true
            ),
            dive = DiveSettings(
                ppO2Deco = 1.60,
                descentRateMMin = 20.0,
                ascentRateToFirstStopMMin = 9.0,
                ascentDecoRateMMin = 9.0,
                ascentRateToSurfaceMMin = 3.0,
                stopIncrementM = 3,
                lastStopM = 3,
                minStopTimeMin = 1.0,
                mixChangeExtraTimeMin = 0.0,
                stopOnGasSwitch = false
            ),
            gases = gases,
            levels = listOf(DiveLevel("l1", depthM, timeMin))
        )
    }

    @Test
    fun testGoldenCaseA_40m20minAir_GF20_80() {
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val res = DivePlannerEngine.planDive(goldenInput(40.0, 20.0, air, 20, 80))
        // Expected runtime ~ 35 min (±2 min)
        assertTrue("Case A runtime ${res.totalRuntimeMin} should be within 33..37 min", res.totalRuntimeMin in 32.5..37.5)
        assertTrue("Case A should have stops", res.stops.isNotEmpty())
        val stop3 = res.stops.firstOrNull { it.depthM == 3 }?.durationMin ?: 0.0
        assertTrue("Case A 3m stop $stop3 should be near 4 min", abs(stop3 - 4.0) <= 1.5)
    }

    @Test
    fun testGoldenCaseB_40m20minAir_GF100_100() {
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val res = DivePlannerEngine.planDive(goldenInput(40.0, 20.0, air, 100, 100))
        // Expected runtime ~ 29 min (±2 min), stops at 6m and 3m
        assertTrue("Case B runtime ${res.totalRuntimeMin} should be within 26.5..31.5 min", res.totalRuntimeMin in 26.5..31.5)
        val stop3 = res.stops.firstOrNull { it.depthM == 3 }?.durationMin ?: 0.0
        assertTrue("Case B 3m stop $stop3 should be near 3 min", abs(stop3 - 3.0) <= 1.5)
    }

    @Test
    fun testGoldenCaseC_60m20minTX1845_GF30_70() {
        val tx = Gas.fromPercent("tx1845", 18, 45, GasRole.BOTTOM)
        val res = DivePlannerEngine.planDive(goldenInput(60.0, 20.0, tx, 30, 70))
        // Expected runtime ~ 67 min (±3 min)
        assertTrue("Case C runtime ${res.totalRuntimeMin} should be within 63..71 min", res.totalRuntimeMin in 63.0..71.5)
        assertTrue("Case C first stop ${res.firstStopM} should be deep (27..33m)", res.firstStopM in 27..33)
    }

    @Test
    fun testGoldenCaseD_30m30minAirNoDecoGas_GF100_100() {
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val res = DivePlannerEngine.planDive(goldenInput(30.0, 30.0, air, 100, 100, includeDecoGases = false))
        // Expected 3m:7, runtime ~ 41 min
        assertTrue("Case D runtime ${res.totalRuntimeMin} should be within 38.5..43.5 min", res.totalRuntimeMin in 38.5..43.5)
        val stop3 = res.stops.firstOrNull { it.depthM == 3 }?.durationMin ?: 0.0
        assertTrue("Case D 3m stop $stop3 should be near 7 min", abs(stop3 - 7.0) <= 1.5)
    }

    // ---- Section 14.3: Property Tests & Multi-Dive Day ----

    @Test
    fun testMonotonicityAndModelConservatism() {
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val resA = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 30, 70, model = DecoModel.ZHL16A))
        val resB = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 30, 70, model = DecoModel.ZHL16B))
        val resC = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 30, 70, model = DecoModel.ZHL16C))

        assertTrue("ZHL-16C deco >= ZHL-16B deco", resC.totalDecoMin >= resB.totalDecoMin - 0.01)
        assertTrue("ZHL-16B deco >= ZHL-16A deco", resB.totalDecoMin >= resA.totalDecoMin - 0.01)

        val gfLow20 = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 20, 70))
        val gfLow50 = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 50, 70))
        assertTrue("Lower GF Low -> deeper or equal first stop", gfLow20.firstStopM >= gfLow50.firstStopM)

        val gfHigh65 = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 30, 65))
        val gfHigh85 = DivePlannerEngine.planDive(goldenInput(45.0, 25.0, air, 30, 85))
        assertTrue("Lower GF High -> longer or equal deco", gfHigh65.totalDecoMin >= gfHigh85.totalDecoMin)
    }

    @Test
    fun testMultiDiveDayResidualNitrogenAccumulation() {
        val base = DivePlanInput()
        val dive1 = MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("l1", 40.0, 20.0)))
        val dive2ShortSi = MultiDiveEntry("d2", "Dive 2", 45.0, listOf(DiveLevel("l2", 40.0, 20.0)))
        val dive2LongSi = MultiDiveEntry("d2b", "Dive 2", 240.0, listOf(DiveLevel("l2b", 40.0, 20.0)))

        val dayShort = DivePlannerEngine.planMultiDiveDay(base, listOf(dive1, dive2ShortSi))
        val dayLong = DivePlannerEngine.planMultiDiveDay(base, listOf(dive1, dive2LongSi))

        val d1Deco = dayShort.diveResults[0].totalDecoMin
        val d2ShortDeco = dayShort.diveResults[1].totalDecoMin
        val d2LongDeco = dayLong.diveResults[1].totalDecoMin

        assertTrue("Repetitive dive 2 after 45m SI must require more deco than dive 1 ($d2ShortDeco > $d1Deco)", d2ShortDeco > d1Deco)
        assertTrue("Longer SI (240m) reduces dive 2 deco vs 45m SI ($d2LongDeco < $d2ShortDeco)", d2LongDeco < d2ShortDeco)
        assertTrue("Daily OTU accumulates across dives", dayShort.totalDailyOtu > dayShort.diveResults[0].oxygen.diveOtu)
    }

    @Test
    fun testBestGasMixAllStagesAnd12LDefaultTanks() {
        val general = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        val dive = DiveSettings()

        // 1. Deep 65m dive: Travel + Helium MOD Bottom + Deep Deco + 21m EAN50 + 6m O2, ALL default 12L
        val rec65 = GasCalculator.recommendBestMix(65.0, general, dive)
        assertEquals(100, rec65.deco6mO2!!.o2Pct)
        assertEquals(6.0, rec65.deco6mO2.modOverrideM)
        assertEquals(50, rec65.deco21mEan50!!.o2Pct)
        assertEquals(21.0, rec65.deco21mEan50.modOverrideM)
        assertTrue("65m MOD bottom gas should contain helium", rec65.bottomGas.hePct > 0)
        assertTrue("65m dive should include a deep helium deco stage", rec65.deepDecoGas != null && rec65.deepDecoGas.hePct > 0)
        assertTrue("65m dive should select normoxic travel gas", rec65.travelGasDisplay.o2Pct >= 19)
        assertTrue(
            "All Best GAS Mix bottles must default to 12L",
            rec65.plannerGases.all { it.cylinder.waterVolumeL == 12.0 }
        )

        // Verify the dive planner ACTUALLY uses Travel (descent), Bottom MOD (bottom), Deep Deco, EAN50, and O2 across stages
        val plan65 = DivePlannerEngine.planDive(
            DivePlanInput(
                general = general,
                dive = dive,
                gases = rec65.plannerGases,
                levels = listOf(DiveLevel("l65", 65.0, 20.0))
            )
        )
        val usedRoles = plan65.segments.map { it.gas.role }.toSet()
        val usedIds = plan65.segments.map { it.gas.id }.toSet()
        assertTrue("Descent should use Travel Gas", GasRole.TRAVEL in usedRoles)
        assertTrue("Bottom should use MOD Bottom Gas", GasRole.BOTTOM in usedRoles)
        assertTrue("Ascent/Deco should use DECO bottles", GasRole.DECO in usedRoles)
        assertTrue("Should switch to 21m EAN50 on deco", "best_deco_21m_ean50" in usedIds)
        assertTrue("Should switch to 6m O2 on deco", "best_deco_6m_o2" in usedIds)
        assertTrue("No insufficient gas errors in Best GAS Mix mode", plan65.alerts.none { it.code == AlertCode.GAS_VOLUME })

        // 2. Mid 40m technical dive: verify 12L defaults, richer travel gas on descent, and safe MOD
        val rec40 = GasCalculator.recommendBestMix(40.0, general, dive)
        assertEquals(100, rec40.deco6mO2!!.o2Pct)
        assertEquals(50, rec40.deco21mEan50!!.o2Pct)
        assertTrue("40m travel gas should be richer than bottom gas", rec40.travelGasDisplay.o2Pct > rec40.bottomGas.o2Pct)
        assertTrue("40m MOD mix should be safe at 40m", rec40.modM >= 39.0)
        assertTrue("All 40m bottles default to 12L", rec40.plannerGases.all { it.cylinder.waterVolumeL == 12.0 })

        // 3. Preserving user-customized tank size (e.g., user changes 6m O2 bottle to 7L and bottom to 15L)
        val customized = rec40.plannerGases.map { g ->
            when (g.id) {
                "best_deco_6m_o2" -> g.copy(cylinder = g.cylinder.copy(waterVolumeL = 7.0))
                "best_bottom" -> g.copy(cylinder = g.cylinder.copy(waterVolumeL = 15.0))
                else -> g
            }
        }
        val rec50Preserved = GasCalculator.recommendBestMix(50.0, general, dive, existingGases = customized)
        assertEquals(7.0, rec50Preserved.deco6mO2!!.cylinder.waterVolumeL, 0.001)
        assertEquals(15.0, rec50Preserved.bottomGas.cylinder.waterVolumeL, 0.001)
        assertEquals(12.0, rec50Preserved.deco21mEan50!!.cylinder.waterVolumeL, 0.001)
    }

    @Test
    fun testBestGasMixAutoSizesTankCountBySacRate() {
        val general = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        val dive = DiveSettings()

        // 1. Shallow short dive with low SAC (12 L/min) -> 1 × 12L @ 200 bar is enough
        val rec20 = GasCalculator.recommendBestMix(20.0, general, dive)
        val planLow = DivePlannerEngine.planDive(
            DivePlanInput(
                general = general,
                dive = dive,
                consumption = ConsumptionSettings(bottomRmvLMin = 12.0, decoRmvLMin = 10.0),
                gases = rec20.plannerGases,
                levels = listOf(DiveLevel("l20", 20.0, 20.0))
            )
        )
        val bottomLow = planLow.input.gases.first { it.role == GasRole.BOTTOM }
        assertEquals(1, bottomLow.cylinder.count)
        assertEquals(12.0, bottomLow.cylinder.waterVolumeL, 0.001)
        assertTrue("All gases sufficient for shallow low-SAC dive", planLow.gasConsumption.all { it.isSufficient })
        assertTrue("No GAS_VOLUME alert", planLow.alerts.none { it.code == AlertCode.GAS_VOLUME })

        // 2. Deep heavy-SAC dive (70m / 30min @ 30 L/min bottom, 25 L/min deco) -> scales up to 3+ × 12L @ 200 bar
        val rec70 = GasCalculator.recommendBestMix(70.0, general, dive)
        val planHigh = DivePlannerEngine.planDive(
            DivePlanInput(
                general = general,
                dive = dive,
                consumption = ConsumptionSettings(bottomRmvLMin = 30.0, decoRmvLMin = 25.0),
                gases = rec70.plannerGases,
                levels = listOf(DiveLevel("l70", 70.0, 30.0))
            )
        )
        val bottomHigh = planHigh.input.gases.first { it.role == GasRole.BOTTOM }
        assertTrue("Heavy 70m/30m high-SAC dive should auto-scale to >= 3 tanks of 12L (got ${bottomHigh.cylinder.count})", bottomHigh.cylinder.count >= 3)
        assertTrue("All gases must be sufficient even at extreme SAC", planHigh.gasConsumption.all { it.isSufficient })
        assertTrue("Zero GAS_VOLUME alerts in Best GAS Mix mode", planHigh.alerts.none { it.code == AlertCode.GAS_VOLUME })
    }

    @Test
    fun testCriticalErrorsOnlyPolicy() {
        val general = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        val dive = DiveSettings()

        // 1. Standard 40m/20m Best GAS Mix dive should produce ZERO alerts (no noisy warnings/info)
        val rec40 = GasCalculator.recommendBestMix(40.0, general, dive)
        val cleanPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = general,
                dive = dive,
                gases = rec40.plannerGases,
                levels = listOf(DiveLevel("l40", 40.0, 20.0))
            )
        )
        assertTrue("Standard Best GAS Mix dive must have zero noisy alerts (got: ${cleanPlan.alerts})", cleanPlan.alerts.isEmpty())

        // 2. Dangerous User Input setup (EAN50 at 40m -> ppO2 ~ 2.5 bar > 1.60 bar) MUST raise critical ERROR
        val dangerousPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                dive = dive,
                gases = listOf(Gas.fromPercent("bad_ean50", 50, 0, GasRole.BOTTOM)),
                levels = listOf(DiveLevel("l_bad", 40.0, 15.0))
            )
        )
        assertTrue("Dangerous ppO2 must raise critical error", dangerousPlan.hasErrors)
        assertTrue("All emitted alerts must be critical ERRORs", dangerousPlan.alerts.all { it.level == AlertLevel.ERROR })
        assertTrue("Should flag PPO2_HIGH_BOTTOM", dangerousPlan.alerts.any { it.code == AlertCode.PPO2_HIGH_BOTTOM })
    }

    @Test
    fun testRecreationalDiveUnder40mWithinNdlUsesEan32NoDecoNoTrimix() {
        val general = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        val dive = DiveSettings()

        // 1. 25m / 20min (< 40m, within NDL) -> REC dive, default EAN32, no DECO, no Trimix
        val rec25 = GasCalculator.recommendBestMix(
            maxDepthM = 25.0,
            general = general,
            dive = dive,
            levels = listOf(DiveLevel("l25", 25.0, 20.0))
        )
        assertTrue("25m/20min should be classified as REC Recreational Dive", rec25.isRecreational)
        assertEquals("Default REC best mix must be EAN32", 32, rec25.bottomGas.o2Pct)
        assertEquals("REC dive must never use helium/trimix", 0, rec25.bottomGas.hePct)
        assertTrue("REC dive must not propose DECO gases", rec25.decoGases.isEmpty())
        assertEquals("REC dive must only include 1 bottom gas", 1, rec25.plannerGases.size)
        assertEquals(null, rec25.deco6mO2)
        assertEquals(null, rec25.deco21mEan50)

        // 2. 36m / 8min (< 40m, within NDL, deeper than EAN32 MOD at 1.4 bar) -> safe Nitrox (no Trimix, no DECO)
        val rec36 = GasCalculator.recommendBestMix(
            maxDepthM = 36.0,
            general = general,
            dive = dive,
            levels = listOf(DiveLevel("l36", 36.0, 8.0))
        )
        assertTrue("36m/8min should be classified as REC Recreational Dive", rec36.isRecreational)
        assertTrue("36m REC gas must be Air or Nitrox (21-31% O2)", rec36.bottomGas.o2Pct in 21..31)
        assertEquals("36m REC dive must NOT propose trimix", 0, rec36.bottomGas.hePct)
        assertTrue("36m REC dive must NOT propose DECO gases", rec36.decoGases.isEmpty())

        val plan36 = DivePlannerEngine.planDive(
            DivePlanInput(
                general = general,
                dive = dive,
                gases = rec36.plannerGases,
                levels = listOf(DiveLevel("l36", 36.0, 8.0))
            )
        )
        assertEquals("36m/8min REC dive has zero deco stops", 0.0, plan36.totalDecoMin, 0.001)
        assertTrue("36m/8min REC dive has zero critical errors (got: ${plan36.alerts})", plan36.alerts.isEmpty())

        // 3. 30m / 45min (< 40m, EXCEEDS NDL) -> TEC decompression dive with DECO gases
        val tec30 = GasCalculator.recommendBestMix(
            maxDepthM = 30.0,
            general = general,
            dive = dive,
            levels = listOf(DiveLevel("l30_deco", 30.0, 45.0))
        )
        assertTrue("30m/45min exceeds NDL so it must be a TEC dive", !tec30.isRecreational)
        assertTrue("TEC dive must propose DECO gases", tec30.decoGases.isNotEmpty())
        assertEquals(100, tec30.deco6mO2!!.o2Pct)
        assertEquals(50, tec30.deco21mEan50!!.o2Pct)

        // 4. 40m / 6min (40m is NOT under 40m) -> TEC dive
        val tec40 = GasCalculator.recommendBestMix(
            maxDepthM = 40.0,
            general = general,
            dive = dive,
            levels = listOf(DiveLevel("l40_short", 40.0, 6.0))
        )
        assertTrue("40m dive is not under 40m, so it remains a TEC dive", !tec40.isRecreational)
        assertTrue("40m dive proposes DECO gases", tec40.decoGases.isNotEmpty())
    }
}
