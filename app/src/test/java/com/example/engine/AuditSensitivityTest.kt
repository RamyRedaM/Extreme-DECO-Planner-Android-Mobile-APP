package com.example.engine

import org.junit.Test
import kotlin.math.roundToInt

/**
 * AUDIT ONLY (temporary). Changes one input at a time and reports which outputs change.
 * Prints "DIAG" lines; never fails.
 */
class AuditSensitivityTest {

    private val bottom = Gas.fromPercent("tx", 18, 45, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 232.0, 2))
    private val ean50 = Gas.fromPercent("ean50", 50, 0, GasRole.DECO, cylinder = CylinderConfig(11.0, 200.0, 1))
    private val o2 = Gas.fromPercent("o2", 100, 0, GasRole.DECO, cylinder = CylinderConfig(7.0, 200.0, 1))
    private val travel = Gas.fromPercent("travel", 32, 0, GasRole.TRAVEL, cylinder = CylinderConfig(11.0, 200.0, 1))

    private val base = DivePlanInput(
        general = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS, gfLow = 30, gfHigh = 70),
        dive = DiveSettings(),
        gases = listOf(bottom, ean50, o2),
        levels = listOf(DiveLevel("l1", 60.0, 25.0))
    )

    private fun fp(r: DivePlanResult): Map<String, String> = mapOf(
        "runtime" to "%.2f".format(r.totalRuntimeMin),
        "deco" to "%.2f".format(r.totalDecoMin),
        "stops" to r.stops.joinToString(",") { "${it.depthM}:${"%.1f".format(it.durationMin)}:${it.gas.displayName}" },
        "firstStop" to r.firstStopM.toString(),
        "cns" to "%.2f".format(r.oxygen.endCnsPct),
        "otu" to "%.2f".format(r.oxygen.diveOtu),
        "gasUsed" to r.gasConsumption.joinToString(",") { "${it.gas.displayName}=${it.usedLitres.roundToInt()}" },
        "gasReq" to r.gasConsumption.joinToString(",") { "${it.gas.displayName}=${it.totalRequiredLitres.roundToInt()}/${it.availableLitres.roundToInt()}" },
        "ndl" to "%.1f".format(r.ndlMin),
        "surfGf" to "%.1f".format(r.endSurfaceGfPct),
        "maxPpO2" to "%.2f".format(r.oxygen.maxPpO2),
        "segments" to r.segments.joinToString(",") { "${it.type.name.take(2)}${it.fromDepthM.roundToInt()}-${it.toDepthM.roundToInt()}" },
        "alerts" to r.alerts.joinToString(",") { "${it.level.name.take(1)}:${it.code.name}" }.ifEmpty { "-" },
        "endM" to r.segments.maxOfOrNull { it.endM }?.let { "%.1f".format(it) }.orEmpty(),
        "density" to r.segments.maxOfOrNull { it.densityGL }?.let { "%.2f".format(it) }.orEmpty()
    )

    private val lines = mutableListOf<String>()

    private fun check(name: String, baseIn: DivePlanInput, variant: DivePlanInput) {
        val a = fp(DivePlannerEngine.planDive(baseIn))
        val b = fp(DivePlannerEngine.planDive(variant))
        val changed = a.keys.filter { a[it] != b[it] }
        val detail = changed.filter { it in setOf("runtime", "deco", "cns", "otu", "firstStop", "alerts", "gasReq") }
            .joinToString("; ") { "$it ${a[it]}→${b[it]}".take(160) }
        lines += if (changed.isEmpty()) "NOCHANGE $name" else if (name.startsWith("*")) "OK $name [${changed.joinToString("/")}] $detail" else "OK $name [${changed.filter { it != "segments" }.joinToString("/")}]"
    }

    private fun g(f: (GeneralSettings) -> GeneralSettings) = base.copy(general = f(base.general))
    private fun d(f: (DiveSettings) -> DiveSettings) = base.copy(dive = f(base.dive))
    private fun c(f: (ConsumptionSettings) -> ConsumptionSettings) = base.copy(consumption = f(base.consumption))
    private fun a(f: (AlertSettings) -> AlertSettings) = base.copy(alerts = f(base.alerts))
    private fun gb(f: (GasBreakSettings) -> GasBreakSettings) = base.copy(gasBreaks = f(base.gasBreaks))

    fun auditRound1() {
        lines += "BASE " + fp(DivePlannerEngine.planDive(base)).entries.filter { it.key != "segments" }.joinToString(" ") { "${it.key}=${it.value}" }

        // General
        check("decoModel ZHL16A", base, g { it.copy(decoModel = DecoModel.ZHL16A) })
        check("decoModel ZHL16B", base, g { it.copy(decoModel = DecoModel.ZHL16B) })
        check("decoModel VPMB", base, g { it.copy(decoModel = DecoModel.VPMB) })
        val vpm = g { it.copy(decoModel = DecoModel.VPMB, vpmConservatism = 0) }
        check("vpmConservatism 0->5", vpm, vpm.copy(general = vpm.general.copy(vpmConservatism = 5)))
        check("vpmConservatism ignored when ZHL16C", base, g { it.copy(vpmConservatism = 5) })
        check("gfLow 30->20", base, g { it.copy(gfLow = 20) })
        check("gfHigh 70->85", base, g { it.copy(gfHigh = 85) })
        check("useCompartment1b", base, g { it.copy(useCompartment1b = true) })
        check("salinity FRESH", base, g { it.copy(salinityPreset = SalinityPreset.FRESH) })
        check("salinity EN13319", base, g { it.copy(salinityPreset = SalinityPreset.EN13319) })
        check("salinity CUSTOM 1000", base, g { it.copy(salinityPreset = SalinityPreset.CUSTOM, customSalinityKgM3 = 1000.0) })
        check("salinity RED_SEA uses custom value 1000 (bug?)", g { it.copy(salinityPreset = SalinityPreset.SALT) }, g { it.copy(salinityPreset = SalinityPreset.RED_SEA, customSalinityKgM3 = 1000.0) })
        check("elevation 0->1500", base, g { it.copy(elevationM = 1500) })
        val alt = g { it.copy(elevationM = 1500) }
        check("arrivedUnder12h @1500m", alt, alt.copy(general = alt.general.copy(arrivedUnder12hAtAltitude = true)))
        check("surfaceTempC 22->5", base, g { it.copy(surfaceTempC = 5) })
        check("btIncludesDescent false", base, g { it.copy(btIncludesDescent = false) })
        check("planningType CCR", base, g { it.copy(planningType = PlanningType.CCR) })
        check("gasSelectionMode BEST", base, g { it.copy(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX) })

        // Dive settings
        check("ppO2Bottom 1.4->1.2", base, d { it.copy(ppO2Bottom = 1.2) })
        check("ppO2Deco 1.6->1.4", base, d { it.copy(ppO2Deco = 1.4) })
        check("ppO2Min 0.18->0.21", base, d { it.copy(ppO2Min = 0.21) })
        check("ppN2Max 3.95->2.5", base, d { it.copy(ppN2Max = 2.5) })
        check("ppN2MaxDeco 3.16->2.0", base, d { it.copy(ppN2MaxDeco = 2.0) })
        check("maxEndM 30->20", base, d { it.copy(maxEndM = 20.0) })
        check("o2Narcotic false", base, d { it.copy(o2Narcotic = false) })
        check("maxGasDensity 5.2->4.0", base, d { it.copy(maxGasDensityGL = 4.0) })
        check("descentRate 20->10", base, d { it.copy(descentRateMMin = 10.0) })
        check("ascentToFirstStop 9->5", base, d { it.copy(ascentRateToFirstStopMMin = 5.0) })
        check("ascentDeco 9->3", base, d { it.copy(ascentDecoRateMMin = 3.0) })
        check("ascentSurface 3->1", base, d { it.copy(ascentRateToSurfaceMMin = 1.0) })
        check("stopIncrement 3->1", base, d { it.copy(stopIncrementM = 1) })
        check("lastStop 3->6", base, d { it.copy(lastStopM = 6) })
        check("minStopTime 1->0.5", base, d { it.copy(minStopTimeMin = 0.5) })
        check("mixChangeExtra 1->0", base, d { it.copy(mixChangeExtraTimeMin = 0.0) })
        check("mixChangeExtra 1->3", base, d { it.copy(mixChangeExtraTimeMin = 3.0) })
        val noExtra = d { it.copy(mixChangeExtraTimeMin = 0.0) }
        check("stopOnGasSwitch false (extra 0)", noExtra, noExtra.copy(dive = noExtra.dive.copy(stopOnGasSwitch = false)))

        // Gas breaks
        check("gasBreaks enabled", base, gb { it.copy(enabled = true) })
        val brk = gb { it.copy(enabled = true) }
        check("gasBreaks breakAfter 20->10", brk, brk.copy(gasBreaks = brk.gasBreaks.copy(breakAfterMin = 10.0)))
        check("gasBreaks breakTime 5->8", brk, brk.copy(gasBreaks = brk.gasBreaks.copy(breakTimeMin = 8.0)))
        check("gasBreaks applyDepth 6->3", brk, brk.copy(gasBreaks = brk.gasBreaks.copy(applyDepthMaxM = 3.0)))

        // Consumption
        check("bottomRmv 20->25", base, c { it.copy(bottomRmvLMin = 25.0) })
        check("decoRmv 15->20", base, c { it.copy(decoRmvLMin = 20.0) })
        check("stressFactor 1.5->2.0 (THIRDS)", base, c { it.copy(stressFactor = 2.0) })
        check("bottomReserve QUARTERS", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.QUARTERS) })
        check("bottomReserve HALF", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.HALF) })
        check("bottomReserve ROCK_BOTTOM", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM) })
        val rb = c { it.copy(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM) }
        check("stressFactor 1.5->2.0 (ROCK_BOTTOM)", rb, rb.copy(consumption = rb.consumption.copy(stressFactor = 2.0)))
        check("decoReserve LOST_GAS (stress 1.5)", base, c { it.copy(decoReserveRule = ReserveRuleDeco.LOST_GAS) })
        val lg = c { it.copy(decoReserveRule = ReserveRuleDeco.LOST_GAS) }
        check("stressFactor 1.5->2.0 (LOST_GAS)", lg, lg.copy(consumption = lg.consumption.copy(stressFactor = 2.0)))

        // CCR settings
        val ccr = g { it.copy(planningType = PlanningType.CCR) }
        check("ccr setpointDescent 0.7->0.5", ccr, ccr.copy(ccr = ccr.ccr.copy(setpointDescent = 0.5)))
        check("ccr setpointBottom 1.3->1.1", ccr, ccr.copy(ccr = ccr.ccr.copy(setpointBottom = 1.1)))
        check("ccr setpointDeco 1.3->1.5", ccr, ccr.copy(ccr = ccr.ccr.copy(setpointDeco = 1.5)))
        check("ccr switchToHigh 20->40", ccr, ccr.copy(ccr = ccr.ccr.copy(switchToHighDepthM = 40.0)))
        check("ccr switchToDeco 6->12", ccr, ccr.copy(ccr = ccr.ccr.copy(switchToDecoDepthM = 12.0)))

        // Alert settings (should change alerts only)
        check("alert icdDeltaPpN2 0.5->0.2", base, a { it.copy(icdDeltaPpN2Max = 0.2) })
        check("alert ruleOfFifths off", base, a { it.copy(icdRuleOfFifths = false) })
        check("alert lipid on", base, a { it.copy(considerLipidResponse = true) })
        check("alert bottomMixMaxL 4800->500", base, a { it.copy(bottomMixMaxLitres = 500.0) })
        check("alert deco49 2400->100", base, a { it.copy(decoMixUpTo49MaxLitres = 100.0) })
        check("alert deco50-99 2400->100", base, a { it.copy(decoMix50To99MaxLitres = 100.0) })
        check("alert decoO2 1200->100", base, a { it.copy(decoO2MaxLitres = 100.0) })
        check("alert cnsWarn 80->10", base, a { it.copy(cnsWarnPct = 10.0) })
        check("alert cnsErr 100->20", base, a { it.copy(cnsErrorPct = 20.0) })
        check("alert otuWarn 300->20", base, a { it.copy(otuWarnPerDive = 20.0) })

        // Gas & level inputs
        check("bottom cylinder 2x12->2x15", base, base.copy(gases = listOf(bottom.copy(cylinder = CylinderConfig(15.0, 232.0, 2)), ean50, o2)))
        check("bottom fill 232->200", base, base.copy(gases = listOf(bottom.copy(cylinder = CylinderConfig(12.0, 200.0, 2)), ean50, o2)))
        check("ean50 modOverride 15", base, base.copy(gases = listOf(bottom, ean50.copy(modOverrideM = 15.0), o2)))
        check("ean50 disabled", base, base.copy(gases = listOf(bottom, ean50.copy(enabled = false), o2)))
        check("add travel EAN32", base, base.copy(gases = listOf(travel, bottom, ean50, o2)))
        check("level depth 60->63", base, base.copy(levels = listOf(DiveLevel("l1", 63.0, 25.0))))
        check("level time 25->30", base, base.copy(levels = listOf(DiveLevel("l1", 60.0, 30.0))))
        check("level2 add 40m/10", base, base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 40.0, 10.0))))
        check("level gasId ean50 at 21m level", base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 21.0, 10.0))),
            base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 21.0, 10.0, gasId = "ean50"))))

        // Repetitive inputs
        val first = DivePlannerEngine.planDive(base)
        val rep = base.copy(levels = listOf(DiveLevel("r", 30.0, 30.0)), previousTissues = first.endTissues, previousCnsPct = first.oxygen.endCnsPct, previousDailyOtu = first.oxygen.dailyOtu, surfaceIntervalMin = 60.0)
        check("repetitive SI 60->240", rep, rep.copy(surfaceIntervalMin = 240.0))
        check("repetitive previousTissues vs fresh", rep.copy(previousTissues = null, previousCnsPct = 0.0, previousDailyOtu = 0.0), rep)

        // Best mix sensitivity
        val bg = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        fun bm(gen: GeneralSettings = bg, dv: DiveSettings = DiveSettings(), depth: Double = 70.0) =
            GasCalculator.recommendBestMix(depth, gen, dv, levels = listOf(DiveLevel("b", depth, 20.0))).plannerGases
                .joinToString(",") { "${it.role.name.take(2)}:${it.displayName}:${it.cylinder.count}x" }
        val b0 = bm()
        fun bmCheck(name: String, v: String) { lines += if (v == b0) "NOCHANGE bestMix $name" else "OK bestMix $name: $b0 → $v" }
        bmCheck("ppO2Bottom 1.2", bm(dv = DiveSettings(ppO2Bottom = 1.2)))
        bmCheck("ppO2Deco 1.4", bm(dv = DiveSettings(ppO2Deco = 1.4)))
        bmCheck("maxEnd 20", bm(dv = DiveSettings(maxEndM = 20.0)))
        bmCheck("o2Narcotic false", bm(dv = DiveSettings(o2Narcotic = false)))
        bmCheck("ppN2Max 2.5", bm(dv = DiveSettings(ppN2Max = 2.5)))
        bmCheck("ppN2MaxDeco 2.0", bm(dv = DiveSettings(ppN2MaxDeco = 2.0)))
        bmCheck("maxDensity 4.0", bm(dv = DiveSettings(maxGasDensityGL = 4.0)))
        bmCheck("standardMixesOnly", bm(gen = bg.copy(standardMixesOnly = true)))
        bmCheck("ppO2Min 0.21", bm(dv = DiveSettings(ppO2Min = 0.21)))

        // Surface interval calculator
        val env = UnitsAndPressure.buildEnvironment(base.general)
        fun si(min: Double, depth: Double = 25.0, gas: Gas = Gas.fromPercent("x", 32, 0, GasRole.BOTTOM)) =
            TissuesAndBuhlmann.estimateSurfaceInterval(first.endTissues, first.oxygen.endCnsPct, min, env, depth, gas, base.general)
        val s60 = si(60.0); val s240 = si(240.0)
        lines += "SI 60m: remain=${"%.1f".format(s60.remainingExcessInertPct)}% lead=C${s60.leadingCompartmentNumber} ${"%.1f".format(s60.leadingSaturationAfterPct)}% surfGf=${"%.1f".format(s60.surfaceGfAfterPct)} cns=${"%.1f".format(s60.cnsAfterPct)} ndl=${"%.1f".format(s60.nextDiveNdlMin)}/fresh ${"%.1f".format(s60.freshNdlMin)} clean=${s60.estimatedCleanTimeMin}"
        lines += "SI 240m: remain=${"%.1f".format(s240.remainingExcessInertPct)}% lead=C${s240.leadingCompartmentNumber} ${"%.1f".format(s240.leadingSaturationAfterPct)}% surfGf=${"%.1f".format(s240.surfaceGfAfterPct)} cns=${"%.1f".format(s240.cnsAfterPct)} ndl=${"%.1f".format(s240.nextDiveNdlMin)} clean=${s240.estimatedCleanTimeMin}"
        val sDeep = si(60.0, depth = 40.0); val sAir = si(60.0, gas = Gas.fromPercent("a", 21, 0, GasRole.BOTTOM))
        lines += "SI nextDepth 25->40 ndl ${"%.1f".format(s60.nextDiveNdlMin)}->${"%.1f".format(sDeep.nextDiveNdlMin)}; gas EAN32->Air ndl ${"%.1f".format(s60.nextDiveNdlMin)}->${"%.1f".format(sAir.nextDiveNdlMin)}"
        val sGf = TissuesAndBuhlmann.estimateSurfaceInterval(first.endTissues, first.oxygen.endCnsPct, 60.0, env, 25.0, Gas.fromPercent("x", 32, 0, GasRole.BOTTOM), base.general.copy(gfHigh = 90))
        lines += "SI gfHigh 70->90 ndl ${"%.1f".format(s60.nextDiveNdlMin)}->${"%.1f".format(sGf.nextDiveNdlMin)}"

        lines.forEach { println("DIAG $it") }
    }

    @Test
    fun auditRound2() {
        check("ppO2Bottom 1.4->1.2", base, d { it.copy(ppO2Bottom = 1.2) })
        check("surfaceTempC 22->5", base, g { it.copy(surfaceTempC = 5) })
        check("btIncludesDescent false", base, g { it.copy(btIncludesDescent = false) })
        check("planningType CCR", base, g { it.copy(planningType = PlanningType.CCR) })
        check("gasSelectionMode BEST", base, g { it.copy(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX) })
        val brk = gb { it.copy(enabled = true) }
        check("gasBreaks breakTime 5->8", brk, brk.copy(gasBreaks = brk.gasBreaks.copy(breakTimeMin = 8.0)))
        check("gasBreaks applyDepth 6->3", brk, brk.copy(gasBreaks = brk.gasBreaks.copy(applyDepthMaxM = 3.0)))
        check("bottomRmv 20->25", base, c { it.copy(bottomRmvLMin = 25.0) })
        check("decoRmv 15->20", base, c { it.copy(decoRmvLMin = 20.0) })
        check("stressFactor 1.5->2.0 (THIRDS)", base, c { it.copy(stressFactor = 2.0) })
        check("*bottomReserve QUARTERS", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.QUARTERS) })
        check("*bottomReserve HALF", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.HALF) })
        check("*bottomReserve ROCK_BOTTOM", base, c { it.copy(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM) })
        val rb = c { it.copy(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM) }
        check("*ROCK_BOTTOM add travel gas (descent switch)", rb, rb.copy(gases = listOf(travel, bottom, ean50, o2)))
        check("*ROCK_BOTTOM mixChangeExtra 1->0", rb, rb.copy(dive = rb.dive.copy(mixChangeExtraTimeMin = 0.0)))
        check("*decoReserve LOST_GAS (stress 1.5)", base, c { it.copy(decoReserveRule = ReserveRuleDeco.LOST_GAS) })
        val lg = c { it.copy(decoReserveRule = ReserveRuleDeco.LOST_GAS) }
        check("*stressFactor 1.5->2.0 (LOST_GAS)", lg, lg.copy(consumption = lg.consumption.copy(stressFactor = 2.0)))
        val ccr = g { it.copy(planningType = PlanningType.CCR) }
        check("ccr setpointDescent 0.7->0.5", ccr, ccr.copy(ccr = ccr.ccr.copy(setpointDescent = 0.5)))
        val ccrD = ccr.copy(ccr = ccr.ccr.copy(setpointDeco = 1.5))
        check("ccr switchToDeco 6->12 (deco SP 1.5)", ccrD, ccrD.copy(ccr = ccrD.ccr.copy(switchToDecoDepthM = 12.0)))
        check("level depth 60->63", base, base.copy(levels = listOf(DiveLevel("l1", 63.0, 25.0))))
        check("level time 25->30", base, base.copy(levels = listOf(DiveLevel("l1", 60.0, 30.0))))
        check("level2 add 40m/10", base, base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 40.0, 10.0))))
        check("level gasId ean50 at 21m level", base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 21.0, 10.0))),
            base.copy(levels = listOf(DiveLevel("l1", 60.0, 25.0), DiveLevel("l2", 21.0, 10.0, gasId = "ean50"))))
        val first = DivePlannerEngine.planDive(base)
        val rep = base.copy(levels = listOf(DiveLevel("r", 30.0, 30.0)), previousTissues = first.endTissues, previousCnsPct = first.oxygen.endCnsPct, previousDailyOtu = first.oxygen.dailyOtu, surfaceIntervalMin = 60.0)
        check("*repetitive SI 60->240", rep, rep.copy(surfaceIntervalMin = 240.0))

        // Light dive where switch stops are not otherwise required
        val air = Gas.fromPercent("air", 21, 0, GasRole.BOTTOM)
        val light = base.copy(gases = listOf(air, ean50), levels = listOf(DiveLevel("x", 40.0, 15.0)), dive = DiveSettings(mixChangeExtraTimeMin = 0.0))
        check("*stopOnGasSwitch false (40m/15 air, EAN50)", light, light.copy(dive = light.dive.copy(stopOnGasSwitch = false)))
        check("*maxGasDensity 5.2->6.3 (40m air)", light, light.copy(dive = light.dive.copy(maxGasDensityGL = 6.3)))
        check("*ppN2MaxDeco 3.16->1.5 (40m air)", light, light.copy(dive = light.dive.copy(ppN2MaxDeco = 1.5)))

        val bg = GeneralSettings(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
        for (depth in listOf(45.0, 55.0, 90.0)) {
            fun bm(gen: GeneralSettings = bg, dv: DiveSettings = DiveSettings()) =
                GasCalculator.recommendBestMix(depth, gen, dv, levels = listOf(DiveLevel("b", depth, 20.0))).plannerGases
                    .joinToString(",") { "${it.role.name.take(2)}:${it.displayName}" }
            val b0 = bm()
            fun bmCheck(name: String, v: String) { lines += if (v == b0) "NOCHANGE bestMix@${depth.toInt()} $name ($b0)" else "OK bestMix@${depth.toInt()} $name: $b0 → $v" }
            bmCheck("o2Narcotic false", bm(dv = DiveSettings(o2Narcotic = false)))
            bmCheck("ppN2Max 2.5", bm(dv = DiveSettings(ppN2Max = 2.5)))
            bmCheck("ppN2MaxDeco 2.0", bm(dv = DiveSettings(ppN2MaxDeco = 2.0)))
            bmCheck("ppO2Min 0.21", bm(dv = DiveSettings(ppO2Min = 0.21)))
        }

        // Rock-bottom expected value check: 2 divers x 20 x 1.5 = 60 L/min, from 60 m to first ascent switch at 21 m
        val rbRes = DivePlannerEngine.planDive(rb)
        val gcB = rbRes.gasConsumption.first { it.gas.id == "tx" }
        lines += "ROCKBOTTOM reserve=${gcB.reserveLitres.roundToInt()} used=${gcB.usedLitres.roundToInt()} firstSWITCHseg=${rbRes.segments.firstOrNull { it.type == SegmentType.SWITCH }?.let { "${it.type}@${it.fromDepthM}" }}"
        val rbT = DivePlannerEngine.planDive(rb.copy(gases = listOf(travel, bottom, ean50, o2)))
        lines += "ROCKBOTTOM+travel reserve=${rbT.gasConsumption.first { it.gas.id == "tx" }.reserveLitres.roundToInt()} firstSWITCHseg=${rbT.segments.firstOrNull { it.type == SegmentType.SWITCH }?.let { "@${it.fromDepthM}" }} travelReserve=${rbT.gasConsumption.first { it.gas.id == "travel" }.reserveLitres.roundToInt()}"

        lines.forEach { println("DIAG $it") }
    }
}
