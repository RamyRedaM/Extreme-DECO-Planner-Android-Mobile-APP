package com.example.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Core decompression physiology engine:
 * Haldane, Schreiner, Buhlmann ZHL-16A/B/C with Gradient Factors, NDL, GF99, SurfGF,
 * surface interval off-gassing, and experimental VPM-B bubble conservatism modifier (Section 6).
 */
object TissuesAndBuhlmann {

    private val LN2 = ln(2.0)

    fun initTissues(env: Environment, arrivedUnder12hAtAltitude: Boolean = false): TissueState {
        val pSurf = if (arrivedUnder12hAtAltitude) Constants.P_SURFACE_SEA else env.pSurfaceBar
        val pN2 = (pSurf - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * Constants.FN2_AIR
        return TissueState(List(16) { Tissue(pN2 = pN2, pHe = 0.0) })
    }

    /**
     * Constant-depth Haldane equation (Section 6.3).
     */
    fun haldane(p0: Double, pInspired: Double, halfTimeMin: Double, durationMin: Double): Double {
        if (durationMin <= 0.0) return p0
        val k = LN2 / halfTimeMin
        return p0 + (pInspired - p0) * (1.0 - exp(-k * durationMin))
    }

    /**
     * Linear depth-change Schreiner equation (Section 6.4).
     * Reduces identically to Haldane when rateBarPerMin == 0.
     */
    fun schreiner(
        p0: Double,
        pInspiredStart: Double,
        rateBarPerMin: Double,
        halfTimeMin: Double,
        durationMin: Double
    ): Double {
        if (durationMin <= 0.0) return p0
        val k = LN2 / halfTimeMin
        return pInspiredStart +
            rateBarPerMin * (durationMin - 1.0 / k) -
            (pInspiredStart - p0 - rateBarPerMin / k) * exp(-k * durationMin)
    }

    /**
     * Advances tissue tensions across a segment (OC uses exact Schreiner; CCR uses 1 m sub-steps per Section 8.6).
     */
    fun advanceTissues(
        tissues: TissueState,
        fromDepthM: Double,
        toDepthM: Double,
        durationMin: Double,
        gas: Gas,
        env: Environment,
        coeffs: List<CompartmentCoeff>,
        isCcr: Boolean = false,
        setpoint: Double? = null
    ): TissueState {
        if (durationMin <= 0.0) return tissues
        if (!isCcr || setpoint == null) {
            val pStart = UnitsAndPressure.ambientPressure(fromDepthM, env)
            val pEnd = UnitsAndPressure.ambientPressure(toDepthM, env)
            val dPdt = (pEnd - pStart) / durationMin
            val pi0N2 = (pStart - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * gas.n2
            val pi0He = (pStart - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * gas.he
            val rN2 = dPdt * gas.n2
            val rHe = dPdt * gas.he
            val updated = List(16) { i ->
                val c = coeffs[i]
                val t = tissues.compartments[i]
                val nextN2 = schreiner(t.pN2, pi0N2, rN2, c.n2HalfTime, durationMin).coerceAtLeast(0.0)
                val nextHe = schreiner(t.pHe, pi0He, rHe, c.heHalfTime, durationMin).coerceAtLeast(0.0)
                Tissue(nextN2, nextHe)
            }
            return TissueState(updated)
        } else {
            // CCR: 1 m sub-steps (or 1 step if constant depth) with midpoint inspired pressures
            val deltaD = kotlin.math.abs(toDepthM - fromDepthM)
            val steps = max(1, kotlin.math.ceil(deltaD).toInt())
            val dt = durationMin / steps
            var current = tissues
            for (s in 0 until steps) {
                val fracMid = (s + 0.5) / steps
                val midDepth = fromDepthM + (toDepthM - fromDepthM) * fracMid
                val pAmb = UnitsAndPressure.ambientPressure(midDepth, env)
                val insp = UnitsAndPressure.inspiredPressuresCCR(pAmb, gas, setpoint)
                val next = List(16) { i ->
                    val c = coeffs[i]
                    val t = current.compartments[i]
                    Tissue(
                        pN2 = haldane(t.pN2, insp.pInspiredN2, c.n2HalfTime, dt).coerceAtLeast(0.0),
                        pHe = haldane(t.pHe, insp.pInspiredHe, c.heHalfTime, dt).coerceAtLeast(0.0)
                    )
                }
                current = TissueState(next)
            }
            return current
        }
    }

    /**
     * Surface interval off-gassing on air at ambient surface pressure (Section 8.5).
     */
    fun surfaceOffgas(
        tissues: TissueState,
        intervalMin: Double,
        env: Environment,
        coeffs: List<CompartmentCoeff>
    ): TissueState {
        if (intervalMin <= 0.0) return tissues
        val pInN2 = (env.pSurfaceBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * Constants.FN2_AIR
        val updated = List(16) { i ->
            val c = coeffs[i]
            val t = tissues.compartments[i]
            Tissue(
                pN2 = haldane(t.pN2, pInN2, c.n2HalfTime, intervalMin).coerceAtLeast(0.0),
                pHe = haldane(t.pHe, 0.0, c.heHalfTime, intervalMin).coerceAtLeast(0.0)
            )
        }
        return TissueState(updated)
    }

    /**
     * Combined inert-gas weighted (a, b) coefficients (Section 6.5).
     */
    fun mixedAB(tissue: Tissue, coeff: CompartmentCoeff): Pair<Double, Double> {
        val pInert = tissue.pInert
        if (pInert <= 1e-12) return coeff.aN2 to coeff.bN2
        val a = (coeff.aN2 * tissue.pN2 + coeff.aHe * tissue.pHe) / pInert
        val b = (coeff.bN2 * tissue.pN2 + coeff.bHe * tissue.pHe) / pInert
        return a to b
    }

    /**
     * Pure Buhlmann M-value at ambient pressure (Section 6.6).
     */
    fun mValue(pAmbBar: Double, a: Double, b: Double): Double = pAmbBar / b + a

    /**
     * Tolerated ambient pressure for a compartment with Gradient Factor gf in (0..1] (Section 6.7).
     * When gf == 1.0, reduces identically to (pInert - a) * b.
     */
    fun toleratedAmbientBar(
        tissue: Tissue,
        coeff: CompartmentCoeff,
        gf: Double,
        model: DecoModel = DecoModel.ZHL16C,
        vpmConservatism: Int = 2
    ): Double {
        val pInert = tissue.pInert
        val (aRaw, b) = mixedAB(tissue, coeff)
        // VPM-B experimental: bubble-radius nucleation tightens tolerated supersaturation on fast/medium compartments
        val a = if (model == DecoModel.VPMB) {
            val cFactor = 1.0 + 0.05 * vpmConservatism.coerceIn(0, 5)
            val radiusRatio = (Coefficients.VPM_CRITICAL_RADIUS_HE_UM * tissue.pHe +
                Coefficients.VPM_CRITICAL_RADIUS_N2_UM * tissue.pN2) / max(pInert, 1e-9)
            val vpmScale = (0.86 * (radiusRatio / Coefficients.VPM_CRITICAL_RADIUS_N2_UM) / cFactor)
                .coerceIn(0.62, 0.92)
            aRaw * vpmScale
        } else {
            aRaw
        }
        val effectiveGf = gf.coerceIn(0.05, 1.0)
        return (pInert - a * effectiveGf) / (effectiveGf / b + 1.0 - effectiveGf)
    }

    fun ceilingPressureBar(
        tissues: TissueState,
        gf: Double,
        coeffs: List<CompartmentCoeff>,
        model: DecoModel = DecoModel.ZHL16C,
        vpmConservatism: Int = 2
    ): Double {
        var maxP = 0.0
        for (i in 0 until 16) {
            val pTol = toleratedAmbientBar(tissues.compartments[i], coeffs[i], gf, model, vpmConservatism)
            if (pTol > maxP) maxP = pTol
        }
        return maxP
    }

    fun ceilingDepthM(
        tissues: TissueState,
        gf: Double,
        coeffs: List<CompartmentCoeff>,
        env: Environment,
        model: DecoModel = DecoModel.ZHL16C,
        vpmConservatism: Int = 2
    ): Double {
        val pCeil = ceilingPressureBar(tissues, gf, coeffs, model, vpmConservatism)
        return UnitsAndPressure.depthFromPressure(pCeil, env).coerceAtLeast(0.0)
    }

    /**
     * Linear GF slope from GF_low at firstStopDepthM to GF_high at surface (Section 6.8).
     */
    fun gfAtDepth(depthM: Double, firstStopDepthM: Double, gfLow: Double, gfHigh: Double): Double {
        if (firstStopDepthM <= 0.0) return gfHigh
        val clampedDepth = depthM.coerceIn(0.0, firstStopDepthM)
        return gfHigh + (gfLow - gfHigh) * (clampedDepth / firstStopDepthM)
    }

    /**
     * Current supersaturation % of M-value gradient at ambient pressure (Section 6.9).
     * Returns (maxGf99Pct, leadingCompartmentIndex).
     */
    fun gf99WithLeader(
        tissues: TissueState,
        pAmbBar: Double,
        coeffs: List<CompartmentCoeff>
    ): Pair<Double, Int> {
        var maxGf = Double.NEGATIVE_INFINITY
        var leader = 0
        for (i in 0 until 16) {
            val t = tissues.compartments[i]
            val (a, b) = mixedAB(t, coeffs[i])
            val mVal = mValue(pAmbBar, a, b)
            val denom = (mVal - pAmbBar).coerceAtLeast(1e-6)
            val gfPct = ((t.pInert - pAmbBar) / denom) * 100.0
            if (gfPct > maxGf) {
                maxGf = gfPct
                leader = i
            }
        }
        return max(0.0, maxGf) to leader
    }

    fun gf99Pct(tissues: TissueState, pAmbBar: Double, coeffs: List<CompartmentCoeff>): Double =
        gf99WithLeader(tissues, pAmbBar, coeffs).first

    fun surfaceGfPct(tissues: TissueState, env: Environment, coeffs: List<CompartmentCoeff>): Double =
        gf99Pct(tissues, env.pSurfaceBar, coeffs)

    /**
     * Saturation percentage for each of the 16 compartments relative to their surface M-value
     * (0% = surface equilibrium, 100% = M-value at surface P_amb).
     */
    fun compartmentSurfaceLoadPct(
        tissues: TissueState,
        env: Environment,
        coeffs: List<CompartmentCoeff>
    ): List<Double> {
        val pSurf = env.pSurfaceBar
        return List(16) { i ->
            val t = tissues.compartments[i]
            val (a, b) = mixedAB(t, coeffs[i])
            val mSurf = mValue(pSurf, a, b)
            ((t.pInert / mSurf) * 100.0).coerceAtLeast(0.0)
        }
    }

    /**
     * No-decompression limit (NDL) at constant depth with gas (Section 6.10).
     * Steps 1 min then refines to 1 s, capped at 999 min.
     */
    fun computeNdlMin(
        depthM: Double,
        gas: Gas,
        general: GeneralSettings,
        env: Environment,
        initialTissues: TissueState? = null,
        isCcr: Boolean = false,
        setpoint: Double? = null
    ): Double {
        val coeffs = Coefficients.tableFor(general.decoModel, general.useCompartment1b)
        val gfHigh = general.gfHigh / 100.0
        var tissues = initialTissues ?: initTissues(env, general.arrivedUnder12hAtAltitude)
        if (ceilingDepthM(tissues, gfHigh, coeffs, env, general.decoModel, general.vpmConservatism) > 0.0) {
            return 0.0
        }
        var minutes = 0
        while (minutes < 999) {
            val next = advanceTissues(tissues, depthM, depthM, 1.0, gas, env, coeffs, isCcr, setpoint)
            if (ceilingDepthM(next, gfHigh, coeffs, env, general.decoModel, general.vpmConservatism) > 0.0) {
                // Refine to 1 second resolution within this minute
                var secTissues = tissues
                for (sec in 1..60) {
                    secTissues = advanceTissues(secTissues, depthM, depthM, 1.0 / 60.0, gas, env, coeffs, isCcr, setpoint)
                    if (ceilingDepthM(secTissues, gfHigh, coeffs, env, general.decoModel, general.vpmConservatism) > 0.0) {
                        return minutes + (sec - 1) / 60.0
                    }
                }
                return minutes.toDouble()
            }
            tissues = next
            minutes++
        }
        return 999.0
    }

    /**
     * Surface Interval Calculator based on the Bühlmann ZHL-16C model.
     * Estimates remaining inert gas levels (N2 & He across all 16 compartments),
     * leading compartment saturation %, Surface GF decay, NOAA 90-min CNS decay,
     * clean-tissue off-gassing time, and repetitive NDL impact for a sequential dive.
     */
    fun estimateSurfaceInterval(
        endTissues: TissueState,
        endCnsPct: Double,
        surfaceIntervalMin: Double,
        env: Environment,
        nextDiveDepthM: Double,
        nextDiveGas: Gas,
        general: GeneralSettings
    ): SurfaceIntervalEstimate {
        val zhl16c = Coefficients.tableFor(DecoModel.ZHL16C, general.useCompartment1b)
        val si = surfaceIntervalMin.coerceIn(0.0, 1440.0)
        val ambientN2 = ((env.pSurfaceBar - Constants.P_WATER_VAPOUR) * Constants.FN2_AIR).coerceAtLeast(0.0)

        val postSi = if (si > 0.0) surfaceOffgas(endTissues, si, env, zhl16c) else endTissues

        val loadsBefore = compartmentSurfaceLoadPct(endTissues, env, zhl16c)
        val loadsAfter = compartmentSurfaceLoadPct(postSi, env, zhl16c)

        val excessBeforeSum = endTissues.compartments.sumOf { (it.pInert - ambientN2).coerceAtLeast(0.0) }
        val excessAfterSum = postSi.compartments.sumOf { (it.pInert - ambientN2).coerceAtLeast(0.0) }
        val remainingExcessPct = if (excessBeforeSum > 1e-4) {
            ((excessAfterSum / excessBeforeSum) * 100.0).coerceIn(0.0, 100.0)
        } else {
            0.0
        }

        val excessBeforePeak = endTissues.compartments.maxOf { (it.pInert - ambientN2).coerceAtLeast(0.0) }
        val excessAfterPeak = postSi.compartments.maxOf { (it.pInert - ambientN2).coerceAtLeast(0.0) }
        val peakN2 = postSi.compartments.maxOf { it.pN2 }
        val peakHe = postSi.compartments.maxOf { it.pHe }

        val leaderIdx = loadsAfter.indices.maxByOrNull { loadsAfter[it] } ?: 0
        val surfGfBefore = surfaceGfPct(endTissues, env, zhl16c)
        val surfGfAfter = surfaceGfPct(postSi, env, zhl16c)
        val cnsAfter = endCnsPct * Math.pow(0.5, si / Constants.CNS_DECAY_HALF_TIME_MIN)

        val zhlGeneral = general.copy(decoModel = DecoModel.ZHL16C)
        val freshTissues = initTissues(env, general.arrivedUnder12hAtAltitude)
        val safeNextDepth = nextDiveDepthM.coerceIn(6.0, 100.0)
        val freshNdl = computeNdlMin(safeNextDepth, nextDiveGas, zhlGeneral, env, freshTissues)
        val nextNdl = computeNdlMin(safeNextDepth, nextDiveGas, zhlGeneral, env, postSi)

        // Estimate minutes until total excess inert gas drops below 10% of surfacing excess (capped at 24h)
        var cleanTime = 0.0
        if (excessBeforeSum > 1e-4) {
            var tMin = 0.0
            while (tMin <= 1440.0) {
                val sim = surfaceOffgas(endTissues, tMin, env, zhl16c)
                val remSum = sim.compartments.sumOf { (it.pInert - ambientN2).coerceAtLeast(0.0) }
                if (remSum / excessBeforeSum <= 0.10) {
                    cleanTime = tMin
                    break
                }
                tMin += 15.0
                cleanTime = tMin
            }
        }

        return SurfaceIntervalEstimate(
            surfaceIntervalMin = si,
            startTissues = endTissues,
            postSiTissues = postSi,
            ambientN2Bar = ambientN2,
            excessInertBeforeBar = excessBeforePeak,
            excessInertAfterBar = excessAfterPeak,
            remainingExcessInertPct = remainingExcessPct,
            residualN2PeakBar = peakN2,
            residualHePeakBar = peakHe,
            leadingCompartmentNumber = leaderIdx + 1,
            leadingN2HalfTimeMin = zhl16c[leaderIdx].n2HalfTime,
            leadingSaturationBeforePct = loadsBefore[leaderIdx],
            leadingSaturationAfterPct = loadsAfter[leaderIdx],
            surfaceGfBeforePct = surfGfBefore,
            surfaceGfAfterPct = surfGfAfter,
            cnsBeforePct = endCnsPct,
            cnsAfterPct = cnsAfter,
            freshNdlMin = freshNdl,
            nextDiveNdlMin = nextNdl,
            compartmentLoadsBeforePct = loadsBefore,
            compartmentLoadsAfterPct = loadsAfter,
            estimatedCleanTimeMin = cleanTime.coerceAtMost(1440.0)
        )
    }
}
