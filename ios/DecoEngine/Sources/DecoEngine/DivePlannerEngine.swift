import Foundation

/// Main decompression planner: schedule builder, multi-dive day, contingencies, CCR bailout, lost-gas
/// (mirrors DivePlannerEngine.kt line for line; keep both in step).
public enum DivePlannerEngine {

    public static func planDive(_ input: DivePlanInput, includeContingencies: Bool = false, recordTimeline: Bool = true) -> DivePlanResult {
        let env = UnitsAndPressure.buildEnvironment(input.general)
        let coeffs = Coefficients.tableFor(input.general.decoModel, use1b: input.general.useCompartment1b)
        let enabledGases = input.gases.filter { $0.enabled }
        let baseTissues = input.previousTissues ?? TissuesAndBuhlmann.initTissues(env, arrivedUnder12hAtAltitude: input.general.arrivedUnder12hAtAltitude)
        let startTissues = input.surfaceIntervalMin > 0.0
            ? TissuesAndBuhlmann.surfaceOffgas(baseTissues, input.surfaceIntervalMin, env, coeffs) : baseTissues
        let levels = input.levels.filter { $0.depthM > 0.0 && $0.timeMin > 0.0 }
        if levels.isEmpty || enabledGases.isEmpty {
            return abortedPlan(input, env, startTissues, "Define at least one dive level and one enabled breathing gas.")
        }
        return executePlanCleanly(input, env, coeffs, startTissues, levels, enabledGases, includeContingencies, recordTimeline)
    }

    // swiftlint:disable:next function_body_length cyclomatic_complexity
    static func executePlanCleanly(_ input: DivePlanInput, _ env: Environment, _ coeffs: [CompartmentCoeff],
                                   _ startTissues: TissueState, _ levels: [DiveLevel], _ enabledGases: [Gas],
                                   _ includeContingencies: Bool, _ recordTimeline: Bool) -> DivePlanResult {
        let isCcr = input.general.planningType == .ccr
        let dive = input.dive
        let gfLow = Double(input.general.gfLow) / 100.0
        let gfHigh = Double(input.general.gfHigh) / 100.0
        let stopInc = dive.stopIncrementM.coerceIn(1, 3)
        let stopIncD = Double(stopInc)
        let lastStopM = dive.lastStopM.coerceIn(3, 6)
        let lastStopD = Double(lastStopM)
        let descentRate = dive.descentRateMMin.coerceAtLeast(1.0)
        let ascentRate = dive.ascentRateToFirstStopMMin.coerceAtLeast(1.0)
        let decoAscentRate = dive.ascentDecoRateMMin.coerceAtLeast(1.0)
        let surfaceRate = dive.ascentRateToSurfaceMMin.coerceAtLeast(1.0)
        let model = input.general.decoModel
        let vpmC = input.general.vpmConservatism

        let primaryBottomGas = enabledGases.first(where: { isCcr ? ($0.role == .diluent || $0.role == .bottom) : $0.role == .bottom }) ?? enabledGases[0]
        let travelGas = enabledGases.first(where: { $0.role == .travel })

        var segments = [Segment]()
        var stops = [StopSummary]()
        var timeline = [TissueSnapshot]()
        var tissues = startTissues
        var runtime = 0.0
        var currentDepth = 0.0
        var currentGas = primaryBottomGas
        var totalDecoMin = 0.0
        var firstStopRecorded = 0

        func ceilingM(_ t: TissueState, _ gf: Double) -> Double {
            TissuesAndBuhlmann.ceilingDepthM(t, gf, coeffs, env, model: model, vpmConservatism: vpmC)
        }

        func activeSetpoint(_ depthM: Double, isDescent: Bool, isDeco: Bool) -> Double? {
            if !isCcr { return nil }
            if isDescent && depthM < input.ccr.switchToHighDepthM { return input.ccr.setpointDescent }
            if isDeco && depthM <= input.ccr.switchToDecoDepthM { return input.ccr.setpointDeco }
            return input.ccr.setpointBottom
        }

        func recordSnapshot(_ depthM: Double, _ gas: Gas, _ sp: Double?) {
            if !recordTimeline { return }
            let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
            let insp = (isCcr && sp != nil) ? UnitsAndPressure.inspiredPressuresCCR(pAmb, gas, sp!) : UnitsAndPressure.inspiredPressuresOC(pAmb, gas)
            let (gf99, leader) = TissuesAndBuhlmann.gf99WithLeader(tissues, pAmb, coeffs)
            timeline.append(TissueSnapshot(
                runtimeMin: runtime, depthM: depthM, pAmbBar: pAmb, ceilingM: ceilingM(tissues, gfLow), gas: gas,
                ppO2: insp.ppO2,
                ppN2: (isCcr && sp != nil) ? insp.pInspiredN2 : pAmb * gas.n2,
                ppHe: (isCcr && sp != nil) ? insp.pInspiredHe : pAmb * gas.he,
                tissues: tissues, gf99Pct: gf99, surfaceGfPct: TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs),
                leadingCompartmentIndex: leader,
                compartmentSaturationPct: TissuesAndBuhlmann.compartmentSurfaceLoadPct(tissues, env, coeffs)))
        }

