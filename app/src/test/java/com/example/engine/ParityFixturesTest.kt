package com.example.engine

import org.junit.Test
import kotlin.math.roundToInt

/**
 * Prints reference results for a fixed set of dives ("PARITY|..." lines). The iOS engine test
 * ios/DecoEngine/Tests/DecoEngineTests/ParityTests.swift must reproduce them. When the engine changes on
 * purpose, rerun this test and copy the new lines into ParityTests.swift (see ios/README.md).
 */
class ParityFixturesTest {

    companion object {
        fun fp(id: String, r: DivePlanResult): String = listOf(
            "PARITY", id,
            "%.2f".format(java.util.Locale.US, r.totalRuntimeMin),
            "%.2f".format(java.util.Locale.US, r.totalDecoMin),
            r.firstStopM.toString(),
            r.stops.joinToString(",") { "${it.depthM}:${"%.2f".format(java.util.Locale.US, it.durationMin)}:${it.gas.displayName}" },
            "%.2f".format(java.util.Locale.US, r.oxygen.endCnsPct),
            "%.2f".format(java.util.Locale.US, r.oxygen.diveOtu),
            "%.1f".format(java.util.Locale.US, r.endSurfaceGfPct),
            r.gasConsumption.joinToString(",") { "${it.gas.displayName}=${it.totalRequiredLitres.roundToInt()}/${it.availableLitres.roundToInt()}" },
            r.alerts.joinToString(",") { "${it.level.name}:${it.code.name}" }
        ).joinToString("|")

        private fun g(id: String, o2: Int, he: Int, role: GasRole, l: Double = 12.0, bar: Double = 200.0, n: Int = 1) =
            Gas.fromPercent(id, o2, he, role, cylinder = CylinderConfig(l, bar, n))

        fun scenarios(): List<Pair<String, DivePlanResult>> {
            val user = GeneralSettings(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS)
            val air = g("air", 21, 0, GasRole.BOTTOM, n = 2)
            val tx = g("tx", 18, 45, GasRole.BOTTOM, 12.0, 232.0, 2)
            val ean50 = g("ean50", 50, 0, GasRole.DECO, 11.0)
            val o2 = g("o2", 100, 0, GasRole.DECO, 7.0)
            val out = mutableListOf<Pair<String, DivePlanResult>>()

            out += "S1" to DivePlannerEngine.planDive(DivePlanInput(general = user.copy(gfLow = 20, gfHigh = 80), gases = listOf(air, ean50, o2), levels = listOf(DiveLevel("a", 40.0, 20.0))))
            val s2In = DivePlanInput(general = user, gases = listOf(tx, ean50, o2), levels = listOf(DiveLevel("a", 60.0, 25.0)))
            val s2 = DivePlannerEngine.planDive(s2In)
            out += "S2" to s2
            out += "S3" to DivePlannerEngine.planDive(DivePlanInput(general = user.copy(gfLow = 100, gfHigh = 100), gases = listOf(air), levels = listOf(DiveLevel("a", 30.0, 30.0))))
            out += "S4" to DivePlannerEngine.planDive(DivePlanInput(general = user.copy(planningType = PlanningType.CCR), gases = listOf(g("dil", 18, 45, GasRole.DILUENT, 3.0), g("bo", 50, 0, GasRole.BAILOUT, 11.0)), levels = listOf(DiveLevel("a", 60.0, 25.0))))
            out += "S5" to DivePlannerEngine.planDive(s2In.copy(consumption = ConsumptionSettings(bottomReserveRule = ReserveRuleBottom.ROCK_BOTTOM, decoReserveRule = ReserveRuleDeco.LOST_GAS)))
            out += "S6" to DivePlannerEngine.planDive(DivePlanInput(general = user.copy(decoModel = DecoModel.VPMB), gases = listOf(g("t2", 21, 35, GasRole.BOTTOM, n = 2), ean50, o2), levels = listOf(DiveLevel("a", 45.0, 25.0))))
            out += "S7" to DivePlannerEngine.planDive(DivePlanInput(general = user.copy(elevationM = 1500, salinityPreset = SalinityPreset.FRESH), gasBreaks = GasBreakSettings(enabled = true), gases = listOf(g("e32", 32, 0, GasRole.BOTTOM, n = 2), o2), levels = listOf(DiveLevel("a", 30.0, 40.0))))
            out += "S8" to DivePlannerEngine.planDive(DivePlanInput(general = user, gases = listOf(g("tv", 32, 0, GasRole.TRAVEL, 11.0), tx, ean50, o2), levels = listOf(DiveLevel("a", 60.0, 20.0), DiveLevel("b", 40.0, 10.0))))
            val day = DivePlannerEngine.planMultiDiveDay(
                DivePlanInput(general = GeneralSettings(gfLow = 70, gfHigh = 70)),
                listOf(
                    MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("a", 43.0, 15.0), DiveLevel("b", 82.0, 20.0))),
                    MultiDiveEntry("d2", "Dive 2", 90.0, listOf(DiveLevel("c", 25.0, 25.0)))
                ),
                includeContingenciesForIndex = -1
            )
            out += "S9a" to day.diveResults[0]
            out += "S9b" to day.diveResults[1]
            return out
        }
    }

    @Test
    fun printParityFixtures() {
        scenarios().forEach { (id, r) -> println("DIAG " + fp(id, r)) }
        val bm = GasCalculator.recommendBestMix(70.0, GeneralSettings(), DiveSettings(), levels = listOf(DiveLevel("x", 70.0, 20.0)))
        println("DIAG PARITY|BM70|" + bm.plannerGases.joinToString(",") { "${it.role.name}:${it.displayName}:${it.modOverrideM ?: "-"}" })
        val s2 = scenarios().first { it.first == "S2" }.second
        val si = TissuesAndBuhlmann.estimateSurfaceInterval(s2.endTissues, s2.oxygen.endCnsPct, 60.0, s2.env, 25.0, Gas.fromPercent("x", 32, 0, GasRole.BOTTOM), GeneralSettings())
        println("DIAG PARITY|SI|" + listOf("%.2f".format(java.util.Locale.US, si.remainingExcessInertPct), si.leadingCompartmentNumber, "%.2f".format(java.util.Locale.US, si.surfaceGfAfterPct), "%.2f".format(java.util.Locale.US, si.cnsAfterPct), "%.2f".format(java.util.Locale.US, si.nextDiveNdlMin), "%.2f".format(java.util.Locale.US, si.freshNdlMin), si.estimatedCleanTimeMin).joinToString("|"))
    }
}
