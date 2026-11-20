package com.example.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Oxygen toxicity (CNS % via NOAA table + 90 min decay, OTU via Baker integral),
 * Gas consumption + reserves + turn/min pressures (Section 10),
 * and full safety checks / alerts engine (Section 9).
 */
object OxygenAndConsumption {

    // ---- CNS (Section 9.1) ----

    fun cnsLimitMinutes(ppO2: Double): Double {
        if (ppO2 <= 0.50) return Double.POSITIVE_INFINITY
        val table = Constants.NOAA_CNS_TABLE
        if (ppO2 <= 0.60) {
            // Between 0.50 and 0.60, NOAA starts at 0.60 = 720 min; interpolate gently from 0.50->900..0.60->720 or use 720
            return 720.0 + (0.60 - ppO2) / 0.10 * 180.0
        }
        for (i in 1 until table.size) {
            val (p0, l0) = table[i - 1]
            val (p1, l1) = table[i]
            if (ppO2 <= p1) {
                if (l0.isInfinite()) return l1
                val frac = (ppO2 - p0) / (p1 - p0)
                return l0 + frac * (l1 - l0)
            }
        }
        // Above 1.65 bar: steep extrapolation, floor at 1 min
        val over = ppO2 - 1.65
        return (15.0 * (0.25).pow(over / 0.05)).coerceAtLeast(1.0)
    }

    fun segmentCnsPct(ppO2Start: Double, ppO2End: Double, durationMin: Double): Double {
        if (durationMin <= 0.0) return 0.0
        if (abs(ppO2End - ppO2Start) < 1e-6) {
            if (ppO2Start <= 0.50) return 0.0
            val limit = cnsLimitMinutes(ppO2Start)
            return if (limit.isInfinite()) 0.0 else (durationMin / limit) * 100.0
        }
        // Integrate in <= 10 second steps
        val steps = max(1, ceil(durationMin * 6.0).toInt())
        val dt = durationMin / steps
        var total = 0.0
        for (i in 0 until steps) {
            val frac = (i + 0.5) / steps
            val p = ppO2Start + (ppO2End - ppO2Start) * frac
            if (p > 0.50) {
                val limit = cnsLimitMinutes(p)
                if (!limit.isInfinite()) {
                    total += (dt / limit) * 100.0
                }
            }
        }
        return total
    }

    fun decayCnsPct(cnsPct: Double, surfaceIntervalMin: Double): Double {
        if (surfaceIntervalMin <= 0.0 || cnsPct <= 0.0) return cnsPct
        return cnsPct * 0.5.pow(surfaceIntervalMin / Constants.CNS_DECAY_HALF_TIME_MIN)
    }

    // ---- OTU (Section 9.2) ----

    fun segmentOtu(ppO2Start: Double, ppO2End: Double, durationMin: Double): Double {
        if (durationMin <= 0.0) return 0.0
        if (ppO2Start <= 0.5 && ppO2End <= 0.5) return 0.0
        if (abs(ppO2End - ppO2Start) < 1e-6) {
            return if (ppO2Start <= 0.5) 0.0 else durationMin * ((ppO2Start - 0.5) / 0.5).pow(0.83)
        }
        // Clip and split where linear ramp crosses 0.5 bar
        var p1 = ppO2Start
        var p2 = ppO2End
        var t = durationMin
        if (p1 < 0.5 && p2 > 0.5) {
            val fracAbove = (p2 - 0.5) / (p2 - p1)
            t *= fracAbove
            p1 = 0.5
        } else if (p1 > 0.5 && p2 < 0.5) {
            val fracAbove = (p1 - 0.5) / (p1 - p2)
            t *= fracAbove
            p2 = 0.5
        }
        val term2 = ((p2 - 0.5).coerceAtLeast(0.0) / 0.5).pow(11.0 / 6.0)
        val term1 = ((p1 - 0.5).coerceAtLeast(0.0) / 0.5).pow(11.0 / 6.0)
        return ((3.0 / 11.0) * t / (p2 - p1) * (term2 - term1)).coerceAtLeast(0.0)
    }