        func appendSegment(_ type: SegmentType, _ fromD: Double, _ toD: Double, _ durationMin: Double, _ gas: Gas, _ sp: Double?) {
            if durationMin <= 1e-6 { return }
            if !recordTimeline {
                tissues = TissuesAndBuhlmann.advanceTissues(tissues, fromD, toD, durationMin, gas, env, coeffs, isCcr: isCcr, setpoint: sp)
                runtime += durationMin
            } else {
                let sampleStep = durationMin > 15.0 ? 0.5 : 0.25
                let steps = min(40, max(1, Int((durationMin / sampleStep).rounded(.up))))
                let dt = durationMin / Double(steps)
                for i in 1...steps {
                    let d0 = fromD + (toD - fromD) * (Double(i - 1) / Double(steps))
                    let d1 = fromD + (toD - fromD) * (Double(i) / Double(steps))
                    tissues = TissuesAndBuhlmann.advanceTissues(tissues, d0, d1, dt, gas, env, coeffs, isCcr: isCcr, setpoint: sp)
                    runtime += dt
                    recordSnapshot(d1, gas, sp)
                }
            }
            let pStart = UnitsAndPressure.ambientPressure(fromD, env)
            let pEnd = UnitsAndPressure.ambientPressure(toD, env)
            let useCcr = isCcr && sp != nil
            let inspStart = useCcr ? UnitsAndPressure.inspiredPressuresCCR(pStart, gas, sp!) : UnitsAndPressure.inspiredPressuresOC(pStart, gas)
            let inspEnd = useCcr ? UnitsAndPressure.inspiredPressuresCCR(pEnd, gas, sp!) : UnitsAndPressure.inspiredPressuresOC(pEnd, gas)
            segments.append(Segment(
                type: type, fromDepthM: fromD, toDepthM: toD, durationMin: durationMin, runtimeMin: runtime, gas: gas,
                ppO2Start: inspStart.ppO2, ppO2End: inspEnd.ppO2,
                ppN2End: useCcr ? inspEnd.pInspiredN2 : pEnd * gas.n2,
                ppHeEnd: useCcr ? inspEnd.pInspiredHe : pEnd * gas.he,
                endM: GasCalculator.endM(gas, toD, env, dive.o2Narcotic),
                densityGL: GasCalculator.densityGL(gas, toD, env),
                ceilingM: ceilingM(tissues, gfLow),
                gf99Pct: TissuesAndBuhlmann.gf99Pct(tissues, pEnd, coeffs),
                surfaceGfPct: TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs),
                isCcr: isCcr, setpoint: sp))
            currentDepth = toD
            currentGas = gas
        }

        // ---- 1. Descent ----
        let firstLevel = levels[0]
        let firstLevelGas = enabledGases.first(where: { $0.id == firstLevel.gasId }) ?? primaryBottomGas
        let bottomMin = GasCalculator.minDepthM(firstLevelGas, dive.ppO2Min, env)
        var shouldUseTravel = false
        if !isCcr, let tg = travelGas, tg.id != firstLevelGas.id, GasCalculator.minDepthM(tg, dive.ppO2Min, env) <= 0.0 {
            shouldUseTravel = bottomMin > 0.0 || (tg.o2 > firstLevelGas.o2 + 0.01 && firstLevel.depthM > 24.0)
        }
        let startGas = (shouldUseTravel && travelGas != nil) ? travelGas! : firstLevelGas
        recordSnapshot(0.0, startGas, activeSetpoint(0.0, isDescent: true, isDeco: false))

        if shouldUseTravel, let tg = travelGas {
            let travelMod = GasCalculator.modDisplayM(tg, dive.ppO2Bottom, env)
            let rawSwitch = min(firstLevel.depthM - stopIncD, travelMod)
            let snappedSwitch = ((rawSwitch / stopIncD).rounded(.down) * stopIncD)
                .coerceAtLeast(max(bottomMin, 9.0))
                .coerceAtMost(min(travelMod, firstLevel.depthM - 3.0))
            if snappedSwitch >= 9.0 && snappedSwitch < firstLevel.depthM - 1e-6 {
                appendSegment(.descent, 0.0, snappedSwitch, snappedSwitch / descentRate, tg, nil)
                if dive.mixChangeExtraTimeMin > 0.0 {
                    appendSegment(.switchGas, snappedSwitch, snappedSwitch, dive.mixChangeExtraTimeMin, firstLevelGas, nil)
                }
                appendSegment(.descent, snappedSwitch, firstLevel.depthM, (firstLevel.depthM - snappedSwitch) / descentRate, firstLevelGas, nil)
            } else {
                appendSegment(.descent, 0.0, firstLevel.depthM, firstLevel.depthM / descentRate, firstLevelGas, nil)
            }
        } else if isCcr && firstLevel.depthM > input.ccr.switchToHighDepthM + 1e-6 && input.ccr.switchToHighDepthM > 0.0 {
            let swD = input.ccr.switchToHighDepthM
            appendSegment(.descent, 0.0, swD, swD / descentRate, firstLevelGas, input.ccr.setpointDescent)
            appendSegment(.descent, swD, firstLevel.depthM, (firstLevel.depthM - swD) / descentRate, firstLevelGas, input.ccr.setpointBottom)
        } else {
            let spDesc = activeSetpoint(firstLevel.depthM / 2.0, isDescent: true, isDeco: false)
            appendSegment(.descent, 0.0, firstLevel.depthM, firstLevel.depthM / descentRate, firstLevelGas, spDesc)
        }

        // ---- 2. Bottom / multi-level ----
        let firstLevelDur = input.general.btIncludesDescent ? (firstLevel.timeMin - runtime).coerceAtLeast(0.0) : firstLevel.timeMin
        let spBottom1 = activeSetpoint(firstLevel.depthM, isDescent: false, isDeco: false)
        appendSegment(.level, firstLevel.depthM, firstLevel.depthM, firstLevelDur, firstLevelGas, spBottom1)

        if levels.count > 1 {
            for i in 1..<levels.count {
                let lvl = levels[i]
                let lvlGas = enabledGases.first(where: { $0.id == lvl.gasId })
                    ?? (!isCcr ? GasCalculator.bestWorkingGasAt(lvl.depthM, primaryBottomGas, enabledGases, env, dive) : primaryBottomGas)
                let spLvl = activeSetpoint(lvl.depthM, isDescent: lvl.depthM > currentDepth, isDeco: false)
                if abs(lvl.depthM - currentDepth) > 0.1 {
                    let deeper = lvl.depthM > currentDepth
                    appendSegment(deeper ? .descent : .ascent, currentDepth, lvl.depthM,
                                  abs(lvl.depthM - currentDepth) / (deeper ? descentRate : ascentRate), lvlGas, spLvl)
                }
                appendSegment(.level, lvl.depthM, lvl.depthM, lvl.timeMin, lvlGas, spLvl)
            }
        }

        let endBottomRuntime = runtime
        let maxDepthM = levels.map(\.depthM).max() ?? 0.0
        let ndlMin = TissuesAndBuhlmann.computeNdlMin(firstLevel.depthM, firstLevelGas, input.general, env,
                                                      initialTissues: startTissues, isCcr: isCcr, setpoint: spBottom1)

        // ---- 3. Ascent & decompression ----
        let isWithinNdl: Bool = {
            var sim = tissues
            var d = currentDepth
            if ceilingM(sim, gfHigh) > 0.0 { return false }
            if d > lastStopD {
                let dt1 = (d - lastStopD) / ascentRate
                let sp1 = activeSetpoint((d + lastStopD) / 2.0, isDescent: false, isDeco: false)
                sim = TissuesAndBuhlmann.advanceTissues(sim, d, lastStopD, dt1, currentGas, env, coeffs, isCcr: isCcr, setpoint: sp1)
                d = lastStopD
                if ceilingM(sim, gfHigh) > 0.0 { return false }
            }
            if d > 0.0 {
                let dt2 = d / surfaceRate
                let sp2 = activeSetpoint(d / 2.0, isDescent: false, isDeco: true)
                sim = TissuesAndBuhlmann.advanceTissues(sim, d, 0.0, dt2, currentGas, env, coeffs, isCcr: isCcr, setpoint: sp2)
                if ceilingM(sim, gfHigh) > 0.0 { return false }
            }
            return true
        }()

        var firstStop: Int
        if isWithinNdl {
            firstStop = 0
        } else {
            let rawFirst = UnitsAndPressure.ceilToIncrement(ceilingM(tissues, gfLow), stopInc)
            firstStop = (rawFirst >= 1 && rawFirst < lastStopM) ? lastStopM : rawFirst
        }

        var depth = currentDepth
        var highO2TimeMin = 0.0

        func ascendWithSwitches(_ targetDepth: Double, _ rate: Double, isDecoPhase: Bool) {
            var d = depth
            var ascGuard = 0
            while d > targetDepth + 1e-6 && ascGuard < 300 {
                ascGuard += 1
                let nextMultiple = (((d / stopIncD).rounded(.up) - 1) * stopIncD).coerceAtLeast(targetDepth)
                let stepTarget = abs(d - nextMultiple) < 1e-6 ? (d - stopIncD).coerceAtLeast(targetDepth) : nextMultiple
                let spAsc = activeSetpoint((d + stepTarget) / 2.0, isDescent: false, isDeco: isDecoPhase)
                let stepTime = (d - stepTarget) / rate
                let preview = TissuesAndBuhlmann.advanceTissues(tissues, d, stepTarget, stepTime, currentGas, env, coeffs, isCcr: isCcr, setpoint: spAsc)
                let ceilGfLow = ceilingM(preview, gfLow)
                if !isDecoPhase && ceilGfLow > stepTarget + 1e-6 {
                    let forcedStop = UnitsAndPressure.ceilToIncrement(ceilGfLow, stopInc).coerceAtLeast(lastStopM)
                    if Double(forcedStop) < d {
                        appendSegment(.ascent, d, Double(forcedStop), (d - Double(forcedStop)) / rate, currentGas, spAsc)
                        d = Double(forcedStop)
                    }
                    firstStop = max(firstStop, forcedStop)
                    depth = d
                    return
                }
                appendSegment(.ascent, d, stepTarget, stepTime, currentGas, spAsc)
                d = stepTarget
                depth = d
                if !isCcr && d > targetDepth + 1e-6 && d > 0.0 {
                    let betterGas = GasCalculator.bestGasAt(d, currentGas, enabledGases, env, dive)
                    if betterGas.id != currentGas.id {
                        currentGas = betterGas
                        if dive.mixChangeExtraTimeMin > 0.0 {
                            appendSegment(.switchGas, d, d, dive.mixChangeExtraTimeMin, currentGas, nil)
                            stops.append(StopSummary(depthM: kRound(d), durationMin: dive.mixChangeExtraTimeMin, runtimeMin: runtime, gas: currentGas, isSwitch: true))
                            totalDecoMin += dive.mixChangeExtraTimeMin
                        } else if dive.stopOnGasSwitch {
                            appendSegment(.stop, d, d, dive.minStopTimeMin, currentGas, nil)
                            stops.append(StopSummary(depthM: kRound(d), durationMin: dive.minStopTimeMin, runtimeMin: runtime, gas: currentGas, isSwitch: true))
                            totalDecoMin += dive.minStopTimeMin
                        }
                    }
                }
            }
        }

        if isWithinNdl {
            if depth > lastStopD {
                let spAsc = activeSetpoint((depth + lastStopD) / 2.0, isDescent: false, isDeco: false)
                appendSegment(.ascent, depth, lastStopD, (depth - lastStopD) / ascentRate, currentGas, spAsc)
                depth = lastStopD
            }
            if depth > 0.0 {
                let spSurf = activeSetpoint(depth / 2.0, isDescent: false, isDeco: true)
                appendSegment(.ascent, depth, 0.0, depth / surfaceRate, currentGas, spSurf)
                depth = 0.0
            }
        } else if firstStop <= 0 {
            if depth > lastStopD { ascendWithSwitches(lastStopD, ascentRate, isDecoPhase: false) }
            if firstStop <= 0 { firstStop = lastStopM }
        }

        if depth > 0.0 && firstStop > 0 {
            if depth > Double(firstStop) { ascendWithSwitches(Double(firstStop), ascentRate, isDecoPhase: false) }
            let lockedFirstStop = max(firstStop, UnitsAndPressure.ceilToIncrement(depth, stopInc)).coerceAtLeast(lastStopM)
            firstStopRecorded = lockedFirstStop
            var stopDepth = lockedFirstStop
            var stopGuard = 0
            while stopDepth > 0 && stopGuard < 300 {
                stopGuard += 1
                let nextStop = stopDepth > lastStopM ? stopDepth - stopInc : 0
                let spStop = activeSetpoint(Double(stopDepth), isDescent: false, isDeco: true)

                var switchedHere = false
                if !isCcr {
                    let betterGas = GasCalculator.bestGasAt(Double(stopDepth), currentGas, enabledGases, env, dive)
                    if betterGas.id != currentGas.id {
                        switchedHere = true
                        currentGas = betterGas
                        if dive.mixChangeExtraTimeMin > 0.0 {
                            appendSegment(.switchGas, Double(stopDepth), Double(stopDepth), dive.mixChangeExtraTimeMin, currentGas, nil)
                            totalDecoMin += dive.mixChangeExtraTimeMin
                        }
                    }
                }

                let gfNext = TissuesAndBuhlmann.gfAtDepth(Double(nextStop), Double(lockedFirstStop), gfLow, gfHigh)
                let maxRemMin = (Constants.MAX_DECO_MINUTES - Int(totalDecoMin)).coerceAtLeast(1)
                var prevTissues = tissues
                var simTissues = tissues
                var coarseMin = 0
                while ceilingM(simTissues, gfNext) > Double(nextStop) + 1e-6 {
                    prevTissues = simTissues
                    simTissues = TissuesAndBuhlmann.advanceTissues(simTissues, Double(stopDepth), Double(stopDepth), 1.0, currentGas, env, coeffs, isCcr: isCcr, setpoint: spStop)
                    coarseMin += 1
                    if coarseMin >= maxRemMin {
                        return abortedPlan(input, env, startTissues, "Deco obligation too large (> \(Constants.MAX_DECO_MINUTES) min).")
                    }
                }
                var neededSeconds = 0
                if coarseMin > 0 {
                    neededSeconds = (coarseMin - 1) * 60
                    var fineTissues = prevTissues
                    var extraSec = 0
                    while extraSec < 60 && ceilingM(fineTissues, gfNext) > Double(nextStop) + 1e-6 {
                        fineTissues = TissuesAndBuhlmann.advanceTissues(fineTissues, Double(stopDepth), Double(stopDepth), 1.0 / 60.0, currentGas, env, coeffs, isCcr: isCcr, setpoint: spStop)
                        extraSec += 1
                    }
                    neededSeconds += extraSec
                }

                let minStepSec = kRound(dive.minStopTimeMin * 60.0).coerceAtLeast(30)
                let roundedStopMin: Double
                if neededSeconds > 0 {
                    roundedStopMin = ((Double(neededSeconds) / Double(minStepSec)).rounded(.up) * Double(minStepSec)) / 60.0
                } else if switchedHere && dive.stopOnGasSwitch && dive.mixChangeExtraTimeMin <= 0.0 {
                    roundedStopMin = dive.minStopTimeMin
                } else {
                    roundedStopMin = 0.0
                }

                if roundedStopMin > 0.0 {
                    let highO2 = (!isCcr && currentGas.o2 >= 0.80) || (isCcr && (spStop ?? 0.0) >= 1.35)
                    if input.gasBreaks.enabled && highO2 && Double(stopDepth) <= input.gasBreaks.applyDepthMaxM + 0.1 {
                        var remainingStop = roundedStopMin
                        let breakMix = primaryBottomGas
                        while remainingStop > 1e-6 {
                            let untilBreak = (input.gasBreaks.breakAfterMin - highO2TimeMin).coerceAtLeast(0.5)
                            let chunk = min(remainingStop, untilBreak)
                            appendSegment(.stop, Double(stopDepth), Double(stopDepth), chunk, currentGas, spStop)
                            totalDecoMin += chunk
                            highO2TimeMin += chunk
                            remainingStop -= chunk
                            if highO2TimeMin >= input.gasBreaks.breakAfterMin - 1e-4 && remainingStop > 1e-4 {
                                let breakDur = input.gasBreaks.breakTimeMin.coerceAtLeast(1.0)
                                appendSegment(.airBreak, Double(stopDepth), Double(stopDepth), breakDur, breakMix, isCcr ? 0.7 : nil)
                                totalDecoMin += breakDur
                                highO2TimeMin = 0.0
                            }
                        }
                    } else {
                        appendSegment(.stop, Double(stopDepth), Double(stopDepth), roundedStopMin, currentGas, spStop)
                        totalDecoMin += roundedStopMin
                        if highO2 { highO2TimeMin += roundedStopMin } else { highO2TimeMin = 0.0 }
                    }
                    stops.append(StopSummary(depthM: stopDepth, durationMin: roundedStopMin + (switchedHere ? dive.mixChangeExtraTimeMin : 0.0),
                                             runtimeMin: runtime, gas: currentGas, isSwitch: switchedHere))
                } else if switchedHere && dive.mixChangeExtraTimeMin > 0.0 {
                    stops.append(StopSummary(depthM: stopDepth, durationMin: dive.mixChangeExtraTimeMin, runtimeMin: runtime, gas: currentGas, isSwitch: true))
                }

                let rate = nextStop == 0 ? surfaceRate : decoAscentRate
                let spTransit = activeSetpoint(Double(stopDepth + nextStop) / 2.0, isDescent: false, isDeco: true)
                appendSegment(.ascent, Double(stopDepth), Double(nextStop), Double(stopDepth - nextStop) / rate, currentGas, spTransit)
                stopDepth = nextStop
                depth = Double(nextStop)
            }
        }

        let oxygen = OxygenAndConsumption.computeOxygenSummary(segments, previousCnsPct: input.previousCnsPct,
                                                                previousDailyOtu: input.previousDailyOtu, surfaceIntervalMin: input.surfaceIntervalMin)
        let lostGasMaxUsage: [String: Double] = (!isCcr && input.consumption.decoReserveRule == .lostGas) ? lostGasWorstCaseUsage(input) : [:]
        let rawConsumption = OxygenAndConsumption.computeGasConsumption(segments, input, env, lostGasMaxUsageById: lostGasMaxUsage)

        var finalInput = input
        var finalSegments = segments
        var finalStops = stops
        var finalTimeline = timeline
        var gasConsumption = rawConsumption

        if input.general.gasSelectionMode == .bestGasMix {
            var requiredByGasId = [String: GasConsumptionResult]()
            for gc in rawConsumption { requiredByGasId[gc.gas.id] = gc }
            var updatedGasById = [String: Gas]()
            let resizedGases: [Gas] = input.gases.map { g in
                let singleTankLitres = (g.cylinder.waterVolumeL * g.cylinder.fillPressureBar).coerceAtLeast(1.0)
                var neededCount = 1
                if let gc = requiredByGasId[g.id], gc.totalRequiredLitres > 0.0 {
                    neededCount = Int(((gc.totalRequiredLitres + 1e-3) / singleTankLitres).rounded(.up)).coerceIn(1, 20)
                }
                var resized = g
                resized.cylinder.count = neededCount
                updatedGasById[resized.id] = resized
                return resized
            }
            finalInput.gases = resizedGases
            finalSegments = segments.map { seg in
                guard let g = updatedGasById[seg.gas.id] else { return seg }
                var s = seg; s.gas = g; return s
            }
            finalStops = stops.map { st in
                guard let g = updatedGasById[st.gas.id] else { return st }
                var s = st; s.gas = g; return s
            }
            if recordTimeline {
                finalTimeline = timeline.map { snap in
                    guard let g = updatedGasById[snap.gas.id] else { return snap }
                    var s = snap; s.gas = g; return s
                }
            }
            gasConsumption = OxygenAndConsumption.computeGasConsumption(finalSegments, finalInput, env, lostGasMaxUsageById: lostGasMaxUsage)
        }

        let endSurfGf = TissuesAndBuhlmann.surfaceGfPct(tissues, env, coeffs)
        let ttsMin = (runtime - endBottomRuntime).coerceAtLeast(0.0)
        let alerts = OxygenAndConsumption.runSafetyChecks(finalInput, env, finalSegments, oxygen, gasConsumption,
                                                          endSurfaceGfPct: endSurfGf, ttsMin: ttsMin, totalDecoMin: totalDecoMin,
                                                          ndlMin: ndlMin, totalRuntimeMin: runtime, maxDepthM: maxDepthM)
        let contingencies = includeContingencies ? generateContingencies(finalInput) : []
        let ccrBailout = (includeContingencies && isCcr) ? generateCcrBailout(finalInput) : nil

        return DivePlanResult(input: finalInput, env: env, segments: finalSegments, stops: finalStops, maxDepthM: maxDepthM,
                              totalRuntimeMin: runtime, totalDecoMin: totalDecoMin, ttsMin: ttsMin, firstStopM: firstStopRecorded,
                              ndlMin: ndlMin, startTissues: startTissues, endTissues: tissues, endSurfaceGfPct: endSurfGf,
                              tissueTimeline: finalTimeline, oxygen: oxygen, gasConsumption: gasConsumption, alerts: alerts,
                              contingencies: contingencies, ccrBailout: ccrBailout)
    }

    /// For each enabled deco gas, plans the dive with that gas lost; returns per remaining gas the highest usage seen.
    public static func lostGasWorstCaseUsage(_ input: DivePlanInput) -> [String: Double] {
        let decoGases = input.gases.filter { $0.enabled && ($0.role == .deco || $0.role == .bailout) }
        if decoGases.isEmpty { return [:] }
        var worst = [String: Double]()
        for lost in decoGases {
            var scenario = input
            scenario.gases = input.gases.map { g in var c = g; if g.id == lost.id { c.enabled = false }; return c }
            scenario.consumption.decoReserveRule = .multiplier15
            let res = planDive(scenario, includeContingencies: false, recordTimeline: false)
            if res.abortedReason != nil { continue }
            for gc in res.gasConsumption { worst[gc.gas.id] = max(worst[gc.gas.id] ?? 0.0, gc.usedLitres) }
        }
        return worst
    }

    /// Multi-dive day: carries tissues, CNS (90 min decay) and daily OTU from dive to dive.
    public static func planMultiDiveDay(_ baseInput: DivePlanInput, _ dives: [MultiDiveEntry], includeContingenciesForIndex: Int = 0) -> MultiDiveDayResult {
        var results = [DivePlanResult]()
        var intervals = [Double]()
        var startTissuesList = [TissueState]()
        var carryTissues: TissueState? = nil
        var carryCns = 0.0
        var carryOtu = 0.0
        var carryMaxDepth: Double? = nil

        for (idx, entry) in dives.enumerated() {
            let si = idx == 0 ? 0.0 : entry.surfaceIntervalBeforeMin.coerceAtLeast(0.0)
            let isBest = baseInput.general.gasSelectionMode == .bestGasMix
            let diveGases: [Gas] = isBest
                ? GasCalculator.recommendBestMix(maxDepthM: entry.levels.map(\.depthM).max() ?? 40.0, general: baseInput.general,
                                                 dive: baseInput.dive, existingGases: baseInput.gases, alerts: baseInput.alerts,
                                                 levels: entry.levels, previousTissues: carryTissues, surfaceIntervalMin: si,
                                                 consumption: baseInput.consumption).plannerGases
                : baseInput.gases
            let diveLevels = isBest ? entry.levels.map { var l = $0; l.gasId = nil; return l } : entry.levels
            var diveInput = baseInput
            diveInput.gases = diveGases
            diveInput.levels = diveLevels
            diveInput.previousTissues = carryTissues
            diveInput.previousCnsPct = carryCns
            diveInput.previousDailyOtu = carryOtu
            diveInput.surfaceIntervalMin = si
            diveInput.previousMaxDepthM = carryMaxDepth
            let result = planDive(diveInput, includeContingencies: idx == includeContingenciesForIndex)
            results.append(result)
            intervals.append(si)
            startTissuesList.append(result.startTissues)
            carryTissues = result.endTissues
            carryCns = result.oxygen.endCnsPct
            carryOtu = result.oxygen.dailyOtu
            carryMaxDepth = result.maxDepthM
        }
        return MultiDiveDayResult(diveResults: results, surfaceIntervalsMin: intervals, postSurfaceIntervalTissues: startTissuesList,
                                  totalDailyOtu: carryOtu, peakEndCnsPct: results.map(\.oxygen.endCnsPct).max() ?? 0.0)
    }

    /// Deeper (+3 m), longer (+5 min) and lost richest deco gas.
    public static func generateContingencies(_ input: DivePlanInput) -> [ContingencySummary] {
        var list = [ContingencySummary]()
        var deeper = input
        deeper.levels = input.levels.map { var l = $0; l.depthM += 3.0; return l }
        list.append(toContingencySummary("Deeper (+3 m)", "Max depth +3 m contingency", planDive(deeper, includeContingencies: false, recordTimeline: false)))
        var longer = input
        longer.levels = input.levels.enumerated().map { idx, l in var c = l; if idx == 0 { c.timeMin += 5.0 }; return c }
        list.append(toContingencySummary("Longer (+5 min)", "Bottom time +5 min contingency", planDive(longer, includeContingencies: false, recordTimeline: false)))
        let decoCandidates = input.gases.filter { $0.enabled && ($0.role == .deco || $0.role == .bailout) }
        if let richest = decoCandidates.max(by: { $0.o2 < $1.o2 }) {
            var lost = input
            lost.gases = input.gases.map { g in var c = g; if g.id == richest.id { c.enabled = false }; return c }
            list.append(toContingencySummary("Lost \(richest.displayName)", "Ascent & deco without \(richest.displayName)",
                                             planDive(lost, includeContingencies: false, recordTimeline: false)))
        }
        return list
    }

    /// CCR open-circuit bailout at stress RMV.
    public static func generateCcrBailout(_ input: DivePlanInput) -> ContingencySummary {
        var oc = input
        oc.general.planningType = .openCircuit
        oc.consumption.bottomRmvLMin = input.consumption.bottomRmvLMin * input.consumption.stressFactor
        oc.consumption.decoRmvLMin = input.consumption.decoRmvLMin * input.consumption.stressFactor
        return toContingencySummary("CCR OC Bailout", "Open-circuit bailout at \(input.consumption.stressFactor)× stress RMV",
                                    planDive(oc, includeContingencies: false, recordTimeline: false))
    }

    static func toContingencySummary(_ title: String, _ subtitle: String, _ res: DivePlanResult) -> ContingencySummary {
        ContingencySummary(title: title, subtitle: subtitle, maxDepthM: res.maxDepthM, totalRuntimeMin: res.totalRuntimeMin,
                           totalDecoMin: res.totalDecoMin, ttsMin: res.ttsMin, cnsPct: res.oxygen.endCnsPct, otu: res.oxygen.diveOtu,
                           stops: res.stops, hasErrors: res.hasErrors)
    }

    static func abortedPlan(_ input: DivePlanInput, _ env: Environment, _ tissues: TissueState, _ reason: String) -> DivePlanResult {
        DivePlanResult(input: input, env: env, segments: [], stops: [], maxDepthM: 0.0, totalRuntimeMin: 0.0, totalDecoMin: 0.0,
                       ttsMin: 0.0, firstStopM: 0, ndlMin: 0.0, startTissues: tissues, endTissues: tissues, endSurfaceGfPct: 0.0,
                       tissueTimeline: [],
                       oxygen: OxygenSummary(diveCnsPct: 0.0, startCnsPct: input.previousCnsPct, endCnsPct: input.previousCnsPct,
                                             diveOtu: 0.0, dailyOtu: input.previousDailyOtu, maxPpO2: 0.0),
                       gasConsumption: [], alerts: [PlanAlert(.critical, .DECO_OVERFLOW, reason)], abortedReason: reason)
    }
}
