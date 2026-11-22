package com.example.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Main technical decompression dive planner (Section 8, Section 11, Multi-Dive Day engine).
 * Pure Kotlin, deterministic, zero UI dependencies.
 */
object DivePlannerEngine {

    fun planDive(
        input: DivePlanInput,
        includeContingencies: Boolean = false,
        recordTimeline: Boolean = true
    ): DivePlanResult {
        val env = UnitsAndPressure.buildEnvironment(input.general)
        val coeffs = Coefficients.tableFor(input.general.decoModel, input.general.useCompartment1b)
        val enabledGases = input.gases.filter { it.enabled }

        // Resolve initial tissues (accounting for repetitive dive surface interval if provided)
        val baseTissues = input.previousTissues
            ?: TissuesAndBuhlmann.initTissues(env, input.general.arrivedUnder12hAtAltitude)
        val startTissues = if (input.surfaceIntervalMin > 0.0) {
            TissuesAndBuhlmann.surfaceOffgas(baseTissues, input.surfaceIntervalMin, env, coeffs)
        } else {
            baseTissues
        }

        val levels = input.levels.filter { it.depthM > 0.0 && it.timeMin > 0.0 }
        if (levels.isEmpty() || enabledGases.isEmpty()) {
            return abortedPlan(input, env, startTissues, "Define at least one dive level and one enabled breathing gas.")
        }

        return executePlanCleanly(
            input = input,
            env = env,
            coeffs = coeffs,
            startTissues = startTissues,
            levels = levels,
            enabledGases = enabledGases,
            includeContingencies = includeContingencies,
            recordTimeline = recordTimeline
        )
    }

    private fun executePlanCleanly(
        input: DivePlanInput,
        env: Environment,
        coeffs: List<CompartmentCoeff>,
        startTissues: TissueState,
        levels: List<DiveLevel>,
        enabledGases: List<Gas>,
        includeContingencies: Boolean,
        recordTimeline: Boolean
    ): DivePlanResult {
        val isCcr = input.general.planningType == PlanningType.CCR
        val dive = input.dive
        val gfLow = input.general.gfLow / 100.0
        val gfHigh = input.general.gfHigh / 100.0
        val stopInc = dive.stopIncrementM.coerceIn(1, 3)
        val lastStopM = dive.lastStopM.coerceIn(3, 6)
        val descentRate = dive.descentRateMMin.coerceAtLeast(1.0)
        val ascentRate = dive.ascentRateToFirstStopMMin.coerceAtLeast(1.0)
        val decoAscentRate = dive.ascentDecoRateMMin.coerceAtLeast(1.0)
        val surfaceRate = dive.ascentRateToSurfaceMMin.coerceAtLeast(1.0)

        val primaryBottomGas = enabledGases.firstOrNull {
            if (isCcr) it.role == GasRole.DILUENT || it.role == GasRole.BOTTOM else it.role == GasRole.BOTTOM
        } ?: enabledGases.first()
        val travelGas = enabledGases.firstOrNull { it.role == GasRole.TRAVEL }

        val segments = mutableListOf<Segment>()
        val stops = mutableListOf<StopSummary>()
        val timeline = mutableListOf<TissueSnapshot>()

        var tissues = startTissues
        var runtime = 0.0
        var currentDepth = 0.0
        var currentGas = primaryBottomGas
        var totalDecoMin = 0.0
        var firstStopRecorded = 0

        fun activeSetpoint(depthM: Double, isDescent: Boolean, isDeco: Boolean): Double? {
            if (!isCcr) return null
            return when {
                isDescent && depthM < input.ccr.switchToHighDepthM -> input.ccr.setpointDescent
                isDeco && depthM <= input.ccr.switchToDecoDepthM -> input.ccr.setpointDeco
                else -> input.ccr.setpointBottom
            }
        }

        fun recordSnapshot(depthM: Double, gas: Gas, sp: Double?) {
            if (!recordTimeline) return
            val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
            val insp = if (isCcr && sp != null) {
                UnitsAndPressure.inspiredPressuresCCR(pAmb, gas, sp)
            } else {
                UnitsAndPressure.inspiredPressuresOC(pAmb, gas)
            }
            val ceilM = TissuesAndBuhlmann.ceilingDepthM(
                tissues, gfLow, coeffs, env, input.general.decoModel, input.general.vpmConservatism
            )
            val (gf99, leader) = TissuesAndBuhlmann.gf99WithLeader(tissues, pAmb, coeffs)
            val surfGf = TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs)
            val satPct = TissuesAndBuhlmann.compartmentSurfaceLoadPct(tissues, env, coeffs)
            timeline += TissueSnapshot(
                runtimeMin = runtime,
                depthM = depthM,
                pAmbBar = pAmb,
                ceilingM = ceilM,
                gas = gas,
                ppO2 = insp.ppO2,
                ppN2 = if (isCcr && sp != null) insp.pInspiredN2 else pAmb * gas.n2,
                ppHe = if (isCcr && sp != null) insp.pInspiredHe else pAmb * gas.he,
                tissues = tissues,
                gf99Pct = gf99,
                surfaceGfPct = surfGf,
                leadingCompartmentIndex = leader,
                compartmentSaturationPct = satPct
            )
        }

