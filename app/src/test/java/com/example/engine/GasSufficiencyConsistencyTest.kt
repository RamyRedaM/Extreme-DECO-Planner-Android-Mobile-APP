package com.example.engine

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reproduces the reported dive day (Dive 1: 43 m/15 min + 82 m/20 min, GF 70/70, Best GAS Mix;
 * Dive 2: 25 m after a 90 min surface interval) and checks that gas sufficiency, the cylinders
 * shown, and the CRITICAL "Insufficient gas" alert always agree, including after the app's
 * repeated recompute and dive-switching cycles.
 */
class GasSufficiencyConsistencyTest {

    private val general = GeneralSettings(gfLow = 70, gfHigh = 70, gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
    private val dives = listOf(
        MultiDiveEntry("d1", "Dive 1", 0.0, listOf(DiveLevel("l1", 43.0, 15.0), DiveLevel("l2", 82.0, 20.0))),
        MultiDiveEntry("d2", "Dive 2", 90.0, listOf(DiveLevel("l3", 25.0, 25.0)))
    )

    private fun describe(tag: String, res: DivePlanResult): String =
        "DIAG $tag: " + res.gasConsumption.joinToString(" | ") {
            "${it.gas.displayName} ${it.gas.cylinder.count}x${it.gas.cylinder.waterVolumeL.toInt()}L " +
                "need ${it.totalRequiredLitres.toInt()} have ${it.availableLitres.toInt()} ok=${it.isSufficient}"
        } + " || inputGases: " + res.input.gases.joinToString(", ") { "${it.id}:${it.displayName}:${it.cylinder.count}x" } +
            " || alerts: " + res.alerts.joinToString(", ") { "${it.level}/${it.code}" }

    private fun assertConsistent(tag: String, res: DivePlanResult) {
        res.gasConsumption.filter { !it.isSufficient }.forEach { gc ->
            assertTrue(
                "$tag: ${gc.gas.displayName} is insufficient but no CRITICAL GAS_VOLUME alert",
                res.alerts.any { it.code == AlertCode.GAS_VOLUME && it.level == AlertLevel.CRITICAL }
            )
        }
        // In Best GAS Mix mode the app sizes cylinders itself, so every gas must be sufficient.
        assertTrue(
            "$tag: Best GAS Mix left insufficient gas: ${describe(tag, res)}",
            res.gasConsumption.all { it.isSufficient }
        )
        // Cylinders in the plan's input must match the cylinders used for consumption.
        val byId = res.input.gases.associateBy { it.id }
        res.gasConsumption.forEach { gc ->
            val g = byId[gc.gas.id]
            if (g != null) {
                assertTrue(
                    "$tag: ${gc.gas.displayName} shown as ${g.cylinder.count}x but planned with ${gc.gas.cylinder.count}x",
                    g.cylinder.count == gc.gas.cylinder.count
                )
            }
        }
    }

    @Test
    fun reportedDiveDayIsConsistentAcrossRecomputes() {
        var input = DivePlanInput(general = general)
        // Simulate the view model: compute, sync gases from the active dive, compute again, switch dives.
        val activeSequence = listOf(0, 0, 1, 1, 0, 0)
        activeSequence.forEachIndexed { step, activeIdx ->
            val effective = dives.map { d -> d.copy(levels = d.levels.map { it.copy(gasId = null) }) }
            val day = DivePlannerEngine.planMultiDiveDay(input, effective, includeContingenciesForIndex = -1)
            day.diveResults.forEachIndexed { i, r -> assertConsistent("step$step active$activeIdx dive${i + 1}", r) }
            input = input.copy(gases = day.diveResults[activeIdx].input.gases)
        }
    }
}