    fun computeOxygenSummary(
        segments: List<Segment>,
        previousCnsPct: Double,
        previousDailyOtu: Double,
        surfaceIntervalMin: Double
    ): OxygenSummary {
        val startCns = decayCnsPct(previousCnsPct, surfaceIntervalMin)
        var diveCns = 0.0
        var diveOtu = 0.0
        var maxPpO2 = 0.0
        for (seg in segments) {
            diveCns += segmentCnsPct(seg.ppO2Start, seg.ppO2End, seg.durationMin)
            diveOtu += segmentOtu(seg.ppO2Start, seg.ppO2End, seg.durationMin)
            maxPpO2 = max(maxPpO2, max(seg.ppO2Start, seg.ppO2End))
        }
        return OxygenSummary(
            diveCnsPct = diveCns,
            startCnsPct = startCns,
            endCnsPct = startCns + diveCns,
            diveOtu = diveOtu,
            dailyOtu = previousDailyOtu + diveOtu,
            maxPpO2 = maxPpO2
        )
    }

    // ---- Gas Consumption & Reserves (Section 10) ----

    fun computeGasConsumption(
        segments: List<Segment>,
        input: DivePlanInput,
        env: Environment
    ): List<GasConsumptionResult> {
        val usedByGasId = linkedMapOf<String, Double>()
        val gasById = linkedMapOf<String, Gas>()

        // Register all enabled gases so they appear in order if used
        for (g in input.gases.filter { it.enabled }) {
            gasById[g.id] = g
        }

        val tempCorrection = 293.15 / (273.15 + input.general.surfaceTempC.coerceIn(-10, 45))

        for (seg in segments) {
            gasById[seg.gas.id] = seg.gas
            val pStart = UnitsAndPressure.ambientPressure(seg.fromDepthM, env)
            val pEnd = UnitsAndPressure.ambientPressure(seg.toDepthM, env)
            val pAvg = (pStart + pEnd) / 2.0

            val rmv = when {
                seg.isCcr -> {
                    // CCR consumes mostly O2 metabolically (~1 L/min) + small diluent for descent compression
                    if (seg.type == SegmentType.DESCENT) 3.5 else 0.8
                }
                seg.type == SegmentType.STOP || seg.type == SegmentType.SWITCH ||
                    seg.type == SegmentType.BREAK -> input.consumption.decoRmvLMin
                seg.type == SegmentType.ASCENT && seg.fromDepthM <= (segments.firstOrNull { it.type == SegmentType.STOP }?.fromDepthM ?: 0.0) ->
                    input.consumption.decoRmvLMin
                else -> input.consumption.bottomRmvLMin
            }

            val volLitres = rmv * (pAvg / env.pSurfaceBar) * seg.durationMin * tempCorrection
            usedByGasId[seg.gas.id] = (usedByGasId[seg.gas.id] ?: 0.0) + volLitres
        }

        // Rock-bottom calculation for bottom gas: 2 divers at stress RMV from max depth to first switch + 1 min problem solving
        val maxDepthM = segments.maxOfOrNull { max(it.fromDepthM, it.toDepthM) } ?: 0.0
        val firstSwitchDepth = segments.firstOrNull { it.type == SegmentType.SWITCH }?.fromDepthM ?: 0.0
        val ascentDist = (maxDepthM - firstSwitchDepth).coerceAtLeast(0.0)
        val ascentMin = ascentDist / input.dive.ascentRateToFirstStopMMin.coerceAtLeast(1.0)
        val pMax = UnitsAndPressure.ambientPressure(maxDepthM, env)
        val pSwitch = UnitsAndPressure.ambientPressure(firstSwitchDepth, env)
        val stressRmv2Divers = input.consumption.bottomRmvLMin * input.consumption.stressFactor * 2.0
        val rockBottomReserve = stressRmv2Divers * (
            (pMax / env.pSurfaceBar) * 1.0 +
                (((pMax + pSwitch) / 2.0) / env.pSurfaceBar) * ascentMin
            )

        return usedByGasId.map { (gasId, usedL) ->
            val gas = gasById[gasId]!!
            val isDeco = gas.role == GasRole.DECO || gas.role == GasRole.BAILOUT
            val reserveL = if (isDeco) {
                when (input.consumption.decoReserveRule) {
                    ReserveRuleDeco.MULTIPLIER_1_5 -> usedL * 0.5
                    ReserveRuleDeco.LOST_GAS -> usedL * (input.consumption.stressFactor - 1.0).coerceAtLeast(0.5)
                }
            } else {
                when (input.consumption.bottomReserveRule) {
                    ReserveRuleBottom.THIRDS -> usedL * 0.5
                    ReserveRuleBottom.QUARTERS -> usedL * (1.0 / 3.0)
                    ReserveRuleBottom.HALF -> usedL * 1.0
                    ReserveRuleBottom.ROCK_BOTTOM -> max(rockBottomReserve, usedL * 0.5)
                }
            }
            val totalReq = usedL + reserveL
            val cyl = gas.cylinder
            val totalWaterVol = (cyl.waterVolumeL * cyl.count).coerceAtLeast(1.0)
            val availableL = cyl.availableLitres
            val minPressure = reserveL / totalWaterVol
            val usableBottom = (availableL - reserveL).coerceAtLeast(0.0)
            val turnPressure = (cyl.fillPressureBar - (usableBottom / 2.0) / totalWaterVol).coerceIn(minPressure, cyl.fillPressureBar)
            val remainingBar = ((availableL - usedL) / totalWaterVol)

            GasConsumptionResult(
                gas = gas,
                usedLitres = usedL,
                reserveLitres = reserveL,
                totalRequiredLitres = totalReq,
                availableLitres = availableL,
                turnPressureBar = turnPressure,
                minPressureBar = minPressure,
                remainingBar = remainingBar,
                isSufficient = availableL >= totalReq - 1e-3
            )
        }
    }