        fun appendSegment(
            type: SegmentType,
            fromD: Double,
            toD: Double,
            durationMin: Double,
            gas: Gas,
            sp: Double?
        ) {
            if (durationMin <= 1e-6) return
            if (!recordTimeline) {
                tissues = TissuesAndBuhlmann.advanceTissues(tissues, fromD, toD, durationMin, gas, env, coeffs, isCcr, sp)
                runtime += durationMin
            } else {
                val sampleStep = if (durationMin > 15.0) 0.5 else 0.25
                val steps = min(40, max(1, ceil(durationMin / sampleStep).toInt()))
                val dt = durationMin / steps
                for (i in 1..steps) {
                    val d0 = fromD + (toD - fromD) * ((i - 1).toDouble() / steps)
                    val d1 = fromD + (toD - fromD) * (i.toDouble() / steps)
                    tissues = TissuesAndBuhlmann.advanceTissues(tissues, d0, d1, dt, gas, env, coeffs, isCcr, sp)
                    runtime += dt
                    recordSnapshot(d1, gas, sp)
                }
            }
            val pStart = UnitsAndPressure.ambientPressure(fromD, env)
            val pEnd = UnitsAndPressure.ambientPressure(toD, env)
            val inspStart = if (isCcr && sp != null) UnitsAndPressure.inspiredPressuresCCR(pStart, gas, sp)
            else UnitsAndPressure.inspiredPressuresOC(pStart, gas)
            val inspEnd = if (isCcr && sp != null) UnitsAndPressure.inspiredPressuresCCR(pEnd, gas, sp)
            else UnitsAndPressure.inspiredPressuresOC(pEnd, gas)

            val ceilM = TissuesAndBuhlmann.ceilingDepthM(
                tissues, gfLow, coeffs, env, input.general.decoModel, input.general.vpmConservatism
            )
            val gf99 = TissuesAndBuhlmann.gf99Pct(tissues, pEnd, coeffs)
            val surfGf = TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs)

