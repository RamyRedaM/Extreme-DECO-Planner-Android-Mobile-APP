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
            val turnLower = kotlin.math.min(minPressure, cyl.fillPressureBar)
            val turnPressure = (cyl.fillPressureBar - (usableBottom / 2.0) / totalWaterVol).coerceIn(turnLower, cyl.fillPressureBar)
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

    // ---- 4-Tier Categorized Safety Checks: CRITICAL / HIGH / MEDIUM / LOW ----

    fun runSafetyChecks(
        input: DivePlanInput,
        env: Environment,
        segments: List<Segment>,
        oxygen: OxygenSummary,
        consumption: List<GasConsumptionResult>,
        endSurfaceGfPct: Double = 0.0,
        ttsMin: Double = 0.0,
        totalDecoMin: Double = 0.0,
        ndlMin: Double = Double.POSITIVE_INFINITY,
        totalRuntimeMin: Double = 0.0,
        maxDepthM: Double = segments.maxOfOrNull { max(it.fromDepthM, it.toDepthM) } ?: 0.0
    ): List<PlanAlert> {
        val list = mutableListOf<PlanAlert>()
        val dive = input.dive
        val alerts = input.alerts
        val general = input.general
        val isCcr = general.planningType == PlanningType.CCR
        val enabledGases = input.gases.filter { it.enabled }
        val hasDecoStops = totalDecoMin > 0.0 || segments.any { it.type == SegmentType.STOP }

        // 0. Gradient Factor validation (CRITICAL / MEDIUM / LOW)
        if (general.gfLow > general.gfHigh) {
            list += PlanAlert(
                AlertLevel.CRITICAL,
                AlertCode.GF_EXTREME,
                "Invalid Gradient Factors: GF Low (${general.gfLow}%) cannot exceed GF High (${general.gfHigh}%)."
            )
        } else {
            if (general.gfHigh > 90 || (hasDecoStops && general.gfLow > 60)) {
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.GF_EXTREME,
                    "Aggressive Gradient Factors (GF ${general.gfLow}/${general.gfHigh}): reduced decompression margin."
                )
            } else if (general.gfHigh < 65 || general.gfLow < 15) {
                list += PlanAlert(
                    AlertLevel.LOW,
                    AlertCode.GF_EXTREME,
                    "Very conservative Gradient Factors (GF ${general.gfLow}/${general.gfHigh}): significantly increases deco time."
                )
            }
        }

        // 0b. Surfacing Supersaturation / Surface GF (CRITICAL > 100%, HIGH > 90%)
        if (endSurfaceGfPct > 100.0 + 0.5) {
            list += PlanAlert(
                AlertLevel.CRITICAL,
                AlertCode.SURFACE_GF_HIGH,
                "Surfacing supersaturation (${endSurfaceGfPct.roundToInt()}% Surf GF) exceeds Bühlmann M-value — omitted decompression / acute DCS hazard."
            )
        } else if (endSurfaceGfPct > 90.0) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.SURFACE_GF_HIGH,
                "High surfacing tissue stress (Surf GF ${endSurfaceGfPct.roundToInt()}%) — elevated post-dive DCS risk; lower GF High or extend shallow stops."
            )
        }

        // 1. Ascent & Descent Rate validation (MEDIUM)
        if (dive.ascentRateToFirstStopMMin > 12.0 || dive.ascentRateToSurfaceMMin > 9.0) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.ASCENT_FAST,
                "Fast ascent rate configured (${dive.ascentRateToFirstStopMMin.roundToInt()} m/min ascent, ${dive.ascentRateToSurfaceMMin.roundToInt()} m/min to surface)."
            )
        }
        if (dive.descentRateMMin > 24.0) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.DESCENT_FAST,
                "Fast descent rate (${dive.descentRateMMin.roundToInt()} m/min > 24 m/min): increases rapid narcosis onset, equalization barotrauma, and HPNS risk."
            )
        }

        // 1b. Hypoxic Bottom Gas Without Surface-Breathable Travel Gas / Coverage Gap (CRITICAL)
        if (!isCcr) {
            val primaryBottom = enabledGases.firstOrNull { it.role == GasRole.BOTTOM } ?: enabledGases.firstOrNull()
            if (primaryBottom != null) {
                val bottomMinM = GasCalculator.minDepthM(primaryBottom, dive.ppO2Min, env)
                if (bottomMinM > 0.0) {
                    val surfaceBreathable = enabledGases.filter {
                        it.id != primaryBottom.id && GasCalculator.minDepthM(it, dive.ppO2Min, env) <= 0.0
                    }
                    if (surfaceBreathable.isEmpty()) {
                        list += PlanAlert(
                            AlertLevel.CRITICAL,
                            AlertCode.HYPOXIC_NO_TRAVEL,
                            "Hypoxic bottom mix ${primaryBottom.displayName} (min depth ${bottomMinM.roundToInt()} m) has no normoxic travel/deco gas breathable at the surface."
                        )
                    } else {
                        val deepestBridgeMod = surfaceBreathable.maxOf {
                            it.modOverrideM ?: GasCalculator.modExactM(it, dive.ppO2Bottom, env)
                        }
                        if (deepestBridgeMod + 1.0 < bottomMinM) {
                            list += PlanAlert(
                                AlertLevel.CRITICAL,
                                AlertCode.HYPOXIC_NO_TRAVEL,
                                "Gas coverage gap: travel/deco gas MOD (${deepestBridgeMod.roundToInt()} m) does not reach hypoxic ${primaryBottom.displayName} min depth (${bottomMinM.roundToInt()} m)."
                            )
                        }
                    }
                }
            }
        }

        // 1c. CCR-Specific Checks: Diluent Hyperoxia (CRITICAL/HIGH) & OC Bailout Readiness (CRITICAL)
        if (isCcr) {
            val diluent = enabledGases.firstOrNull { it.role == GasRole.DILUENT || it.role == GasRole.BOTTOM }
            if (diluent != null && maxDepthM > 0.0) {
                val pMax = UnitsAndPressure.ambientPressure(maxDepthM, env)
                val dilPpO2Max = pMax * diluent.o2
                if (dilPpO2Max > 1.60 + 0.03) {
                    list += PlanAlert(
                        AlertLevel.CRITICAL,
                        AlertCode.CCR_DILUENT_HAZARD,
                        "CCR diluent ${diluent.displayName} reaches ppO₂ ${"%.2f".format(dilPpO2Max)} bar at ${maxDepthM.roundToInt()} m (> 1.60 bar) — manual loop flush at depth causes acute O₂ toxicity."
                    )
                } else if (dilPpO2Max > 1.40 + 0.03) {
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.CCR_DILUENT_HAZARD,
                        "CCR diluent ${diluent.displayName} reaches ppO₂ ${"%.2f".format(dilPpO2Max)} bar at ${maxDepthM.roundToInt()} m (> 1.40 bar) — poor diluent flush margin."
                    )
                }
            }

            val ocBailoutGases = enabledGases.filter {
                it.role == GasRole.BAILOUT || it.role == GasRole.DECO || it.role == GasRole.TRAVEL
            }
            if (ocBailoutGases.isEmpty()) {
                list += PlanAlert(
                    AlertLevel.CRITICAL,
                    AlertCode.CCR_BAILOUT_RISK,
                    "CCR plan has no open-circuit bailout or deco gases configured — configure dedicated OC bailout cylinders."
                )
            } else if (maxDepthM > 0.0) {
                // Estimate minimum stress-RMV OC bailout ascent volume (2 min problem solving at max depth + ascent)
                val pMax = UnitsAndPressure.ambientPressure(maxDepthM, env)
                val pAvgAsc = (pMax + env.pSurfaceBar) / 2.0
                val ascTimeMin = maxDepthM / dive.ascentRateToFirstStopMMin.coerceAtLeast(3.0)
                val stressRmv = input.consumption.bottomRmvLMin * input.consumption.stressFactor
                val minBailoutLitres = stressRmv * (
                    (pMax / env.pSurfaceBar) * 2.0 + (pAvgAsc / env.pSurfaceBar) * ascTimeMin
                )
                val availableBailoutLitres = ocBailoutGases.sumOf { it.cylinder.availableLitres }
                if (availableBailoutLitres < minBailoutLitres) {
                    list += PlanAlert(
                        AlertLevel.CRITICAL,
                        AlertCode.CCR_BAILOUT_RISK,
                        "Insufficient CCR OC bailout gas: stress-RMV ascent requires ~${minBailoutLitres.roundToInt()} L, onboard bailout/deco cylinders hold ${availableBailoutLitres.roundToInt()} L."
                    )
                }
            }
        }

        // 1d. HPNS (High-Pressure Nervous Syndrome) Deep Helium Check (CRITICAL >= 150 m, HIGH >= 120 m)
        val maxPpHe = segments.maxOfOrNull { it.ppHeEnd } ?: 0.0
        if (maxDepthM >= 150.0) {
            list += PlanAlert(
                AlertLevel.CRITICAL,
                AlertCode.HPNS_RISK,
                "Extreme depth (${maxDepthM.roundToInt()} m, ppHe ${"%.1f".format(maxPpHe)} bar): severe High-Pressure Nervous Syndrome (HPNS) and respiratory failure hazard."
            )
        } else if (maxDepthM >= 120.0 && (maxPpHe >= 6.5 || dive.descentRateMMin > 18.0)) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.HPNS_RISK,
                "Deep helium HPNS risk at ${maxDepthM.roundToInt()} m (ppHe ${"%.1f".format(maxPpHe)} bar): slow descent (≤ 15 m/min) and ensure adequate N₂ narcotic balance."
            )
        }

        var critPpO2Bottom = false
        var highPpO2Bottom = false
        var critPpO2Deco = false
        var highPpO2Deco = false
        var critHypoxic = false
        var critMod = false
        var highMod = false
        var medSwitchMargin = false
        var critDensity = false
        var highDensity = false
        var medDensity = false
        var critEnd = false
        var highEnd = false
        var medEnd = false
        var highIcdN2 = false
        var medIcdFifths = false
        var lowLipid = false

        var continuousHighO2Min = 0.0
        var maxContinuousHighO2Min = 0.0

        val criticalBottomPpO2 = max(dive.ppO2Bottom, 1.60) + 0.03
        val criticalDecoPpO2 = max(dive.ppO2Deco, 1.60) + 0.03

        var prevGas: Gas? = null
        segments.forEachIndexed { idx, seg ->
            val maxPpO2Seg = max(seg.ppO2Start, seg.ppO2End)
            val minPpO2Seg = kotlin.math.min(seg.ppO2Start, seg.ppO2End)
            val isDecoPhase = seg.type == SegmentType.STOP || seg.type == SegmentType.SWITCH ||
                (seg.type == SegmentType.ASCENT && seg.gas.role == GasRole.DECO)
            val maxSegDepth = max(seg.fromDepthM, seg.toDepthM)

            // Track continuous high-ppO2 deco exposure (for O2 Air Break check)
            if (seg.type == SegmentType.BREAK || maxPpO2Seg < 1.30) {
                continuousHighO2Min = 0.0
            } else if (isDecoPhase && (maxPpO2Seg >= 1.40 || (!seg.isCcr && seg.gas.o2 >= 0.80 && maxSegDepth >= 3.0))) {
                continuousHighO2Min += seg.durationMin
                if (continuousHighO2Min > maxContinuousHighO2Min) {
                    maxContinuousHighO2Min = continuousHighO2Min
                }
            }

            // 2. Hypoxia (CRITICAL)
            if (minPpO2Seg < dive.ppO2Min - 0.01 && !critHypoxic) {
                critHypoxic = true
                list += PlanAlert(
                    AlertLevel.CRITICAL,
                    AlertCode.HYPOXIC,
                    "Hypoxic gas on ${seg.gas.displayName}: ppO₂ drops to ${"%.2f".format(minPpO2Seg)} bar (< ${"%.2f".format(dive.ppO2Min)} bar).",
                    idx
                )
            }

            // 3. High ppO2 — CRITICAL (> 1.60 bar) vs HIGH (> user configured bottom/deco ppO2 limit)
            if (!isDecoPhase) {
                if (maxPpO2Seg > criticalBottomPpO2 && !critPpO2Bottom) {
                    critPpO2Bottom = true
                    list += PlanAlert(
                        AlertLevel.CRITICAL,
                        AlertCode.PPO2_HIGH_BOTTOM,
                        "Bottom ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} exceeds 1.60 bar acute O₂ toxicity limit.",
                        idx
                    )
                } else if (!critPpO2Bottom && maxPpO2Seg > dive.ppO2Bottom + 0.03 && !highPpO2Bottom) {
                    highPpO2Bottom = true
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.PPO2_HIGH_BOTTOM,
                        "Bottom ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} exceeds your ${"%.2f".format(dive.ppO2Bottom)} bar limit.",
                        idx
                    )
                }
            } else {
                if (maxPpO2Seg > criticalDecoPpO2 && !critPpO2Deco) {
                    critPpO2Deco = true
                    list += PlanAlert(
                        AlertLevel.CRITICAL,
                        AlertCode.PPO2_HIGH_DECO,
                        "Deco ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} exceeds ${"%.2f".format(max(dive.ppO2Deco, 1.60))} bar toxicity limit.",
                        idx
                    )
                } else if (!critPpO2Deco && maxPpO2Seg > dive.ppO2Deco + 0.03 && !highPpO2Deco) {
                    highPpO2Deco = true
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.PPO2_HIGH_DECO,
                        "Deco ppO₂ ${"%.2f".format(maxPpO2Seg)} bar on ${seg.gas.displayName} exceeds your ${"%.2f".format(dive.ppO2Deco)} bar deco limit.",
                        idx
                    )
                } else if (
                    seg.type == SegmentType.SWITCH &&
                    !seg.isCcr &&
                    maxSegDepth > 6.5 &&
                    maxPpO2Seg > dive.ppO2Deco &&
                    !medSwitchMargin && !critPpO2Deco && !highPpO2Deco
                ) {
                    medSwitchMargin = true
                    list += PlanAlert(
                        AlertLevel.MEDIUM,
                        AlertCode.SWITCH_MOD_MARGIN,
                        "Tight switch MOD margin on ${seg.gas.displayName} at ${maxSegDepth.roundToInt()} m (ppO₂ ${"%.2f".format(maxPpO2Seg)} bar) — minor buoyancy excursion exceeds ${"%.2f".format(dive.ppO2Deco)} bar.",
                        idx
                    )
                }
            }

            // 4. MOD Exceeded — CRITICAL (> 1.60 bar MOD) vs HIGH (> configured MOD)
            if (!seg.isCcr) {
                val critLimitPp = if (isDecoPhase) max(dive.ppO2Deco, 1.60) else max(dive.ppO2Bottom, 1.60)
                val critAllowedMod = if (isDecoPhase) {
                    GasCalculator.effectiveSwitchModM(seg.gas, critLimitPp, env) + 1.5
                } else {
                    (seg.gas.modOverrideM ?: GasCalculator.modExactM(seg.gas, critLimitPp, env)) + 1.5
                }
                val cfgLimitPp = if (isDecoPhase) dive.ppO2Deco else dive.ppO2Bottom
                val cfgAllowedMod = if (isDecoPhase) {
                    GasCalculator.effectiveSwitchModM(seg.gas, cfgLimitPp, env) + 1.0
                } else {
                    (seg.gas.modOverrideM ?: GasCalculator.modExactM(seg.gas, cfgLimitPp, env)) + 1.0
                }

                if (maxSegDepth > critAllowedMod && !critMod && !critPpO2Bottom && !critPpO2Deco) {
                    critMod = true
                    list += PlanAlert(
                        AlertLevel.CRITICAL,
                        AlertCode.MOD_EXCEEDED,
                        "${seg.gas.displayName} breathed at ${maxSegDepth.roundToInt()} m exceeds critical MOD (${critAllowedMod.roundToInt()} m).",
                        idx
                    )
                } else if (maxSegDepth > cfgAllowedMod && !critMod && !highMod && !highPpO2Bottom && !highPpO2Deco && !critPpO2Bottom && !critPpO2Deco) {
                    highMod = true
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.MOD_EXCEEDED,
                        "${seg.gas.displayName} breathed at ${maxSegDepth.roundToInt()} m exceeds configured MOD (${cfgAllowedMod.roundToInt()} m).",
                        idx
                    )
                }
            }

            // 5. Gas Density — UHMS / Mitchell Consensus:
            //    CRITICAL (> 6.2 g/L CO2 retention redline), HIGH (> configured target, default 5.2 g/L),
            //    MEDIUM (> 5.2 g/L UHMS ideal if user raised custom limit)
            if (seg.densityGL > 6.2 + 0.02 && !critDensity) {
                critDensity = true
                list += PlanAlert(
                    AlertLevel.CRITICAL,
                    AlertCode.DENSITY_HIGH,
                    "Gas density ${"%.2f".format(seg.densityGL)} g/L on ${seg.gas.displayName} exceeds 6.2 g/L UHMS maximum limit — severe CO₂ retention & hypercapnia risk.",
                    idx
                )
            } else if (!critDensity && seg.densityGL > dive.maxGasDensityGL + 0.05 && !highDensity) {
                highDensity = true
                list += PlanAlert(
                    AlertLevel.HIGH,
                    AlertCode.DENSITY_HIGH,
                    "Gas density ${"%.2f".format(seg.densityGL)} g/L on ${seg.gas.displayName} exceeds your ${"%.1f".format(dive.maxGasDensityGL)} g/L target.",
                    idx
                )
            } else if (!critDensity && !highDensity && dive.maxGasDensityGL > 5.2 && seg.densityGL > 5.25 && !medDensity) {
                medDensity = true
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.DENSITY_HIGH,
                    "Gas density ${"%.2f".format(seg.densityGL)} g/L on ${seg.gas.displayName} exceeds 5.2 g/L UHMS ideal work-of-breathing guideline.",
                    idx
                )
            }

            // 6. Narcosis: 3-Tier END & ppN2 Escalation (CRITICAL > 45 m, HIGH > 40 m, MEDIUM > maxEndM)
            if (!critEnd && (seg.endM > 45.0 || seg.ppN2End > 4.50)) {
                critEnd = true
                list += PlanAlert(
                    AlertLevel.CRITICAL,
                    AlertCode.END_HIGH,
                    "Severe narcosis on ${seg.gas.displayName}: END ${seg.endM.roundToInt()} m (> 45 m), ppN₂ ${"%.2f".format(seg.ppN2End)} bar — helium (Trimix) required.",
                    idx
                )
            } else if (!critEnd && !highEnd && (seg.endM > 40.0 || seg.ppN2End > 4.20)) {
                highEnd = true
                list += PlanAlert(
                    AlertLevel.HIGH,
                    AlertCode.END_HIGH,
                    "High narcosis on ${seg.gas.displayName}: END ${seg.endM.roundToInt()} m (> 40 m), ppN₂ ${"%.2f".format(seg.ppN2End)} bar — impaired judgment & task-loading risk.",
                    idx
                )
            } else if (!critEnd && !highEnd && !medEnd && (seg.endM > dive.maxEndM + 1.0 || seg.ppN2End > dive.ppN2Max + 0.08)) {
                medEnd = true
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.END_HIGH,
                    "Elevated narcosis on ${seg.gas.displayName}: END ${seg.endM.roundToInt()} m (limit ${dive.maxEndM.roundToInt()} m), ppN₂ ${"%.2f".format(seg.ppN2End)} bar.",
                    idx
                )
            }

            // 7. Isobaric Counter-Diffusion (ICD) on gas switches
            val previous = prevGas
            if (!seg.isCcr && previous != null && previous.id != seg.gas.id && previous.he > 0.05) {
                val icd = GasCalculator.evaluateIcd(previous, seg.gas, seg.fromDepthM, env, alerts)
                if (icd.violatesDeltaN2 && !highIcdN2) {
                    highIcdN2 = true
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.ICD_N2,
                        "ICD ΔppN₂ jump of +${"%.2f".format(icd.deltaPpN2)} bar switching ${previous.displayName} → ${seg.gas.displayName} at ${seg.fromDepthM.roundToInt()} m (limit ${"%.2f".format(alerts.icdDeltaPpN2Max)} bar).",
                        idx
                    )
                }
                if (icd.violatesRuleOfFifths && !medIcdFifths) {
                    medIcdFifths = true
                    list += PlanAlert(
                        AlertLevel.MEDIUM,
                        AlertCode.ICD_FIFTHS,
                        "ICD Rule of Fifths advisory switching ${previous.displayName} → ${seg.gas.displayName} at ${seg.fromDepthM.roundToInt()} m.",
                        idx
                    )
                }
            }
            prevGas = seg.gas

            // 8. Lipid tissue advisory (LOW, optional setting)
            if (alerts.considerLipidResponse && seg.gas.he > 0.15 && !lowLipid) {
                lowLipid = true
                list += PlanAlert(
                    AlertLevel.LOW,
                    AlertCode.LIPID,
                    "Lipid-tissue helium response enabled: consider slower ascent & extended shallow stops on ${seg.gas.displayName}.",
                    idx
                )
            }
        }

        // 8b. Missing O2 / Air Breaks on Prolonged High-ppO2 Deco (HIGH)
        if (maxContinuousHighO2Min > 20.0 + 0.5) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.NO_O2_BREAKS,
                "Continuous high-ppO₂ decompression (${maxContinuousHighO2Min.roundToInt()} min > 20 min) without an air/backgas break — enable Gas Breaks in Settings to cut CNS seizure risk."
            )
        }

        // 9. CNS Oxygen Toxicity Clock (NOAA single-dive & cumulative model):
        //    - CRITICAL (Red): single-dive CNS > 100% or cumulative CNS > cnsErrorPct
        //    - HIGH (Orange): single-dive CNS > 80% NOAA limit, or cumulative CNS > cnsWarnPct
        //    - LOW (Gray): moderate CNS exposure (>= 50%)
        val singleDiveCnsHighThreshold = kotlin.math.min(80.0, alerts.cnsWarnPct)
        if (oxygen.diveCnsPct > 100.0 || oxygen.endCnsPct > alerts.cnsErrorPct) {
            list += PlanAlert(
                AlertLevel.CRITICAL,
                AlertCode.CNS,
                "CNS oxygen toxicity clock exceeds NOAA limit: single-dive ${oxygen.diveCnsPct.roundToInt()}%" +
                    if (oxygen.startCnsPct > 0.5) " (cumulative ${oxygen.endCnsPct.roundToInt()}%, limit ${alerts.cnsErrorPct.roundToInt()}%)."
                    else " (limit ${alerts.cnsErrorPct.roundToInt()}%)."
            )
        } else if (oxygen.diveCnsPct > singleDiveCnsHighThreshold) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.CNS,
                "High CNS oxygen toxicity clock: single-dive CNS reaches ${oxygen.diveCnsPct.roundToInt()}%, exceeding ${singleDiveCnsHighThreshold.roundToInt()}% of the NOAA single-dive limit" +
                    if (oxygen.startCnsPct > 0.5) " (cumulative end CNS ${oxygen.endCnsPct.roundToInt()}%)."
                    else "."
            )
        } else if (oxygen.endCnsPct > alerts.cnsWarnPct) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.CNS,
                "High cumulative CNS oxygen clock: end-of-dive CNS reaches ${oxygen.endCnsPct.roundToInt()}% (single-dive +${oxygen.diveCnsPct.roundToInt()}%, threshold ${alerts.cnsWarnPct.roundToInt()}%)."
            )
        } else if (oxygen.diveCnsPct >= 50.0 || oxygen.endCnsPct >= 50.0) {
            list += PlanAlert(
                AlertLevel.LOW,
                AlertCode.CNS,
                "Moderate CNS exposure (${oxygen.endCnsPct.roundToInt()}% NOAA clock) — monitor cumulative O₂ across repetitive dives."
            )
        }

        // 10. OTU Pulmonary Toxicity — HIGH (dive OTU > limit or daily > 850), MEDIUM (daily OTU > limit)
        if (oxygen.diveOtu > alerts.otuWarnPerDive || oxygen.dailyOtu > 850.0) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.OTU,
                "Pulmonary OTU limit exceeded: dive ${oxygen.diveOtu.roundToInt()} OTU, daily ${oxygen.dailyOtu.roundToInt()} OTU (limit ${alerts.otuWarnPerDive.roundToInt()} OTU)."
            )
        } else if (oxygen.dailyOtu > alerts.otuWarnPerDive) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.OTU,
                "Cumulative daily OTU (${oxygen.dailyOtu.roundToInt()} OTU) exceeds single-dive guideline (${alerts.otuWarnPerDive.roundToInt()} OTU)."
            )
        }

        // 11. Deco Gas Strategy & Redundancy (HIGH)
        if (!isCcr && hasDecoStops) {
            val enabledDecoGases = enabledGases.filter { it.role == GasRole.DECO }
            if (enabledDecoGases.isEmpty() && (maxDepthM > 40.0 || totalDecoMin >= 25.0)) {
                list += PlanAlert(
                    AlertLevel.HIGH,
                    AlertCode.NO_DECO_REDUNDANCY,
                    "Decompression dive (${maxDepthM.roundToInt()} m, ${totalDecoMin.roundToInt()} min deco) has no accelerated deco gas (e.g., EAN50 / O₂) — long backgas deco and zero gas redundancy."
                )
            } else if (maxDepthM >= 50.0 && totalDecoMin >= 25.0 && enabledDecoGases.isNotEmpty()) {
                val hasHighO2 = enabledDecoGases.any { it.o2Pct >= 80 }
                val hasMidDeco = enabledDecoGases.any { it.o2Pct in 35..79 }
                if (hasHighO2 && !hasMidDeco) {
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.NO_DECO_REDUNDANCY,
                        "Deep deco gap (${maxDepthM.roundToInt()} m): jumping directly from bottom mix to high-O₂ without a mid-range deco gas (e.g., EAN50 at 21 m) prolongs deep stops and increases ICD/lost-gas risk."
                    )
                }
            }
        }

        // 12. Repetitive Dive Checks: Reverse Profile & Short Surface Interval (HIGH / MEDIUM)
        if (input.previousTissues != null) {
            val prevDepth = input.previousMaxDepthM
            if (prevDepth != null && maxDepthM > prevDepth + 6.0) {
                val deltaM = (maxDepthM - prevDepth).roundToInt()
                if (hasDecoStops || maxDepthM > prevDepth + 12.0) {
                    list += PlanAlert(
                        AlertLevel.HIGH,
                        AlertCode.REVERSE_PROFILE,
                        "Reverse dive profile (+${deltaM} m deeper than prior ${prevDepth.roundToInt()} m dive): elevated repetitive DCS risk — plan deepest dive first."
                    )
                } else {
                    list += PlanAlert(
                        AlertLevel.MEDIUM,
                        AlertCode.REVERSE_PROFILE,
                        "Reverse profile advisory: dive is +${deltaM} m deeper than previous ${prevDepth.roundToInt()} m dive — extend surface interval or add conservatism."
                    )
                }
            }

            if (input.surfaceIntervalMin < 30.0 || oxygen.startCnsPct >= 60.0) {
                list += PlanAlert(
                    AlertLevel.HIGH,
                    AlertCode.SHORT_SURFACE_INTERVAL,
                    "Short surface interval (${input.surfaceIntervalMin.roundToInt()} min, start CNS ${oxygen.startCnsPct.roundToInt()}%): high residual inert gas & CNS carry-over."
                )
            } else if (input.surfaceIntervalMin < 60.0) {
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.SHORT_SURFACE_INTERVAL,
                    "Surface interval under 60 min (${input.surfaceIntervalMin.roundToInt()} min): significant residual fast/medium tissue N₂ loading remains."
                )
            }
        }

        // 13. Multi-Level Sawtooth / Yo-Yo Profile Check (MEDIUM)
        val activeLevels = input.levels.filter { it.depthM > 0.0 && it.timeMin > 0.0 }
        if (activeLevels.size >= 3) {
            var ascendedBy6m = false
            var hasYoYo = false
            for (i in 1 until activeLevels.size) {
                val delta = activeLevels[i].depthM - activeLevels[i - 1].depthM
                if (delta <= -6.0) {
                    ascendedBy6m = true
                } else if (ascendedBy6m && delta >= 6.0) {
                    hasYoYo = true
                    break
                }
            }
            if (hasYoYo) {
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.YOYO_PROFILE,
                    "Sawtooth / yo-yo multi-level profile detected (ascending ≥ 6 m then re-descending ≥ 6 m): increases microbubble expansion/recompression DCS risk."
                )
            }
        }

        // 14. Extended TTS / Thermal & Cold Water Exposure (HIGH / MEDIUM)
        var coldEscalated = false
        if (ttsMin >= 90.0) {
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.LONG_TTS_EXPOSURE,
                "Extended Total Time to Surface (TTS ${ttsMin.roundToInt()} min ≥ 90 min): high hypothermia, dehydration, and immersion pulmonary edema risk."
            )
        } else if (general.surfaceTempC <= 14 && ttsMin >= 45.0) {
            coldEscalated = true
            list += PlanAlert(
                AlertLevel.HIGH,
                AlertCode.LONG_TTS_EXPOSURE,
                "Cold-water prolonged deco (TTS ${ttsMin.roundToInt()} min at ${general.surfaceTempC}°C): peripheral vasoconstriction slows off-gassing — add thermal & GF conservatism."
            )
        } else if (ttsMin >= 45.0 || totalRuntimeMin >= 90.0) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.LONG_TTS_EXPOSURE,
                "Long dive exposure (TTS ${ttsMin.roundToInt()} min, runtime ${totalRuntimeMin.roundToInt()} min): plan thermal protection and hydration."
            )
        }

        if (!coldEscalated && general.surfaceTempC <= 10) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.COLD_WATER,
                "Cold water environment (${general.surfaceTempC}°C ≤ 10°C): regulator free-flow risk and reduced peripheral off-gassing — use environmentally sealed cold-water regs."
            )
        }

        // 15. Altitude Diving Check (HIGH unacclimatized < 12h, MEDIUM acclimatized >= 300 m)
        if (general.elevationM >= 300) {
            if (general.arrivedUnder12hAtAltitude) {
                list += PlanAlert(
                    AlertLevel.HIGH,
                    AlertCode.ALTITUDE_DIVING,
                    "Unacclimatized altitude dive (${general.elevationM} m, arrived < 12 h): tissues carry residual sea-level N₂ under reduced surface pressure (${"%.2f".format(env.pSurfaceBar)} bar)."
                )
            } else {
                list += PlanAlert(
                    AlertLevel.MEDIUM,
                    AlertCode.ALTITUDE_DIVING,
                    "Altitude diving (${general.elevationM} m, surface pressure ${"%.2f".format(env.pSurfaceBar)} bar): apply slower ascent rates and extended flying/driving-after-diving surface intervals."
                )
            }
        }

        // 16. Tight NDL Margin on No-Stop Dives (MEDIUM)
        if (!hasDecoStops && ndlMin.isFinite() && ndlMin > 0.0 && activeLevels.size == 1) {
            val plannedBottomTime = activeLevels.first().timeMin
            val remainingNdl = ndlMin - plannedBottomTime
            if (plannedBottomTime >= ndlMin * 0.85 || remainingNdl <= 3.0) {
                val hasDecoGas = enabledGases.any { it.role == GasRole.DECO }
                if (!hasDecoGas) {
                    list += PlanAlert(
                        AlertLevel.MEDIUM,
                        AlertCode.TIGHT_NDL_MARGIN,
                        "Tight NDL margin: planned bottom time (${plannedBottomTime.roundToInt()} min) is within ${remainingNdl.coerceAtLeast(0.0).roundToInt()} min of the ${ndlMin.roundToInt()} min NDL without deco gas."
                    )
                }
            }
        }

        // 17. Final Stop at 6 m on Heavy Deco Dive (MEDIUM)
        if (hasDecoStops && dive.lastStopM >= 6 && totalDecoMin >= 25.0 && endSurfaceGfPct >= 80.0) {
            list += PlanAlert(
                AlertLevel.MEDIUM,
                AlertCode.LAST_STOP_6M_GAP,
                "Final deco stop set to ${dive.lastStopM} m on heavy deco (${totalDecoMin.roundToInt()} min, Surf GF ${endSurfaceGfPct.roundToInt()}%): surfacing directly from 6 m creates a steep final pressure drop."
            )
        }

        // 18. Gas Volume, Low Surfacing Pressure & Configured Gas Budget Alerts (CRITICAL / MEDIUM / LOW)
        for (gc in consumption) {
            if (!gc.isSufficient) {
                list += PlanAlert(
                    AlertLevel.CRITICAL,
                    AlertCode.GAS_VOLUME,
                    "Insufficient ${gc.gas.displayName}: requires ${gc.totalRequiredLitres.roundToInt()} L (incl. reserve), cylinder holds ${gc.availableLitres.roundToInt()} L."
                )
            } else {
                val barMarginAboveReserve = gc.remainingBar - gc.minPressureBar
                if (gc.remainingBar < 50.0 || barMarginAboveReserve < 10.0) {
                    list += PlanAlert(
                        AlertLevel.MEDIUM,
                        AlertCode.END_PRESSURE_LOW,
                        "Low surfacing pressure on ${gc.gas.displayName}: ends at ${gc.remainingBar.roundToInt()} bar (min reserve ${gc.minPressureBar.roundToInt()} bar) — narrow contingency margin."
                    )
                } else if (barMarginAboveReserve < 15.0) {
                    list += PlanAlert(
                        AlertLevel.LOW,
                        AlertCode.GAS_ALERT_LIMITS,
                        "Tight gas margin on ${gc.gas.displayName}: ends ${gc.remainingBar.roundToInt()} bar vs ${gc.minPressureBar.roundToInt()} bar min reserve."
                    )
                }
                // Check configured gas budget limits (LOW)
                val budgetLimit = when {
                    gc.gas.role == GasRole.DECO && gc.gas.o2Pct >= 99 -> alerts.decoO2MaxLitres
                    gc.gas.role == GasRole.DECO && gc.gas.o2Pct >= 50 -> alerts.decoMix50To99MaxLitres
                    gc.gas.role == GasRole.DECO -> alerts.decoMixUpTo49MaxLitres
                    else -> alerts.bottomMixMaxLitres
                }
                if (gc.usedLitres > budgetLimit) {
                    list += PlanAlert(
                        AlertLevel.LOW,
                        AlertCode.GAS_ALERT_LIMITS,
                        "${gc.gas.displayName} consumption (${gc.usedLitres.roundToInt()} L) exceeds alert budget (${budgetLimit.roundToInt()} L)."
                    )
                }
            }
        }

        return list.sortedBy { it.level.priority }
    }
}