    // ---- Critical Safety Checks Only ----

    fun runSafetyChecks(
        input: DivePlanInput,
        env: Environment,
        segments: List<Segment>,
        oxygen: OxygenSummary,
        consumption: List<GasConsumptionResult>
    ): List<PlanAlert> {
        val list = mutableListOf<PlanAlert>()
        val dive = input.dive
        val alerts = input.alerts

        if (input.general.gfLow > input.general.gfHigh) {
            list += PlanAlert(
                AlertLevel.ERROR,
                AlertCode.GF_EXTREME,
                "Invalid Gradient Factors: GF Low (${input.general.gfLow}%) cannot exceed GF High (${input.general.gfHigh}%)."
            )
        }

        var erroredPpO2Bottom = false
        var erroredPpO2Deco = false
        var erroredHypoxic = false
        var erroredDensity = false
        var erroredMod = false

        val criticalBottomPpO2 = max(dive.ppO2Bottom, 1.60) + 0.03
        val criticalDecoPpO2 = max(dive.ppO2Deco, 1.60) + 0.03

        segments.forEachIndexed { idx, seg ->
            val maxPpO2Seg = max(seg.ppO2Start, seg.ppO2End)
            val minPpO2Seg = kotlin.math.min(seg.ppO2Start, seg.ppO2End)
            val isDecoPhase = seg.type == SegmentType.STOP || seg.type == SegmentType.SWITCH ||
                (seg.type == SegmentType.ASCENT && seg.gas.role == GasRole.DECO)

            // 1. Critical Hypoxia (< ppO2Min)
            if (minPpO2Seg < dive.ppO2Min - 0.01 && !erroredHypoxic) {
                erroredHypoxic = true
                list += PlanAlert(
                    AlertLevel.ERROR,
                    AlertCode.HYPOXIC,
                    "Critical hypoxia on ${seg.gas.displayName}: ppO₂ drops to ${"%.2f".format(minPpO2Seg)} bar (< ${"%.2f".format(dive.ppO2Min)} bar).",
                    idx
                )
            }

            // 2. Critical High ppO2 (> 1.60 bar acute CNS toxicity risk)
            if (!isDecoPhase) {
                if (maxPpO2Seg > criticalBottomPpO2 && !erroredPpO2Bottom) {
                    erroredPpO2Bottom = true
                    list += PlanAlert(
                        AlertLevel.ERROR,
                        AlertCode.PPO2_HIGH_BOTTOM,
                        "Critical bottom ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} (> 1.60 bar O₂ toxicity limit).",
                        idx
                    )
                }
            } else {
                if (maxPpO2Seg > criticalDecoPpO2 && !erroredPpO2Deco) {
                    erroredPpO2Deco = true
                    list += PlanAlert(
                        AlertLevel.ERROR,
                        AlertCode.PPO2_HIGH_DECO,
                        "Critical deco ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} (> ${"%.2f".format(max(dive.ppO2Deco, 1.60))} bar limit).",
                        idx
                    )
                }
            }

            // 3. Critical MOD violation (only emit if not already flagged by ppO2 error, with 1.5m margin)
            if (!seg.isCcr && !erroredPpO2Bottom && !erroredPpO2Deco && !erroredMod) {
                val maxSegDepth = max(seg.fromDepthM, seg.toDepthM)
                val limitPp = if (isDecoPhase) max(dive.ppO2Deco, 1.60) else max(dive.ppO2Bottom, 1.60)
                val allowedMod = if (isDecoPhase) {
                    GasCalculator.effectiveSwitchModM(seg.gas, limitPp, env) + 1.5
                } else {
                    (seg.gas.modOverrideM ?: GasCalculator.modExactM(seg.gas, limitPp, env)) + 1.5
                }
                if (maxSegDepth > allowedMod) {
                    erroredMod = true
                    list += PlanAlert(
                        AlertLevel.ERROR,
                        AlertCode.MOD_EXCEEDED,
                        "${seg.gas.displayName} breathed at ${maxSegDepth.roundToInt()} m exceeds critical MOD (${allowedMod.roundToInt()} m).",
                        idx
                    )
                }
            }

            // 4. Critical Gas Density (> 6.5 g/L CO2 retention hazard)
            if (seg.densityGL > 6.5 && !erroredDensity) {
                erroredDensity = true
                list += PlanAlert(
                    AlertLevel.ERROR,
                    AlertCode.DENSITY_HIGH,
                    "Critical gas density ${"%.2f".format(seg.densityGL)} g/L (> 6.5 g/L CO₂ retention limit) on ${seg.gas.displayName}.",
                    idx
                )
            }
        }

        // 5. Critical CNS Oxygen Toxicity (> error threshold, default 100%)
        if (oxygen.endCnsPct > alerts.cnsErrorPct) {
            list += PlanAlert(
                AlertLevel.ERROR,
                AlertCode.CNS,
                "Critical CNS oxygen toxicity ${oxygen.endCnsPct.roundToInt()}% exceeds limit (${alerts.cnsErrorPct.roundToInt()}%)."
            )
        }

        // 6. Critical Insufficient Gas Volume
        for (gc in consumption) {
            if (!gc.isSufficient) {
                list += PlanAlert(
                    AlertLevel.ERROR,
                    AlertCode.GAS_VOLUME,
                    "Insufficient ${gc.gas.displayName}: requires ${gc.totalRequiredLitres.roundToInt()} L (incl. reserve), cylinder holds ${gc.availableLitres.roundToInt()} L."
                )
            }
        }

        return list
    }
}