            segments += Segment(
                type = type,
                fromDepthM = fromD,
                toDepthM = toD,
                durationMin = durationMin,
                runtimeMin = runtime,
                gas = gas,
                ppO2Start = inspStart.ppO2,
                ppO2End = inspEnd.ppO2,
                ppN2End = if (isCcr && sp != null) inspEnd.pInspiredN2 else pEnd * gas.n2,
                ppHeEnd = if (isCcr && sp != null) inspEnd.pInspiredHe else pEnd * gas.he,
                endM = GasCalculator.endM(gas, toD, env, dive.o2Narcotic),
                densityGL = GasCalculator.densityGL(gas, toD, env),
                ceilingM = ceilM,
                gf99Pct = gf99,
                surfaceGfPct = surfGf,
                isCcr = isCcr,
                setpoint = sp
            )
            currentDepth = toD
            currentGas = gas
        }

        // ---- 1. Descent (actively uses Travel Gas when present & safe, then switches to Bottom MOD Mix) ----
        val firstLevel = levels.first()
        val firstLevelGas = enabledGases.firstOrNull { it.id == firstLevel.gasId } ?: primaryBottomGas
        val bottomMin = GasCalculator.minDepthM(firstLevelGas, dive.ppO2Min, env)
        val shouldUseTravel = !isCcr &&
            travelGas != null &&
            travelGas.id != firstLevelGas.id &&
            GasCalculator.minDepthM(travelGas, dive.ppO2Min, env) <= 0.0 &&
            (bottomMin > 0.0 || (travelGas.o2 > firstLevelGas.o2 + 0.01 && firstLevel.depthM > 24.0))

        val startGas = if (shouldUseTravel && travelGas != null) travelGas else firstLevelGas
        recordSnapshot(0.0, startGas, activeSetpoint(0.0, isDescent = true, isDeco = false))

        if (shouldUseTravel && travelGas != null) {
            val travelMod = GasCalculator.modDisplayM(travelGas, dive.ppO2Bottom, env)
            val rawSwitch = min(firstLevel.depthM - stopInc, travelMod)
            val snappedSwitch = (kotlin.math.floor(rawSwitch / stopInc) * stopInc)
                .coerceAtLeast(max(bottomMin, 9.0))
                .coerceAtMost(min(travelMod, firstLevel.depthM - 3.0))
            if (snappedSwitch >= 9.0 && snappedSwitch < firstLevel.depthM - 1e-6) {
                appendSegment(SegmentType.DESCENT, 0.0, snappedSwitch, snappedSwitch / descentRate, travelGas, null)
                if (dive.mixChangeExtraTimeMin > 0.0) {
                    appendSegment(SegmentType.SWITCH, snappedSwitch, snappedSwitch, dive.mixChangeExtraTimeMin, firstLevelGas, null)
                }
                appendSegment(
                    SegmentType.DESCENT, snappedSwitch, firstLevel.depthM,
                    (firstLevel.depthM - snappedSwitch) / descentRate, firstLevelGas, null
                )
            } else {
                appendSegment(SegmentType.DESCENT, 0.0, firstLevel.depthM, firstLevel.depthM / descentRate, firstLevelGas, null)
            }
        } else if (isCcr && firstLevel.depthM > input.ccr.switchToHighDepthM + 1e-6 && input.ccr.switchToHighDepthM > 0.0) {
            // CCR: low (descent) setpoint until the switch depth, then the bottom setpoint below it
            val swD = input.ccr.switchToHighDepthM
            appendSegment(SegmentType.DESCENT, 0.0, swD, swD / descentRate, firstLevelGas, input.ccr.setpointDescent)
            appendSegment(
                SegmentType.DESCENT, swD, firstLevel.depthM,
                (firstLevel.depthM - swD) / descentRate, firstLevelGas, input.ccr.setpointBottom
            )
        } else {
            val spDesc = activeSetpoint(firstLevel.depthM / 2.0, isDescent = true, isDeco = false)
            appendSegment(SegmentType.DESCENT, 0.0, firstLevel.depthM, firstLevel.depthM / descentRate, firstLevelGas, spDesc)
        }

        // ---- 2. Bottom / Multi-Level ----
        val firstLevelDur = if (input.general.btIncludesDescent) {
            (firstLevel.timeMin - runtime).coerceAtLeast(0.0)
        } else {
            firstLevel.timeMin
        }
        val spBottom1 = activeSetpoint(firstLevel.depthM, isDescent = false, isDeco = false)
        appendSegment(SegmentType.LEVEL, firstLevel.depthM, firstLevel.depthM, firstLevelDur, firstLevelGas, spBottom1)

        for (i in 1 until levels.size) {
            val lvl = levels[i]
            val lvlGas = enabledGases.firstOrNull { it.id == lvl.gasId }
                ?: if (!isCcr) GasCalculator.bestWorkingGasAt(lvl.depthM, primaryBottomGas, enabledGases, env, dive) else primaryBottomGas
            val spLvl = activeSetpoint(lvl.depthM, isDescent = lvl.depthM > currentDepth, isDeco = false)
            if (abs(lvl.depthM - currentDepth) > 0.1) {
                val deeper = lvl.depthM > currentDepth
                val rate = if (deeper) descentRate else ascentRate
                appendSegment(
                    if (deeper) SegmentType.DESCENT else SegmentType.ASCENT,
                    currentDepth, lvl.depthM, abs(lvl.depthM - currentDepth) / rate, lvlGas, spLvl
                )
            }
            appendSegment(SegmentType.LEVEL, lvl.depthM, lvl.depthM, lvl.timeMin, lvlGas, spLvl)
        }

        val endBottomRuntime = runtime
        val maxDepthM = levels.maxOf { it.depthM }

        val ndlMin = TissuesAndBuhlmann.computeNdlMin(
            depthM = firstLevel.depthM,
            gas = firstLevelGas,
            general = input.general,
            env = env,
            initialTissues = startTissues,
            isCcr = isCcr,
            setpoint = spBottom1
        )

        // ---- 3. Ascent & Decompression Schedule (Section 8.4) ----
        // Standard Bühlmann GF NDL rule: a dive is within NDL if a direct ascent to surface keeps
        // ceilingDepthM(tissues, gfHigh) <= 0m; GF Low only anchors firstStop once NDL is exceeded.
        val isWithinNdl = run {
            var sim = tissues
            var d = currentDepth
            if (TissuesAndBuhlmann.ceilingDepthM(
                    sim, gfHigh, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                ) > 0.0
            ) {
                return@run false
            }
            if (d > lastStopM) {
                val dt1 = (d - lastStopM) / ascentRate
                val sp1 = activeSetpoint((d + lastStopM) / 2.0, isDescent = false, isDeco = false)
                sim = TissuesAndBuhlmann.advanceTissues(sim, d, lastStopM.toDouble(), dt1, currentGas, env, coeffs, isCcr, sp1)
                d = lastStopM.toDouble()
                if (TissuesAndBuhlmann.ceilingDepthM(
                        sim, gfHigh, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                    ) > 0.0
                ) {
                    return@run false
                }
            }
            if (d > 0.0) {
                val dt2 = d / surfaceRate
                val sp2 = activeSetpoint(d / 2.0, isDescent = false, isDeco = true)
                sim = TissuesAndBuhlmann.advanceTissues(sim, d, 0.0, dt2, currentGas, env, coeffs, isCcr, sp2)
                if (TissuesAndBuhlmann.ceilingDepthM(
                        sim, gfHigh, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                    ) > 0.0
                ) {
                    return@run false
                }
            }
            true
        }

        // Determine initial first stop depth with GF Low only when NDL is exceeded
        var firstStop = if (isWithinNdl) {
            0
        } else {
            val rawFirst = UnitsAndPressure.ceilToIncrement(
                TissuesAndBuhlmann.ceilingDepthM(
                    tissues, gfLow, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                ),
                stopInc
            )
            if (rawFirst in 1 until lastStopM) lastStopM else rawFirst
        }

        // Check if NDL dive (try ascending to surface, checking ceiling along the way)
        var depth = currentDepth
        var highO2TimeMin = 0.0

        // Helper: find deepest stop multiple where a richer gas switch happens above currentDepth and above target
        fun ascendWithSwitches(targetDepth: Double, rate: Double, isDecoPhase: Boolean) {
            var d = depth
            var ascGuard = 0
            while (d > targetDepth + 1e-6 && ascGuard++ < 300) {
                // Step down to next stopInc multiple or targetDepth
                val nextMultiple = ((ceil(d / stopInc) - 1) * stopInc).coerceAtLeast(targetDepth)
                val stepTarget = if (abs(d - nextMultiple) < 1e-6) {
                    (d - stopInc).coerceAtLeast(targetDepth)
                } else {
                    nextMultiple
                }

                // Check ceiling at stepTarget with gfLow before ascending if we haven't locked firstStop
                val spAsc = activeSetpoint((d + stepTarget) / 2.0, isDescent = false, isDeco = isDecoPhase)
                val stepTime = (d - stepTarget) / rate
                val preview = TissuesAndBuhlmann.advanceTissues(tissues, d, stepTarget, stepTime, currentGas, env, coeffs, isCcr, spAsc)
                val ceilGfLow = TissuesAndBuhlmann.ceilingDepthM(
                    preview, gfLow, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                )
                if (!isDecoPhase && ceilGfLow > stepTarget + 1e-6) {
                    val forcedStop = UnitsAndPressure.ceilToIncrement(ceilGfLow, stopInc).coerceAtLeast(lastStopM)
                    if (forcedStop < d) {
                        val dtForced = (d - forcedStop) / rate
                        appendSegment(SegmentType.ASCENT, d, forcedStop.toDouble(), dtForced, currentGas, spAsc)
                        d = forcedStop.toDouble()
                    }
                    firstStop = max(firstStop, forcedStop)
                    depth = d
                    return
                }

                appendSegment(SegmentType.ASCENT, d, stepTarget, stepTime, currentGas, spAsc)
                d = stepTarget
                depth = d

                // Check gas switch at this stop multiple during ascent
                if (!isCcr && d > targetDepth + 1e-6 && d > 0.0) {
                    val betterGas = GasCalculator.bestGasAt(d, currentGas, enabledGases, env, dive)
                    if (betterGas.id != currentGas.id) {
                        currentGas = betterGas
                        if (dive.mixChangeExtraTimeMin > 0.0) {
                            appendSegment(SegmentType.SWITCH, d, d, dive.mixChangeExtraTimeMin, currentGas, null)
                            stops += StopSummary(d.roundToInt(), dive.mixChangeExtraTimeMin, runtime, currentGas, isSwitch = true)
                            totalDecoMin += dive.mixChangeExtraTimeMin
                        } else if (dive.stopOnGasSwitch) {
                            appendSegment(SegmentType.STOP, d, d, dive.minStopTimeMin, currentGas, null)
                            stops += StopSummary(d.roundToInt(), dive.minStopTimeMin, runtime, currentGas, isSwitch = true)
                            totalDecoMin += dive.minStopTimeMin
                        }
                    }
                }
            }
        }

        if (isWithinNdl) {
            // Pure NDL dive -> direct ascent to lastStopM at ascentRate, then to surface at surfaceRate
            if (depth > lastStopM) {
                val spAsc = activeSetpoint((depth + lastStopM) / 2.0, isDescent = false, isDeco = false)
                appendSegment(SegmentType.ASCENT, depth, lastStopM.toDouble(), (depth - lastStopM) / ascentRate, currentGas, spAsc)
                depth = lastStopM.toDouble()
            }
            if (depth > 0.0) {
                val spSurf = activeSetpoint(depth / 2.0, isDescent = false, isDeco = true)
                appendSegment(SegmentType.ASCENT, depth, 0.0, depth / surfaceRate, currentGas, spSurf)
                depth = 0.0
            }
        } else if (firstStop <= 0) {
            // NDL exceeded without deep GF Low ceiling -> start deco at lastStopM
            if (depth > lastStopM) {
                ascendWithSwitches(lastStopM.toDouble(), ascentRate, isDecoPhase = false)
            }
            if (firstStop <= 0) {
                firstStop = lastStopM
            }
        }

        // Deco dive: ascend to firstStop, then run stop loop
        if (depth > 0.0 && firstStop > 0) {
            if (depth > firstStop) {
                ascendWithSwitches(firstStop.toDouble(), ascentRate, isDecoPhase = false)
            }
            // Lock firstStop at current depth (the deepest stop where deco begins)
            val lockedFirstStop = max(firstStop, UnitsAndPressure.ceilToIncrement(depth, stopInc)).coerceAtLeast(lastStopM)
            firstStopRecorded = lockedFirstStop
            var stopDepth = lockedFirstStop

            var stopGuard = 0
            while (stopDepth > 0 && stopGuard++ < 300) {
                val nextStop = if (stopDepth > lastStopM) stopDepth - stopInc else 0
                val spStop = activeSetpoint(stopDepth.toDouble(), isDescent = false, isDeco = true)

                // Gas switch check at stopDepth
                var switchedHere = false
                if (!isCcr) {
                    val betterGas = GasCalculator.bestGasAt(stopDepth.toDouble(), currentGas, enabledGases, env, dive)
                    if (betterGas.id != currentGas.id) {
                        switchedHere = true
                        currentGas = betterGas
                        if (dive.mixChangeExtraTimeMin > 0.0) {
                            appendSegment(
                                SegmentType.SWITCH,
                                stopDepth.toDouble(),
                                stopDepth.toDouble(),
                                dive.mixChangeExtraTimeMin,
                                currentGas,
                                null
                            )
                            totalDecoMin += dive.mixChangeExtraTimeMin
                        }
                    }
                }

                val gfNext = TissuesAndBuhlmann.gfAtDepth(
                    nextStop.toDouble(),
                    lockedFirstStop.toDouble(),
                    gfLow,
                    gfHigh
                )

                // Fast 2-stage stop solver: 1-minute coarse stepping + 1-second fine refinement on the final minute
                val maxRemMin = (Constants.MAX_DECO_MINUTES - totalDecoMin.toInt()).coerceAtLeast(1)
                var prevTissues = tissues
                var simTissues = tissues
                var coarseMin = 0
                while (TissuesAndBuhlmann.ceilingDepthM(
                        simTissues, gfNext, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                    ) > nextStop + 1e-6
                ) {
                    prevTissues = simTissues
                    simTissues = TissuesAndBuhlmann.advanceTissues(
                        simTissues,
                        stopDepth.toDouble(),
                        stopDepth.toDouble(),
                        1.0,
                        currentGas,
                        env,
                        coeffs,
                        isCcr,
                        spStop
                    )
                    coarseMin++
                    if (coarseMin >= maxRemMin) {
                        return abortedPlan(input, env, startTissues, "Deco obligation too large (> ${Constants.MAX_DECO_MINUTES} min).")
                    }
                }

                var neededSeconds = 0
                if (coarseMin > 0) {
                    neededSeconds = (coarseMin - 1) * 60
                    var fineTissues = prevTissues
                    var extraSec = 0
                    while (extraSec < 60 && TissuesAndBuhlmann.ceilingDepthM(
                            fineTissues, gfNext, coeffs, env, input.general.decoModel, input.general.vpmConservatism
                        ) > nextStop + 1e-6
                    ) {
                        fineTissues = TissuesAndBuhlmann.advanceTissues(
                            fineTissues,
                            stopDepth.toDouble(),
                            stopDepth.toDouble(),
                            1.0 / 60.0,
                            currentGas,
                            env,
                            coeffs,
                            isCcr,
                            spStop
                        )
                        extraSec++
                    }
                    neededSeconds += extraSec
                }

                // Round UP to minStopTimeMin (1 min or 0.5 min)
                val minStepSec = (dive.minStopTimeMin * 60.0).roundToInt().coerceAtLeast(30)
                val roundedStopMin = if (neededSeconds > 0) {
                    (ceil(neededSeconds.toDouble() / minStepSec) * minStepSec) / 60.0
                } else if (switchedHere && dive.stopOnGasSwitch && dive.mixChangeExtraTimeMin <= 0.0) {
                    dive.minStopTimeMin
                } else {
                    0.0
                }

                if (roundedStopMin > 0.0) {
                    // Apply Air Breaks if enabled on high-O2 deco gas (Section 3.4)
                    val highO2 = (!isCcr && currentGas.o2 >= 0.80) || (isCcr && (spStop ?: 0.0) >= 1.35)
                    if (input.gasBreaks.enabled && highO2 && stopDepth <= input.gasBreaks.applyDepthMaxM + 0.1) {
                        var remainingStop = roundedStopMin
                        val breakMix = primaryBottomGas
                        while (remainingStop > 1e-6) {
                            val untilBreak = (input.gasBreaks.breakAfterMin - highO2TimeMin).coerceAtLeast(0.5)
                            val chunk = min(remainingStop, untilBreak)
                            appendSegment(SegmentType.STOP, stopDepth.toDouble(), stopDepth.toDouble(), chunk, currentGas, spStop)
                            totalDecoMin += chunk
                            highO2TimeMin += chunk
                            remainingStop -= chunk

                            if (highO2TimeMin >= input.gasBreaks.breakAfterMin - 1e-4 && remainingStop > 1e-4) {
                                val breakDur = input.gasBreaks.breakTimeMin.coerceAtLeast(1.0)
                                appendSegment(SegmentType.BREAK, stopDepth.toDouble(), stopDepth.toDouble(), breakDur, breakMix, if (isCcr) 0.7 else null)
                                totalDecoMin += breakDur
                                highO2TimeMin = 0.0
                            }
                        }
                    } else {
                        appendSegment(SegmentType.STOP, stopDepth.toDouble(), stopDepth.toDouble(), roundedStopMin, currentGas, spStop)
                        totalDecoMin += roundedStopMin
                        if (highO2) highO2TimeMin += roundedStopMin else highO2TimeMin = 0.0
                    }

                    stops += StopSummary(
                        depthM = stopDepth,
                        durationMin = roundedStopMin + (if (switchedHere) dive.mixChangeExtraTimeMin else 0.0),
                        runtimeMin = runtime,
                        gas = currentGas,
                        isSwitch = switchedHere
                    )
                } else if (switchedHere && dive.mixChangeExtraTimeMin > 0.0) {
                    stops += StopSummary(
                        depthM = stopDepth,
                        durationMin = dive.mixChangeExtraTimeMin,
                        runtimeMin = runtime,
                        gas = currentGas,
                        isSwitch = true
                    )
                }

                // Ascend to nextStop
                val rate = if (nextStop == 0) surfaceRate else decoAscentRate
                val transitTime = (stopDepth - nextStop) / rate
                val spTransit = activeSetpoint((stopDepth + nextStop) / 2.0, isDescent = false, isDeco = true)
                appendSegment(SegmentType.ASCENT, stopDepth.toDouble(), nextStop.toDouble(), transitTime, currentGas, spTransit)
                stopDepth = nextStop
                depth = nextStop.toDouble()
            }
        }

        val oxygen = OxygenAndConsumption.computeOxygenSummary(
            segments = segments,
            previousCnsPct = input.previousCnsPct,
            previousDailyOtu = input.previousDailyOtu,
            surfaceIntervalMin = input.surfaceIntervalMin
        )

        // Lost-gas reserve: replan once per deco gas with that gas lost, and record the most
        // each remaining gas is used in any of those scenarios.
        val lostGasMaxUsage: Map<String, Double> =
            if (!isCcr && input.consumption.decoReserveRule == ReserveRuleDeco.LOST_GAS) {
                lostGasWorstCaseUsage(input)
            } else {
                emptyMap()
            }

        val rawConsumption = OxygenAndConsumption.computeGasConsumption(segments, input, env, lostGasMaxUsage)

        // In Best GAS Mix mode, automatically scale tank count (1x, 2x, 3x... of 12L @ 200bar or user's chosen tank size)
        // for every gas according to the diver's Settings SAC/RMV & reserve rule so gas is always sufficient.
        var finalInput = input
        var finalSegments: List<Segment> = segments
        var finalStops: List<StopSummary> = stops
        var finalTimeline: List<TissueSnapshot> = timeline
        var gasConsumption = rawConsumption

        if (input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX) {
            val requiredByGasId = rawConsumption.associateBy { it.gas.id }
            val updatedGasById = linkedMapOf<String, Gas>()
            val resizedGases = input.gases.map { g ->
                val gc = requiredByGasId[g.id]
                val singleTankLitres = (g.cylinder.waterVolumeL * g.cylinder.fillPressureBar).coerceAtLeast(1.0)
                val neededCount = if (gc != null && gc.totalRequiredLitres > 0.0) {
                    ceil((gc.totalRequiredLitres + 1e-3) / singleTankLitres).toInt().coerceIn(1, 20)
                } else {
                    1
                }
                val resized = g.copy(cylinder = g.cylinder.copy(count = neededCount))
                updatedGasById[resized.id] = resized
                resized
            }
            finalInput = input.copy(gases = resizedGases)
            finalSegments = segments.map { seg ->
                updatedGasById[seg.gas.id]?.let { seg.copy(gas = it) } ?: seg
            }
            finalStops = stops.map { st ->
                updatedGasById[st.gas.id]?.let { st.copy(gas = it) } ?: st
            }
            finalTimeline = if (recordTimeline) {
                timeline.map { snap ->
                    updatedGasById[snap.gas.id]?.let { snap.copy(gas = it) } ?: snap
                }
            } else {
                timeline
            }
            gasConsumption = OxygenAndConsumption.computeGasConsumption(finalSegments, finalInput, env, lostGasMaxUsage)
        }

        val endSurfGf = TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs)
        val ttsMin = (runtime - endBottomRuntime).coerceAtLeast(0.0)
        val alerts = OxygenAndConsumption.runSafetyChecks(
            input = finalInput,
            env = env,
            segments = finalSegments,
            oxygen = oxygen,
            consumption = gasConsumption,
            endSurfaceGfPct = endSurfGf,
            ttsMin = ttsMin,
            totalDecoMin = totalDecoMin,
            ndlMin = ndlMin,
            totalRuntimeMin = runtime,
            maxDepthM = maxDepthM
        )

        val contingencies = if (includeContingencies) generateContingencies(finalInput) else emptyList()
        val ccrBailout = if (includeContingencies && isCcr) generateCcrBailout(finalInput) else null

        return DivePlanResult(
            input = finalInput,
            env = env,
            segments = finalSegments,
            stops = finalStops,
            maxDepthM = maxDepthM,
            totalRuntimeMin = runtime,
            totalDecoMin = totalDecoMin,
            ttsMin = ttsMin,
            firstStopM = firstStopRecorded,
            ndlMin = ndlMin,
            startTissues = startTissues,
            endTissues = tissues,
            endSurfaceGfPct = endSurfGf,
            tissueTimeline = finalTimeline,
            oxygen = oxygen,
            gasConsumption = gasConsumption,
            alerts = alerts,
            contingencies = contingencies,
            ccrBailout = ccrBailout
        )
    }

    /**
     * Plans a multi-dive single-day sequence (Dive 1 -> SI -> Dive 2 -> SI -> Dive 3 ...),
     * automatically propagating 16-compartment N2/He tissue tensions, CNS 90-min decay,
     * and cumulative daily OTU across every dive in the day!
     */
    /**
     * For each enabled deco gas, plans the same dive with that gas lost and returns, per remaining gas,
     * the highest usage (litres) seen across those scenarios.
     */
    fun lostGasWorstCaseUsage(input: DivePlanInput): Map<String, Double> {
        val decoGases = input.gases.filter { it.enabled && (it.role == GasRole.DECO || it.role == GasRole.BAILOUT) }
        if (decoGases.isEmpty()) return emptyMap()
        val worst = mutableMapOf<String, Double>()
        for (lost in decoGases) {
            val scenario = input.copy(
                gases = input.gases.map { if (it.id == lost.id) it.copy(enabled = false) else it },
                consumption = input.consumption.copy(decoReserveRule = ReserveRuleDeco.MULTIPLIER_1_5)
            )
            val res = planDive(scenario, includeContingencies = false, recordTimeline = false)
            if (res.abortedReason != null) continue
            for (gc in res.gasConsumption) {
                worst[gc.gas.id] = max(worst[gc.gas.id] ?: 0.0, gc.usedLitres)
            }
        }
        return worst
    }

    fun planMultiDiveDay(
        baseInput: DivePlanInput,
        dives: List<MultiDiveEntry>,
        includeContingenciesForIndex: Int = 0
    ): MultiDiveDayResult {
        val results = mutableListOf<DivePlanResult>()
        val intervals = mutableListOf<Double>()
        val startTissuesList = mutableListOf<TissueState>()

        var carryTissues: TissueState? = null
        var carryCns = 0.0
        var carryOtu = 0.0
        var carryMaxDepth: Double? = null

        dives.forEachIndexed { idx, entry ->
            val si = if (idx == 0) 0.0 else entry.surfaceIntervalBeforeMin.coerceAtLeast(0.0)
            val diveGases = if (baseInput.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX) {
                val diveMaxD = entry.levels.maxOfOrNull { it.depthM } ?: 40.0
                GasCalculator.recommendBestMix(
                    maxDepthM = diveMaxD,
                    general = baseInput.general,
                    dive = baseInput.dive,
                    existingGases = baseInput.gases,
                    alerts = baseInput.alerts,
                    levels = entry.levels,
                    previousTissues = carryTissues,
                    surfaceIntervalMin = si,
                    consumption = baseInput.consumption
                ).plannerGases
            } else {
                baseInput.gases
            }
            val diveLevels = if (baseInput.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX) {
                entry.levels.map { it.copy(gasId = null) }
            } else {
                entry.levels
            }
            val diveInput = baseInput.copy(
                gases = diveGases,
                levels = diveLevels,
                previousTissues = carryTissues,
                previousCnsPct = carryCns,
                previousDailyOtu = carryOtu,
                surfaceIntervalMin = si,
                previousMaxDepthM = carryMaxDepth
            )
            val result = planDive(diveInput, includeContingencies = (idx == includeContingenciesForIndex))
            results += result
            intervals += si
            startTissuesList += result.startTissues

            carryTissues = result.endTissues
            carryCns = result.oxygen.endCnsPct
            carryOtu = result.oxygen.dailyOtu
            carryMaxDepth = result.maxDepthM
        }

        return MultiDiveDayResult(
            diveResults = results,
            surfaceIntervalsMin = intervals,
            postSurfaceIntervalTissues = startTissuesList,
            totalDailyOtu = carryOtu,
            peakEndCnsPct = results.maxOfOrNull { it.oxygen.endCnsPct } ?: 0.0
        )
    }

    /**
     * Generates the 3 core technical contingency plans quickly:
     * +3m deeper, +5min longer, and lost richest deco gas.
     */
    fun generateContingencies(input: DivePlanInput): List<ContingencySummary> {
        val list = mutableListOf<ContingencySummary>()

        // 1. Deeper (+3 m)
        val deeperInput = input.copy(
            levels = input.levels.map { it.copy(depthM = it.depthM + 3.0) }
        )
        list += toContingencySummary("Deeper (+3 m)", "Max depth +3 m contingency", planDive(deeperInput, includeContingencies = false, recordTimeline = false))

        // 2. Longer (+5 min on first level)
        val longerInput = input.copy(
            levels = input.levels.mapIndexed { idx, lvl ->
                if (idx == 0) lvl.copy(timeMin = lvl.timeMin + 5.0) else lvl
            }
        )
        list += toContingencySummary("Longer (+5 min)", "Bottom time +5 min contingency", planDive(longerInput, includeContingencies = false, recordTimeline = false))

        // 3. Lost Richest Deco Gas
        val richestDeco = input.gases
            .filter { it.enabled && (it.role == GasRole.DECO || it.role == GasRole.BAILOUT) }
            .maxByOrNull { it.o2 }
        if (richestDeco != null) {
            val lostInput = input.copy(
                gases = input.gases.map { if (it.id == richestDeco.id) it.copy(enabled = false) else it }
            )
            list += toContingencySummary(
                "Lost ${richestDeco.displayName}",
                "Ascent & deco without ${richestDeco.displayName}",
                planDive(lostInput, includeContingencies = false, recordTimeline = false)
            )
        }

        return list
    }

    /**
     * CCR Open-Circuit Bailout plan from worst point with stress RMV (Section 8.6 & Section 11).
     */
    fun generateCcrBailout(input: DivePlanInput): ContingencySummary {
        val ocBailoutInput = input.copy(
            general = input.general.copy(planningType = PlanningType.OPEN_CIRCUIT),
            consumption = input.consumption.copy(
                bottomRmvLMin = input.consumption.bottomRmvLMin * input.consumption.stressFactor,
                decoRmvLMin = input.consumption.decoRmvLMin * input.consumption.stressFactor
            )
        )
        return toContingencySummary(
            "CCR OC Bailout",
            "Open-circuit bailout at ${input.consumption.stressFactor}× stress RMV",
            planDive(ocBailoutInput, includeContingencies = false, recordTimeline = false)
        )
    }

    private fun toContingencySummary(title: String, subtitle: String, res: DivePlanResult): ContingencySummary {
        return ContingencySummary(
            title = title,
            subtitle = subtitle,
            maxDepthM = res.maxDepthM,
            totalRuntimeMin = res.totalRuntimeMin,
            totalDecoMin = res.totalDecoMin,
            ttsMin = res.ttsMin,
            cnsPct = res.oxygen.endCnsPct,
            otu = res.oxygen.diveOtu,
            stops = res.stops,
            hasErrors = res.hasErrors
        )
    }

    private fun abortedPlan(
        input: DivePlanInput,
        env: Environment,
        tissues: TissueState,
        reason: String
    ): DivePlanResult {
        return DivePlanResult(
            input = input,
            env = env,
            segments = emptyList(),
            stops = emptyList(),
            maxDepthM = 0.0,
            totalRuntimeMin = 0.0,
            totalDecoMin = 0.0,
            ttsMin = 0.0,
            firstStopM = 0,
            ndlMin = 0.0,
            startTissues = tissues,
            endTissues = tissues,
            endSurfaceGfPct = 0.0,
            tissueTimeline = emptyList(),
            oxygen = OxygenSummary(0.0, input.previousCnsPct, input.previousCnsPct, 0.0, input.previousDailyOtu, 0.0),
            gasConsumption = emptyList(),
            alerts = listOf(PlanAlert(AlertLevel.CRITICAL, AlertCode.DECO_OVERFLOW, reason)),
            abortedReason = reason
        )
    }
}
