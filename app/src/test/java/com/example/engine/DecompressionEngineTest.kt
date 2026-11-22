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
    fun testFourTierCategorizedSafetyAlerts() {
        val dive = DiveSettings(ppO2Bottom = 1.40, maxEndM = 30.0, maxGasDensityGL = 5.2)

        // 1. CRITICAL (Red): EAN50 at 40m -> ppO2 ~ 2.5 bar > 1.60 bar
        val criticalPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                dive = dive,
                gases = listOf(Gas.fromPercent("bad_ean50", 50, 0, GasRole.BOTTOM)),
                levels = listOf(DiveLevel("l_bad", 40.0, 15.0))
            )
        )
        assertTrue("Dangerous ppO2 must raise CRITICAL alert", criticalPlan.hasErrors)
        assertTrue(
            "Should flag CRITICAL PPO2_HIGH_BOTTOM",
            criticalPlan.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.PPO2_HIGH_BOTTOM }
        )

        // 2. HIGH (Orange): Bottom ppO2 > 1.40 (but <= 1.60) & OTU limit exceeded
        val highPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                dive = dive,
                alerts = AlertSettings(otuWarnPerDive = 50.0),
                gases = listOf(Gas.fromPercent("ean36", 36, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 4))),
                levels = listOf(DiveLevel("l_high", 32.0, 40.0)) // ppO2 ~ 1.52 bar (HIGH) & high OTU (HIGH)
            )
        )
        assertTrue(
            "Bottom ppO2 between 1.40 and 1.60 must raise HIGH PPO2_HIGH_BOTTOM",
            highPlan.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.PPO2_HIGH_BOTTOM }
        )
        assertTrue(
            "Exceeding OTU limit must raise HIGH OTU alert",
            highPlan.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.OTU }
        )

        // 3. MEDIUM (Yellow): Air at 38m exceeds END > 30m + aggressive GF High 95%
        val medPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, gfLow = 40, gfHigh = 95),
                dive = dive,
                gases = listOf(Gas.fromPercent("air38", 21, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2))),
                levels = listOf(DiveLevel("l_med", 38.0, 12.0))
            )
        )
        assertTrue(
            "Air at 38m must raise MEDIUM END_HIGH",
            medPlan.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.END_HIGH }
        )
        assertTrue(
            "GF High 95% must raise MEDIUM GF_EXTREME",
            medPlan.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.GF_EXTREME }
        )

        // 4. LOW (Gray): Very conservative GF (10/60) & configured gas budget exceeded
        val lowPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, gfLow = 10, gfHigh = 60),
                dive = dive,
                alerts = AlertSettings(bottomMixMaxLitres = 300.0),
                gases = listOf(Gas.fromPercent("ean32_low", 32, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2))),
                levels = listOf(DiveLevel("l_low", 20.0, 15.0))
            )
        )
        assertTrue(
            "Very conservative GF should raise LOW GF_EXTREME",
            lowPlan.alerts.any { it.level == AlertLevel.LOW && it.code == AlertCode.GF_EXTREME }
        )
        assertTrue(
            "Exceeding gas budget should raise LOW GAS_ALERT_LIMITS",
            lowPlan.alerts.any { it.level == AlertLevel.LOW && it.code == AlertCode.GAS_ALERT_LIMITS }
        )
        // Verify alerts are sorted in severity order (CRITICAL -> HIGH -> MEDIUM -> LOW)
        val priorities = highPlan.alerts.map { it.level.priority }
        assertEquals(priorities.sorted(), priorities)
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
        assertTrue("36m/8min REC dive has zero CRITICAL errors (got: ${plan36.alerts})", !plan36.hasErrors)

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

    @Test
    fun testSurfaceIntervalCalculatorBuhlmannZhl16c() {
        val baseInput = DivePlanInput(
            general = GeneralSettings(decoModel = DecoModel.ZHL16C, gfLow = 30, gfHigh = 85),
            gases = listOf(Gas.fromPercent("air", 21, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2))),
            levels = listOf(DiveLevel("d1", 30.0, 25.0))
        )
        val dive1 = DivePlannerEngine.planDive(baseInput)
        val nextGas = Gas.fromPercent("ean32", 32, 0, GasRole.BOTTOM)

        val est0 = TissuesAndBuhlmann.estimateSurfaceInterval(
            dive1.endTissues, dive1.oxygen.endCnsPct, 0.0, dive1.env, 24.0, nextGas, baseInput.general
        )
        val est60 = TissuesAndBuhlmann.estimateSurfaceInterval(
            dive1.endTissues, dive1.oxygen.endCnsPct, 60.0, dive1.env, 24.0, nextGas, baseInput.general
        )
        val est180 = TissuesAndBuhlmann.estimateSurfaceInterval(
            dive1.endTissues, dive1.oxygen.endCnsPct, 180.0, dive1.env, 24.0, nextGas, baseInput.general
        )
        val est360 = TissuesAndBuhlmann.estimateSurfaceInterval(
            dive1.endTissues, dive1.oxygen.endCnsPct, 360.0, dive1.env, 24.0, nextGas, baseInput.general
        )

        assertEquals(16, est60.compartmentLoadsAfterPct.size)
        assertTrue("Remaining excess inert gas decreases monotonically (60m < 0m)", est60.remainingExcessInertPct < est0.remainingExcessInertPct)
        assertTrue("Remaining excess inert gas decreases monotonically (180m < 60m)", est180.remainingExcessInertPct < est60.remainingExcessInertPct)
        assertTrue("Remaining excess inert gas decreases monotonically (360m < 180m)", est360.remainingExcessInertPct < est180.remainingExcessInertPct)
        assertTrue("Surface GF decreases with surface interval", est180.surfaceGfAfterPct <= est0.surfaceGfAfterPct)
        assertTrue("CNS halves after 90 min half-life (180m ~ 25% of surfacing CNS)", est180.cnsAfterPct <= est0.cnsBeforePct * 0.26 + 0.01)
        assertTrue("Next-dive NDL recovers as surface interval grows", est360.nextDiveNdlMin >= est0.nextDiveNdlMin)
    }

    @Test
    fun testHighCnsSingleDive80PercentNoaaWarning() {
        // NOAA limit at ppO2 1.40 bar is 150 min -> 126 min at ~1.40 bar yields > 80% and <= 100% single-dive CNS
        val ean40 = Gas.fromPercent("ean40_cns", 40, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 8))
        val cnsHighPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, btIncludesDescent = false),
                dive = DiveSettings(ppO2Bottom = 1.45),
                gases = listOf(ean40),
                levels = listOf(DiveLevel("cns_85", 24.5, 125.0)) // pAmb ~ 3.49 bar -> ppO2 ~ 1.40 bar -> ~83% NOAA CNS
            )
        )
        assertTrue(
            "Single-dive CNS (${cnsHighPlan.oxygen.diveCnsPct}%) should be in 80..100% range",
            cnsHighPlan.oxygen.diveCnsPct > 80.0 && cnsHighPlan.oxygen.diveCnsPct <= 100.0
        )
        assertTrue(
            "Single-dive CNS > 80% must trigger HIGH (Orange) CNS alert",
            cnsHighPlan.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.CNS }
        )

        // Below 80% single-dive CNS (~67%) should NOT trigger HIGH CNS warning
        val cnsModPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, btIncludesDescent = false),
                dive = DiveSettings(ppO2Bottom = 1.45),
                gases = listOf(ean40),
                levels = listOf(DiveLevel("cns_65", 24.5, 95.0))
            )
        )
        assertTrue("Single-dive CNS should be <= 80%", cnsModPlan.oxygen.diveCnsPct <= 80.0)
        assertTrue(
            "Single-dive CNS <= 80% must not trigger HIGH CNS alert",
            cnsModPlan.alerts.none { it.level == AlertLevel.HIGH && it.code == AlertCode.CNS }
        )

        // Above 100% single-dive CNS must escalate to CRITICAL (Red)
        val cnsCritPlan = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, btIncludesDescent = false),
                dive = DiveSettings(ppO2Bottom = 1.45),
                gases = listOf(ean40),
                levels = listOf(DiveLevel("cns_110", 24.5, 160.0))
            )
        )
        assertTrue("Single-dive CNS > 100% must trigger CRITICAL CNS alert", cnsCritPlan.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.CNS })
    }

    @Test
    fun testProposedCriticalHighMediumWarnings() {
        // ---- CRITICAL (Red) Warnings ----
        // 1. Hypoxic bottom gas without normoxic travel gas
        val hypoxicNoTravel = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                gases = listOf(Gas.fromPercent("tx1070", 10, 70, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 4))),
                levels = listOf(DiveLevel("l_hyp", 80.0, 15.0))
            )
        )
        assertTrue(
            "Hypoxic bottom gas without travel gas must trigger CRITICAL HYPOXIC_NO_TRAVEL",
            hypoxicNoTravel.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.HYPOXIC_NO_TRAVEL }
        )

        // 2. Severe narcosis (END > 45m) & UHMS gas density redline (> 6.2 g/L)
        val deepAir = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                gases = listOf(Gas.fromPercent("air_deep", 21, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 4))),
                levels = listOf(DiveLevel("l_50", 50.0, 15.0))
            )
        )
        assertTrue(
            "Air at 50m (END 50m > 45m) must trigger CRITICAL END_HIGH",
            deepAir.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.END_HIGH }
        )
        assertTrue(
            "Air at 50m (> 6.2 g/L) must trigger CRITICAL DENSITY_HIGH",
            deepAir.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.DENSITY_HIGH }
        )

        // 3. CCR diluent hyperoxia (> 1.60 bar) & missing OC bailout gas
        val ccrHazard = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(
                    planningType = PlanningType.CCR,
                    gasSelectionMode = GasSelectionMode.USER_INPUT_GAS
                ),
                gases = listOf(Gas.fromPercent("dil_air", 21, 0, GasRole.DILUENT)),
                levels = listOf(DiveLevel("l_ccr", 70.0, 20.0)) // pAmb ~ 8.08 * 0.21 = 1.70 bar > 1.60
            )
        )
        assertTrue(
            "CCR hyperoxic diluent must trigger CRITICAL CCR_DILUENT_HAZARD",
            ccrHazard.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.CCR_DILUENT_HAZARD }
        )
        assertTrue(
            "CCR without OC bailout must trigger CRITICAL CCR_BAILOUT_RISK",
            ccrHazard.alerts.any { it.level == AlertLevel.CRITICAL && it.code == AlertCode.CCR_BAILOUT_RISK }
        )

        // ---- HIGH (Orange) Warnings ----
        // 4. Missing O2 air breaks (> 20 min continuous high-ppO2 deco) — and cleared when gasBreaks enabled
        val o2NoBreaks = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, gfLow = 30, gfHigh = 70),
                gasBreaks = GasBreakSettings(enabled = false),
                gases = listOf(
                    Gas.fromPercent("tx1845", 18, 45, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 4)),
                    Gas.fromPercent("ean50", 50, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 2)),
                    Gas.fromPercent("o2", 100, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 2))
                ),
                levels = listOf(DiveLevel("l_o2", 55.0, 30.0))
            )
        )
        assertTrue(
            "Prolonged high-O2 deco without breaks must trigger HIGH NO_O2_BREAKS",
            o2NoBreaks.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.NO_O2_BREAKS }
        )

        // 5. Reverse dive profile & short surface interval on multi-dive day
        val multiDay = DivePlannerEngine.planMultiDiveDay(
            baseInput = DivePlanInput(
                general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                gases = listOf(
                    Gas.fromPercent("air_m", 21, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2)),
                    Gas.fromPercent("ean50_m", 50, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
                )
            ),
            dives = listOf(
                MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("l1", 18.0, 25.0))),
                MultiDiveEntry("d2", "Dive 2", 20.0, listOf(DiveLevel("l2", 36.0, 25.0))) // +18m deeper & 20m SI
            )
        )
        val dive2Alerts = multiDay.diveResults[1].alerts
        assertTrue(
            "Reverse profile (+18m deeper) must trigger HIGH REVERSE_PROFILE",
            dive2Alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.REVERSE_PROFILE }
        )
        assertTrue(
            "20 min surface interval must trigger HIGH SHORT_SURFACE_INTERVAL",
            dive2Alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.SHORT_SURFACE_INTERVAL }
        )

        // 6. Unacclimatized altitude dive (>= 300m, arrived < 12h) -> HIGH ALTITUDE_DIVING
        val altUnaccl = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(
                    gasSelectionMode = GasSelectionMode.USER_INPUT_GAS,
                    elevationM = 1200,
                    arrivedUnder12hAtAltitude = true
                ),
                gases = listOf(Gas.fromPercent("air_alt", 21, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2))),
                levels = listOf(DiveLevel("l_alt", 20.0, 20.0))
            )
        )
        assertTrue(
            "Unacclimatized altitude dive must trigger HIGH ALTITUDE_DIVING",
            altUnaccl.alerts.any { it.level == AlertLevel.HIGH && it.code == AlertCode.ALTITUDE_DIVING }
        )

        // ---- MEDIUM (Yellow) Warnings ----
        // 7. Yo-yo sawtooth multi-level profile, fast descent (> 24 m/min), cold water (<= 10C), acclimatized altitude
        val medMulti = DivePlannerEngine.planDive(
            DivePlanInput(
                general = GeneralSettings(
                    gasSelectionMode = GasSelectionMode.USER_INPUT_GAS,
                    surfaceTempC = 8,
                    elevationM = 800,
                    arrivedUnder12hAtAltitude = false
                ),
                dive = DiveSettings(descentRateMMin = 30.0),
                gases = listOf(Gas.fromPercent("ean32_y", 32, 0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2))),
                levels = listOf(
                    DiveLevel("y1", 24.0, 8.0),
                    DiveLevel("y2", 12.0, 6.0),
                    DiveLevel("y3", 22.0, 6.0)
                )
            )
        )
        assertTrue("Sawtooth profile must trigger MEDIUM YOYO_PROFILE", medMulti.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.YOYO_PROFILE })
        assertTrue("Descent 30 m/min must trigger MEDIUM DESCENT_FAST", medMulti.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.DESCENT_FAST })
        assertTrue("Water temp 8°C must trigger MEDIUM COLD_WATER", medMulti.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.COLD_WATER })
        assertTrue("Acclimatized 800m altitude must trigger MEDIUM ALTITUDE_DIVING", medMulti.alerts.any { it.level == AlertLevel.MEDIUM && it.code == AlertCode.ALTITUDE_DIVING })
    }
}
