import Foundation

/// CNS %, OTU, gas consumption with reserves, and the 4-level safety checks (mirrors OxygenAndConsumption.kt).
public enum OxygenAndConsumption {

    // ---- CNS ----

    public static func cnsLimitMinutes(_ ppO2: Double) -> Double {
        if ppO2 <= 0.50 { return Double.infinity }
        let table = Constants.NOAA_CNS_TABLE
        if ppO2 <= 0.60 { return 720.0 + (0.60 - ppO2) / 0.10 * 180.0 }
        for i in 1..<table.count {
            let (p0, l0) = table[i - 1]
            let (p1, l1) = table[i]
            if ppO2 <= p1 {
                if l0.isInfinite { return l1 }
                let frac = (ppO2 - p0) / (p1 - p0)
                return l0 + frac * (l1 - l0)
            }
        }
        let over = ppO2 - 1.65
        return (15.0 * pow(0.25, over / 0.05)).coerceAtLeast(1.0)
    }

    public static func segmentCnsPct(_ ppO2Start: Double, _ ppO2End: Double, _ durationMin: Double) -> Double {
        if durationMin <= 0.0 { return 0.0 }
        if abs(ppO2End - ppO2Start) < 1e-6 {
            if ppO2Start <= 0.50 { return 0.0 }
            let limit = cnsLimitMinutes(ppO2Start)
            return limit.isInfinite ? 0.0 : (durationMin / limit) * 100.0
        }
        let steps = max(1, Int((durationMin * 6.0).rounded(.up)))
        let dt = durationMin / Double(steps)
        var total = 0.0
        for i in 0..<steps {
            let frac = (Double(i) + 0.5) / Double(steps)
            let p = ppO2Start + (ppO2End - ppO2Start) * frac
            if p > 0.50 {
                let limit = cnsLimitMinutes(p)
                if !limit.isInfinite { total += (dt / limit) * 100.0 }
            }
        }
        return total
    }

    public static func decayCnsPct(_ cnsPct: Double, _ surfaceIntervalMin: Double) -> Double {
        if surfaceIntervalMin <= 0.0 || cnsPct <= 0.0 { return cnsPct }
        return cnsPct * pow(0.5, surfaceIntervalMin / Constants.CNS_DECAY_HALF_TIME_MIN)
    }

    // ---- OTU ----

    public static func segmentOtu(_ ppO2Start: Double, _ ppO2End: Double, _ durationMin: Double) -> Double {
        if durationMin <= 0.0 { return 0.0 }
        if ppO2Start <= 0.5 && ppO2End <= 0.5 { return 0.0 }
        if abs(ppO2End - ppO2Start) < 1e-6 {
            return ppO2Start <= 0.5 ? 0.0 : durationMin * pow((ppO2Start - 0.5) / 0.5, 0.83)
        }
        var p1 = ppO2Start, p2 = ppO2End, t = durationMin
        if p1 < 0.5 && p2 > 0.5 {
            t *= (p2 - 0.5) / (p2 - p1); p1 = 0.5
        } else if p1 > 0.5 && p2 < 0.5 {
            t *= (p1 - 0.5) / (p1 - p2); p2 = 0.5
        }
        let term2 = pow((p2 - 0.5).coerceAtLeast(0.0) / 0.5, 11.0 / 6.0)
        let term1 = pow((p1 - 0.5).coerceAtLeast(0.0) / 0.5, 11.0 / 6.0)
        return ((3.0 / 11.0) * t / (p2 - p1) * (term2 - term1)).coerceAtLeast(0.0)
    }

    public static func computeOxygenSummary(_ segments: [Segment], previousCnsPct: Double, previousDailyOtu: Double,
                                            surfaceIntervalMin: Double) -> OxygenSummary {
        let startCns = decayCnsPct(previousCnsPct, surfaceIntervalMin)
        var diveCns = 0.0, diveOtu = 0.0, maxPpO2 = 0.0
        for seg in segments {
            diveCns += segmentCnsPct(seg.ppO2Start, seg.ppO2End, seg.durationMin)
            diveOtu += segmentOtu(seg.ppO2Start, seg.ppO2End, seg.durationMin)
            maxPpO2 = max(maxPpO2, max(seg.ppO2Start, seg.ppO2End))
        }
        return OxygenSummary(diveCnsPct: diveCns, startCnsPct: startCns, endCnsPct: startCns + diveCns,
                             diveOtu: diveOtu, dailyOtu: previousDailyOtu + diveOtu, maxPpO2: maxPpO2)
    }

    // ---- Gas consumption & reserves ----

    public static func computeGasConsumption(_ segments: [Segment], _ input: DivePlanInput, _ env: Environment,
                                             lostGasMaxUsageById: [String: Double] = [:]) -> [GasConsumptionResult] {
        var order = [String]()
        var usedByGasId = [String: Double]()
        var gasById = [String: Gas]()
        for g in input.gases where g.enabled {
            gasById[g.id] = g
        }
        let tempCorrection = 293.15 / (273.15 + Double(input.general.surfaceTempC.coerceIn(-10, 45)))
        let firstStopDepth = segments.first(where: { $0.type == .stop })?.fromDepthM ?? 0.0
        for seg in segments {
            gasById[seg.gas.id] = seg.gas
            let pStart = UnitsAndPressure.ambientPressure(seg.fromDepthM, env)
            let pEnd = UnitsAndPressure.ambientPressure(seg.toDepthM, env)
            let pAvg = (pStart + pEnd) / 2.0
            let rmv: Double
            if seg.isCcr {
                rmv = seg.type == .descent ? 3.5 : 0.8
            } else if seg.type == .stop || seg.type == .switchGas || seg.type == .airBreak {
                rmv = input.consumption.decoRmvLMin
            } else if seg.type == .ascent && seg.fromDepthM <= firstStopDepth {
                rmv = input.consumption.decoRmvLMin
            } else {
                rmv = input.consumption.bottomRmvLMin
            }
            let volLitres = rmv * (pAvg / env.pSurfaceBar) * seg.durationMin * tempCorrection
            if usedByGasId[seg.gas.id] == nil { order.append(seg.gas.id) }
            usedByGasId[seg.gas.id] = (usedByGasId[seg.gas.id] ?? 0.0) + volLitres
        }

        // Rock-bottom: bottom gas only, from max depth to the first gas switch on the ascent
        let maxDepthM = segments.map { max($0.fromDepthM, $0.toDepthM) }.max() ?? 0.0
        var lastBottomIdx = segments.lastIndex(where: { $0.type == .level }) ?? -1
        if lastBottomIdx < 0 {
            lastBottomIdx = segments.firstIndex(where: { max($0.fromDepthM, $0.toDepthM) >= maxDepthM - 1e-6 }) ?? -1
        }
        let rockBottomGasId: String? = lastBottomIdx >= 0 ? segments[lastBottomIdx].gas.id : nil
        let firstAscentSwitchDepth: Double = lastBottomIdx >= 0
            ? (segments[(lastBottomIdx + 1)...].first(where: { $0.gas.id != rockBottomGasId })?.fromDepthM ?? 0.0)
            : 0.0
        let rockBottomReserve = rockBottomReserveLitres(maxDepthM, firstAscentSwitchDepth, input, env)

        return order.map { gasId -> GasConsumptionResult in
            let usedL = usedByGasId[gasId]!
            let gas = gasById[gasId]!
            let isDeco = gas.role == .deco || gas.role == .bailout
            let reserveL: Double
            if isDeco {
                switch input.consumption.decoReserveRule {
                case .multiplier15: reserveL = usedL * 0.5
                case .lostGas:
                    let worstCase = max(usedL, lostGasMaxUsageById[gasId] ?? usedL)
                    reserveL = (worstCase * input.consumption.stressFactor.coerceAtLeast(1.0) - usedL).coerceAtLeast(0.0)
                }
            } else {
                switch input.consumption.bottomReserveRule {
                case .thirds: reserveL = usedL * 0.5
                case .quarters: reserveL = usedL * (1.0 / 3.0)
                case .half: reserveL = usedL * 1.0
                case .rockBottom: reserveL = gasId == rockBottomGasId ? max(rockBottomReserve, usedL * 0.5) : usedL * 0.5
                }
            }
            let totalReq = usedL + reserveL
            let cyl = gas.cylinder
            let totalWaterVol = (cyl.waterVolumeL * Double(cyl.count)).coerceAtLeast(1.0)
            let availableL = cyl.availableLitres
            let minPressure = reserveL / totalWaterVol
            let usableBottom = (availableL - reserveL).coerceAtLeast(0.0)
            let turnLower = min(minPressure, cyl.fillPressureBar)
            let turnPressure = (cyl.fillPressureBar - (usableBottom / 2.0) / totalWaterVol).coerceIn(turnLower, cyl.fillPressureBar)
            return GasConsumptionResult(gas: gas, usedLitres: usedL, reserveLitres: reserveL, totalRequiredLitres: totalReq,
                                        availableLitres: availableL, turnPressureBar: turnPressure, minPressureBar: minPressure,
                                        remainingBar: (availableL - usedL) / totalWaterVol, isSufficient: availableL >= totalReq - 1e-3)
        }
    }

    public static func rockBottomReserveLitres(_ maxDepthM: Double, _ switchDepthM: Double, _ input: DivePlanInput, _ env: Environment) -> Double {
        let ascentDist = (maxDepthM - switchDepthM).coerceAtLeast(0.0)
        let ascentMin = ascentDist / input.dive.ascentRateToFirstStopMMin.coerceAtLeast(1.0)
        let pMax = UnitsAndPressure.ambientPressure(maxDepthM, env)
        let pSwitch = UnitsAndPressure.ambientPressure(switchDepthM.coerceAtMost(maxDepthM), env)
        let stressRmv2Divers = input.consumption.bottomRmvLMin * input.consumption.stressFactor * 2.0
        return stressRmv2Divers * ((pMax / env.pSurfaceBar) * 1.0 + (((pMax + pSwitch) / 2.0) / env.pSurfaceBar) * ascentMin)
    }

    // ---- 4-level safety checks ----

    public static func runSafetyChecks(_ input: DivePlanInput, _ env: Environment, _ segments: [Segment],
                                       _ oxygen: OxygenSummary, _ consumption: [GasConsumptionResult],
                                       endSurfaceGfPct: Double = 0.0, ttsMin: Double = 0.0, totalDecoMin: Double = 0.0,
                                       ndlMin: Double = Double.infinity, totalRuntimeMin: Double = 0.0,
                                       maxDepthM maxDepthIn: Double? = nil,
                                       ccrBailout: CcrBailoutAnalysis? = nil) -> [PlanAlert] {
        let maxDepthM = maxDepthIn ?? (segments.map { max($0.fromDepthM, $0.toDepthM) }.max() ?? 0.0)
        var list = [PlanAlert]()
        let dive = input.dive
        let alerts = input.alerts
        let general = input.general
        let isCcr = general.planningType == .ccr
        let enabledGases = input.gases.filter { $0.enabled }
        let hasDecoStops = totalDecoMin > 0.0 || segments.contains { $0.type == .stop }

        // 0. Gradient factors
        if general.gfLow > general.gfHigh {
            list.append(PlanAlert(.critical, .GF_EXTREME, "Invalid Gradient Factors: GF Low (\(general.gfLow)%) cannot exceed GF High (\(general.gfHigh)%)."))
        } else if general.gfHigh > 90 || (hasDecoStops && general.gfLow > 60) {
            list.append(PlanAlert(.medium, .GF_EXTREME, "Aggressive Gradient Factors (GF \(general.gfLow)/\(general.gfHigh)): reduced decompression margin."))
        } else if general.gfHigh < 65 || general.gfLow < 15 {
            list.append(PlanAlert(.low, .GF_EXTREME, "Very conservative Gradient Factors (GF \(general.gfLow)/\(general.gfHigh)): significantly increases deco time."))
        }

        // 0b. Surfacing supersaturation
        if endSurfaceGfPct > 100.0 + 0.5 {
            list.append(PlanAlert(.critical, .SURFACE_GF_HIGH, "Surfacing supersaturation (\(kRound(endSurfaceGfPct))% Surf GF) exceeds Bühlmann M-value — omitted decompression / acute DCS hazard."))
        } else if endSurfaceGfPct > 90.0 {
            list.append(PlanAlert(.high, .SURFACE_GF_HIGH, "High surfacing tissue stress (Surf GF \(kRound(endSurfaceGfPct))%) — elevated post-dive DCS risk; lower GF High or extend shallow stops."))
        }

        // 1. Rates
        if dive.ascentRateToFirstStopMMin > 12.0 || dive.ascentRateToSurfaceMMin > 9.0 {
            list.append(PlanAlert(.medium, .ASCENT_FAST, "Fast ascent rate configured (\(kRound(dive.ascentRateToFirstStopMMin)) m/min ascent, \(kRound(dive.ascentRateToSurfaceMMin)) m/min to surface)."))
        }
        if dive.descentRateMMin > 24.0 {
            list.append(PlanAlert(.medium, .DESCENT_FAST, "Fast descent rate (\(kRound(dive.descentRateMMin)) m/min > 24 m/min): increases rapid narcosis onset, equalization barotrauma, and HPNS risk."))
        }

        // 1b. Hypoxic bottom mix coverage
        if !isCcr, let primaryBottom = enabledGases.first(where: { $0.role == .bottom }) ?? enabledGases.first {
            let bottomMinM = GasCalculator.minDepthM(primaryBottom, dive.ppO2Min, env)
            if bottomMinM > 0.0 {
                let surfaceBreathable = enabledGases.filter { $0.id != primaryBottom.id && GasCalculator.minDepthM($0, dive.ppO2Min, env) <= 0.0 }
                if surfaceBreathable.isEmpty {
                    list.append(PlanAlert(.critical, .HYPOXIC_NO_TRAVEL, "Hypoxic bottom mix \(primaryBottom.displayName) (min depth \(kRound(bottomMinM)) m) has no normoxic travel/deco gas breathable at the surface."))
                } else {
                    let deepestBridgeMod = surfaceBreathable.map { $0.modOverrideM ?? GasCalculator.modExactM($0, dive.ppO2Bottom, env) }.max() ?? 0.0
                    if deepestBridgeMod + 1.0 < bottomMinM {
                        list.append(PlanAlert(.critical, .HYPOXIC_NO_TRAVEL, "Gas coverage gap: travel/deco gas MOD (\(kRound(deepestBridgeMod)) m) does not reach hypoxic \(primaryBottom.displayName) min depth (\(kRound(bottomMinM)) m)."))
                    }
                }
            }
        }

        // 1c. CCR checks
        if isCcr {
            if let diluent = enabledGases.first(where: { $0.role == .diluent || $0.role == .bottom }), maxDepthM > 0.0 {
                let dilPpO2Max = UnitsAndPressure.ambientPressure(maxDepthM, env) * diluent.o2
                if dilPpO2Max > 1.60 + 0.03 {
                    list.append(PlanAlert(.critical, .CCR_DILUENT_HAZARD, "CCR diluent \(diluent.displayName) reaches ppO₂ \(fmt(dilPpO2Max, 2)) bar at \(kRound(maxDepthM)) m (> 1.60 bar) — manual loop flush at depth causes acute O₂ toxicity."))
                } else if dilPpO2Max > 1.40 + 0.03 {
                    list.append(PlanAlert(.high, .CCR_DILUENT_HAZARD, "CCR diluent \(diluent.displayName) reaches ppO₂ \(fmt(dilPpO2Max, 2)) bar at \(kRound(maxDepthM)) m (> 1.40 bar) — poor diluent flush margin."))
                }
            }
            let ocBailoutGases = enabledGases.filter { $0.role == .bailout || $0.role == .deco || $0.role == .travel }
            if ocBailoutGases.isEmpty {
                list.append(PlanAlert(.critical, .CCR_BAILOUT_RISK, "CCR plan has no open-circuit bailout or deco gases configured — configure dedicated OC bailout cylinders."))
            } else if let bo = ccrBailout {
                // Worst-case OC bailout from the end of each bottom level (planned by DivePlannerEngine)
                if let d = bo.abortedDepthM {
                    list.append(PlanAlert(.critical, .CCR_BAILOUT_RISK, "Open-circuit bailout from \(kRound(d)) m cannot be completed with the configured gases and limits."))
                }
                if let d = bo.diluentFallbackDepthM {
                    list.append(PlanAlert(.high, .CCR_BAILOUT_RISK, "No bailout gas is breathable at \(kRound(d)) m on open circuit: the bailout plan has to use the diluent. Carry a bottom bailout cylinder."))
                }
                for g in enabledGases {
                    guard let need = bo.requiredById[g.id] else { continue }
                    let have = g.cylinder.availableLitres
                    if need > have + 0.5 {
                        list.append(PlanAlert(.critical, .CCR_BAILOUT_RISK, "Insufficient bailout gas \(g.displayName): worst-case open-circuit bailout (end of bottom phase, stress RMV) needs \(kRound(need)) L, cylinders hold \(kRound(have)) L."))
                    }
                }
            }
        }

        // 1d. HPNS
        let maxPpHe = segments.map(\.ppHeEnd).max() ?? 0.0
        if maxDepthM >= 150.0 {
            list.append(PlanAlert(.critical, .HPNS_RISK, "Extreme depth (\(kRound(maxDepthM)) m, ppHe \(fmt(maxPpHe, 1)) bar): severe High-Pressure Nervous Syndrome (HPNS) and respiratory failure hazard."))
        } else if maxDepthM >= 120.0 && (maxPpHe >= 6.5 || dive.descentRateMMin > 18.0) {
            list.append(PlanAlert(.high, .HPNS_RISK, "Deep helium HPNS risk at \(kRound(maxDepthM)) m (ppHe \(fmt(maxPpHe, 1)) bar): slow descent (≤ 15 m/min) and ensure adequate N₂ narcotic balance."))
        }

        var critPpO2Bottom = false, highPpO2Bottom = false, critPpO2Deco = false, highPpO2Deco = false
        var critHypoxic = false, critMod = false, highMod = false, medSwitchMargin = false
        var critDensity = false, highDensity = false, medDensity = false
        var critEnd = false, highEnd = false, medEnd = false
        var highIcdN2 = false, medIcdFifths = false, lowLipid = false, medPpN2Deco = false
        var continuousHighO2Min = 0.0, maxContinuousHighO2Min = 0.0
        let criticalBottomPpO2 = max(dive.ppO2Bottom, 1.60) + 0.03
        let criticalDecoPpO2 = max(dive.ppO2Deco, 1.60) + 0.03
        var prevGas: Gas? = nil

        for (idx, seg) in segments.enumerated() {
            let maxPpO2Seg = max(seg.ppO2Start, seg.ppO2End)
            let minPpO2Seg = min(seg.ppO2Start, seg.ppO2End)
            let isDecoPhase = seg.type == .stop || seg.type == .switchGas || (seg.type == .ascent && (seg.gas.role == .deco || seg.gas.role == .bailout))
            let maxSegDepth = max(seg.fromDepthM, seg.toDepthM)

            if seg.type == .airBreak || maxPpO2Seg < 1.30 {
                continuousHighO2Min = 0.0
            } else if isDecoPhase && (maxPpO2Seg >= 1.40 || (!seg.isCcr && seg.gas.o2 >= 0.80 && maxSegDepth >= 3.0)) {
                continuousHighO2Min += seg.durationMin
                if continuousHighO2Min > maxContinuousHighO2Min { maxContinuousHighO2Min = continuousHighO2Min }
            }

            if minPpO2Seg < dive.ppO2Min - 0.01 && !critHypoxic {
                critHypoxic = true
                list.append(PlanAlert(.critical, .HYPOXIC, "Hypoxic gas on \(seg.gas.displayName): ppO₂ drops to \(fmt(minPpO2Seg, 2)) bar (< \(fmt(dive.ppO2Min, 2)) bar).", idx))
            }

            if !isDecoPhase {
                if maxPpO2Seg > criticalBottomPpO2 && !critPpO2Bottom {
                    critPpO2Bottom = true
                    list.append(PlanAlert(.critical, .PPO2_HIGH_BOTTOM, "Bottom ppO₂ \(fmt(maxPpO2Seg, 2)) bar on \(seg.gas.displayName) exceeds 1.60 bar acute O₂ toxicity limit.", idx))
                } else if !critPpO2Bottom && maxPpO2Seg > dive.ppO2Bottom + 0.03 && !highPpO2Bottom {
                    highPpO2Bottom = true
                    list.append(PlanAlert(.high, .PPO2_HIGH_BOTTOM, "Bottom ppO₂ \(fmt(maxPpO2Seg, 2)) bar on \(seg.gas.displayName) exceeds your \(fmt(dive.ppO2Bottom, 2)) bar limit.", idx))
                }
            } else {
                if maxPpO2Seg > criticalDecoPpO2 && !critPpO2Deco {
                    critPpO2Deco = true
                    list.append(PlanAlert(.critical, .PPO2_HIGH_DECO, "Deco ppO₂ \(fmt(maxPpO2Seg, 2)) bar on \(seg.gas.displayName) exceeds \(fmt(max(dive.ppO2Deco, 1.60), 2)) bar toxicity limit.", idx))
                } else if !critPpO2Deco && maxPpO2Seg > dive.ppO2Deco + 0.03 && !highPpO2Deco {
                    highPpO2Deco = true
                    list.append(PlanAlert(.high, .PPO2_HIGH_DECO, "Deco ppO₂ \(fmt(maxPpO2Seg, 2)) bar on \(seg.gas.displayName) exceeds your \(fmt(dive.ppO2Deco, 2)) bar deco limit.", idx))
                } else if seg.type == .switchGas && !seg.isCcr && maxSegDepth > 6.5 && maxPpO2Seg > dive.ppO2Deco &&
                            !medSwitchMargin && !critPpO2Deco && !highPpO2Deco {
                    medSwitchMargin = true
                    list.append(PlanAlert(.medium, .SWITCH_MOD_MARGIN, "Tight switch MOD margin on \(seg.gas.displayName) at \(kRound(maxSegDepth)) m (ppO₂ \(fmt(maxPpO2Seg, 2)) bar) — minor buoyancy excursion exceeds \(fmt(dive.ppO2Deco, 2)) bar.", idx))
                }
            }

            if !seg.isCcr {
                let critLimitPp = isDecoPhase ? max(dive.ppO2Deco, 1.60) : max(dive.ppO2Bottom, 1.60)
                let critAllowedMod = isDecoPhase
                    ? GasCalculator.effectiveSwitchModM(seg.gas, critLimitPp, env) + 1.5
                    : (seg.gas.modOverrideM ?? GasCalculator.modExactM(seg.gas, critLimitPp, env)) + 1.5
                let cfgLimitPp = isDecoPhase ? dive.ppO2Deco : dive.ppO2Bottom
                let cfgAllowedMod = isDecoPhase
                    ? GasCalculator.effectiveSwitchModM(seg.gas, cfgLimitPp, env) + 1.0
                    : (seg.gas.modOverrideM ?? GasCalculator.modExactM(seg.gas, cfgLimitPp, env)) + 1.0
                if maxSegDepth > critAllowedMod && !critMod && !critPpO2Bottom && !critPpO2Deco {
                    critMod = true
                    list.append(PlanAlert(.critical, .MOD_EXCEEDED, "\(seg.gas.displayName) breathed at \(kRound(maxSegDepth)) m exceeds critical MOD (\(kRound(critAllowedMod)) m).", idx))
                } else if maxSegDepth > cfgAllowedMod && !critMod && !highMod && !highPpO2Bottom && !highPpO2Deco && !critPpO2Bottom && !critPpO2Deco {
                    highMod = true
                    list.append(PlanAlert(.high, .MOD_EXCEEDED, "\(seg.gas.displayName) breathed at \(kRound(maxSegDepth)) m exceeds configured MOD (\(kRound(cfgAllowedMod)) m).", idx))
                }
            }

            if seg.densityGL > 6.2 + 0.02 && !critDensity {
                critDensity = true
                list.append(PlanAlert(.critical, .DENSITY_HIGH, "Gas density \(fmt(seg.densityGL, 2)) g/L on \(seg.gas.displayName) exceeds 6.2 g/L UHMS maximum limit — severe CO₂ retention & hypercapnia risk.", idx))
            } else if !critDensity && seg.densityGL > dive.maxGasDensityGL + 0.05 && !highDensity {
                highDensity = true
                list.append(PlanAlert(.high, .DENSITY_HIGH, "Gas density \(fmt(seg.densityGL, 2)) g/L on \(seg.gas.displayName) exceeds your \(fmt(dive.maxGasDensityGL, 1)) g/L target.", idx))
            } else if !critDensity && !highDensity && dive.maxGasDensityGL > 5.2 && seg.densityGL > 5.25 && !medDensity {
                medDensity = true
                list.append(PlanAlert(.medium, .DENSITY_HIGH, "Gas density \(fmt(seg.densityGL, 2)) g/L on \(seg.gas.displayName) exceeds 5.2 g/L UHMS ideal work-of-breathing guideline.", idx))
            }

            if !critEnd && (seg.endM > 45.0 || seg.ppN2End > 4.50) {
                critEnd = true
                list.append(PlanAlert(.critical, .END_HIGH, "Severe narcosis on \(seg.gas.displayName): END \(kRound(seg.endM)) m (> 45 m), ppN₂ \(fmt(seg.ppN2End, 2)) bar — helium (Trimix) required.", idx))
            } else if !critEnd && !highEnd && (seg.endM > 40.0 || seg.ppN2End > 4.20) {
                highEnd = true
                list.append(PlanAlert(.high, .END_HIGH, "High narcosis on \(seg.gas.displayName): END \(kRound(seg.endM)) m (> 40 m), ppN₂ \(fmt(seg.ppN2End, 2)) bar — impaired judgment & task-loading risk.", idx))
            } else if !critEnd && !highEnd && !medEnd && (seg.endM > dive.maxEndM + 1.0 || seg.ppN2End > dive.ppN2Max + 0.08) {
                medEnd = true
                list.append(PlanAlert(.medium, .END_HIGH, "Elevated narcosis on \(seg.gas.displayName): END \(kRound(seg.endM)) m (limit \(kRound(dive.maxEndM)) m), ppN₂ \(fmt(seg.ppN2End, 2)) bar.", idx))
            }

            if !seg.isCcr, let previous = prevGas, previous.id != seg.gas.id, previous.he > 0.05 {
                let icd = GasCalculator.evaluateIcd(previous, seg.gas, seg.fromDepthM, env, alerts)
                if icd.violatesDeltaN2 && !highIcdN2 {
                    highIcdN2 = true
                    list.append(PlanAlert(.high, .ICD_N2, "ICD ΔppN₂ jump of +\(fmt(icd.deltaPpN2, 2)) bar switching \(previous.displayName) → \(seg.gas.displayName) at \(kRound(seg.fromDepthM)) m (limit \(fmt(alerts.icdDeltaPpN2Max, 2)) bar).", idx))
                }
                if icd.violatesRuleOfFifths && !medIcdFifths {
                    medIcdFifths = true
                    list.append(PlanAlert(.medium, .ICD_FIFTHS, "ICD Rule of Fifths advisory switching \(previous.displayName) → \(seg.gas.displayName) at \(kRound(seg.fromDepthM)) m.", idx))
                }
            }
            prevGas = seg.gas

            if !seg.isCcr && isDecoPhase && !medPpN2Deco && seg.ppN2End > dive.ppN2MaxDeco + 0.02 {
                medPpN2Deco = true
                list.append(PlanAlert(.medium, .PPN2_HIGH_DECO, "Deco ppN₂ \(fmt(seg.ppN2End, 2)) bar on \(seg.gas.displayName) at \(kRound(maxSegDepth)) m exceeds your PPN2 Max Deco (\(fmt(dive.ppN2MaxDeco, 2)) bar). Consider a richer or helium-based deco gas.", idx))
            }

            if alerts.considerLipidResponse && seg.gas.he > 0.15 && !lowLipid {
                lowLipid = true
                list.append(PlanAlert(.low, .LIPID, "Lipid-tissue helium response enabled: consider slower ascent & extended shallow stops on \(seg.gas.displayName).", idx))
            }
        }

        if maxContinuousHighO2Min > 20.0 + 0.5 {
            list.append(PlanAlert(.high, .NO_O2_BREAKS, "Continuous high-ppO₂ decompression (\(kRound(maxContinuousHighO2Min)) min > 20 min) without an air/backgas break — enable Gas Breaks in Settings to cut CNS seizure risk."))
        }

        // CNS
        let singleDiveCnsHighThreshold = min(80.0, alerts.cnsWarnPct)
        if oxygen.diveCnsPct > 100.0 || oxygen.endCnsPct > alerts.cnsErrorPct {
            let tail = oxygen.startCnsPct > 0.5
                ? " (cumulative \(kRound(oxygen.endCnsPct))%, limit \(kRound(alerts.cnsErrorPct))%)."
                : " (limit \(kRound(alerts.cnsErrorPct))%)."
            list.append(PlanAlert(.critical, .CNS, "CNS oxygen toxicity clock exceeds NOAA limit: single-dive \(kRound(oxygen.diveCnsPct))%" + tail))
        } else if oxygen.diveCnsPct > singleDiveCnsHighThreshold {
            let tail = oxygen.startCnsPct > 0.5 ? " (cumulative end CNS \(kRound(oxygen.endCnsPct))%)." : "."
            list.append(PlanAlert(.high, .CNS, "High CNS oxygen toxicity clock: single-dive CNS reaches \(kRound(oxygen.diveCnsPct))%, exceeding \(kRound(singleDiveCnsHighThreshold))% of the NOAA single-dive limit" + tail))
        } else if oxygen.endCnsPct > alerts.cnsWarnPct {
            list.append(PlanAlert(.high, .CNS, "High cumulative CNS oxygen clock: end-of-dive CNS reaches \(kRound(oxygen.endCnsPct))% (single-dive +\(kRound(oxygen.diveCnsPct))%, threshold \(kRound(alerts.cnsWarnPct))%)."))
        } else if oxygen.diveCnsPct >= 50.0 || oxygen.endCnsPct >= 50.0 {
            list.append(PlanAlert(.low, .CNS, "Moderate CNS exposure (\(kRound(oxygen.endCnsPct))% NOAA clock) — monitor cumulative O₂ across repetitive dives."))
        }

        // OTU
        if oxygen.diveOtu > alerts.otuWarnPerDive || oxygen.dailyOtu > 850.0 {
            list.append(PlanAlert(.high, .OTU, "Pulmonary OTU limit exceeded: dive \(kRound(oxygen.diveOtu)) OTU, daily \(kRound(oxygen.dailyOtu)) OTU (limit \(kRound(alerts.otuWarnPerDive)) OTU)."))
        } else if oxygen.dailyOtu > alerts.otuWarnPerDive {
            list.append(PlanAlert(.medium, .OTU, "Cumulative daily OTU (\(kRound(oxygen.dailyOtu)) OTU) exceeds single-dive guideline (\(kRound(alerts.otuWarnPerDive)) OTU)."))
        }

        // Deco gas strategy
        if !isCcr && hasDecoStops {
            let enabledDecoGases = enabledGases.filter { $0.role == .deco || $0.role == .bailout }
            if enabledDecoGases.isEmpty && (maxDepthM > 40.0 || totalDecoMin >= 25.0) {
                list.append(PlanAlert(.high, .NO_DECO_REDUNDANCY, "Decompression dive (\(kRound(maxDepthM)) m, \(kRound(totalDecoMin)) min deco) has no accelerated deco gas (e.g., EAN50 / O₂) — long backgas deco and zero gas redundancy."))
            } else if maxDepthM >= 50.0 && totalDecoMin >= 25.0 && !enabledDecoGases.isEmpty {
                let hasHighO2 = enabledDecoGases.contains { $0.o2Pct >= 80 }
                let hasMidDeco = enabledDecoGases.contains { (35...79).contains($0.o2Pct) }
                if hasHighO2 && !hasMidDeco {
                    list.append(PlanAlert(.high, .NO_DECO_REDUNDANCY, "Deep deco gap (\(kRound(maxDepthM)) m): jumping directly from bottom mix to high-O₂ without a mid-range deco gas (e.g., EAN50 at 21 m) prolongs deep stops and increases ICD/lost-gas risk."))
                }
            }
        }

        // Repetitive dives
        if input.previousTissues != nil && input.bailoutStartRuntimeMin == nil {
            if let prevDepth = input.previousMaxDepthM, maxDepthM > prevDepth + 6.0 {
                let deltaM = kRound(maxDepthM - prevDepth)
                if hasDecoStops || maxDepthM > prevDepth + 12.0 {
                    list.append(PlanAlert(.high, .REVERSE_PROFILE, "Reverse dive profile (+\(deltaM) m deeper than prior \(kRound(prevDepth)) m dive): elevated repetitive DCS risk — plan deepest dive first."))
                } else {
                    list.append(PlanAlert(.medium, .REVERSE_PROFILE, "Reverse profile advisory: dive is +\(deltaM) m deeper than previous \(kRound(prevDepth)) m dive — extend surface interval or add conservatism."))
                }
            }
            if input.surfaceIntervalMin < 30.0 || oxygen.startCnsPct >= 60.0 {
                list.append(PlanAlert(.high, .SHORT_SURFACE_INTERVAL, "Short surface interval (\(kRound(input.surfaceIntervalMin)) min, start CNS \(kRound(oxygen.startCnsPct))%): high residual inert gas & CNS carry-over."))
            } else if input.surfaceIntervalMin < 60.0 {
                list.append(PlanAlert(.medium, .SHORT_SURFACE_INTERVAL, "Surface interval under 60 min (\(kRound(input.surfaceIntervalMin)) min): significant residual fast/medium tissue N₂ loading remains."))
            }
        }

        // Yo-yo
        let activeLevels = input.levels.filter { $0.depthM > 0.0 && $0.timeMin > 0.0 }
        if activeLevels.count >= 3 {
            var ascendedBy6m = false, hasYoYo = false
            for i in 1..<activeLevels.count {
                let delta = activeLevels[i].depthM - activeLevels[i - 1].depthM
                if delta <= -6.0 { ascendedBy6m = true }
                else if ascendedBy6m && delta >= 6.0 { hasYoYo = true; break }
            }
            if hasYoYo {
                list.append(PlanAlert(.medium, .YOYO_PROFILE, "Sawtooth / yo-yo multi-level profile detected (ascending ≥ 6 m then re-descending ≥ 6 m): increases microbubble expansion/recompression DCS risk."))
            }
        }

        // TTS / thermal
        var coldEscalated = false
        if ttsMin >= 90.0 {
            list.append(PlanAlert(.high, .LONG_TTS_EXPOSURE, "Extended Total Time to Surface (TTS \(kRound(ttsMin)) min ≥ 90 min): high hypothermia, dehydration, and immersion pulmonary edema risk."))
        } else if general.surfaceTempC <= 14 && ttsMin >= 45.0 {
            coldEscalated = true
            list.append(PlanAlert(.high, .LONG_TTS_EXPOSURE, "Cold-water prolonged deco (TTS \(kRound(ttsMin)) min at \(general.surfaceTempC)°C): peripheral vasoconstriction slows off-gassing — add thermal & GF conservatism."))
        } else if ttsMin >= 45.0 || totalRuntimeMin >= 90.0 {
            list.append(PlanAlert(.medium, .LONG_TTS_EXPOSURE, "Long dive exposure (TTS \(kRound(ttsMin)) min, runtime \(kRound(totalRuntimeMin)) min): plan thermal protection and hydration."))
        }
        if !coldEscalated && general.surfaceTempC <= 10 {
            list.append(PlanAlert(.medium, .COLD_WATER, "Cold water environment (\(general.surfaceTempC)°C ≤ 10°C): regulator free-flow risk and reduced peripheral off-gassing — use environmentally sealed cold-water regs."))
        }

        // Altitude
        if general.elevationM >= 300 {
            if general.arrivedUnder12hAtAltitude {
                list.append(PlanAlert(.high, .ALTITUDE_DIVING, "Unacclimatized altitude dive (\(general.elevationM) m, arrived < 12 h): tissues carry residual sea-level N₂ under reduced surface pressure (\(fmt(env.pSurfaceBar, 2)) bar)."))
            } else {
                list.append(PlanAlert(.medium, .ALTITUDE_DIVING, "Altitude diving (\(general.elevationM) m, surface pressure \(fmt(env.pSurfaceBar, 2)) bar): apply slower ascent rates and extended flying/driving-after-diving surface intervals."))
            }
        }

        // Tight NDL margin
        if !hasDecoStops && ndlMin.isFinite && ndlMin > 0.0 && activeLevels.count == 1 {
            let plannedBottomTime = activeLevels[0].timeMin
            let remainingNdl = ndlMin - plannedBottomTime
            if plannedBottomTime >= ndlMin * 0.85 || remainingNdl <= 3.0 {
                if !enabledGases.contains(where: { $0.role == .deco || $0.role == .bailout }) {
                    list.append(PlanAlert(.medium, .TIGHT_NDL_MARGIN, "Tight NDL margin: planned bottom time (\(kRound(plannedBottomTime)) min) is within \(kRound(remainingNdl.coerceAtLeast(0.0))) min of the \(kRound(ndlMin)) min NDL without deco gas."))
                }
            }
        }

        // 6 m last stop on heavy deco
        if hasDecoStops && dive.lastStopM >= 6 && totalDecoMin >= 25.0 && endSurfaceGfPct >= 80.0 {
            list.append(PlanAlert(.medium, .LAST_STOP_6M_GAP, "Final deco stop set to \(dive.lastStopM) m on heavy deco (\(kRound(totalDecoMin)) min, Surf GF \(kRound(endSurfaceGfPct))%): surfacing directly from 6 m creates a steep final pressure drop."))
        }

        // Gas volumes
        for gc in consumption {
            if !gc.isSufficient {
                list.append(PlanAlert(.critical, .GAS_VOLUME, "Insufficient \(gc.gas.displayName): requires \(kRound(gc.totalRequiredLitres)) L (incl. reserve), cylinder holds \(kRound(gc.availableLitres)) L."))
            } else {
                let barMarginAboveReserve = gc.remainingBar - gc.minPressureBar
                if gc.remainingBar < 50.0 || barMarginAboveReserve < 10.0 {
                    list.append(PlanAlert(.medium, .END_PRESSURE_LOW, "Low surfacing pressure on \(gc.gas.displayName): ends at \(kRound(gc.remainingBar)) bar (min reserve \(kRound(gc.minPressureBar)) bar) — narrow contingency margin."))
                } else if barMarginAboveReserve < 15.0 {
                    list.append(PlanAlert(.low, .GAS_ALERT_LIMITS, "Tight gas margin on \(gc.gas.displayName): ends \(kRound(gc.remainingBar)) bar vs \(kRound(gc.minPressureBar)) bar min reserve."))
                }
                let budgetLimit: Double
                let isDecoRole = gc.gas.role == .deco || gc.gas.role == .bailout
                if isDecoRole && gc.gas.o2Pct >= 99 { budgetLimit = alerts.decoO2MaxLitres }
                else if isDecoRole && gc.gas.o2Pct >= 50 { budgetLimit = alerts.decoMix50To99MaxLitres }
                else if isDecoRole { budgetLimit = alerts.decoMixUpTo49MaxLitres }
                else { budgetLimit = alerts.bottomMixMaxLitres }
                if gc.usedLitres > budgetLimit {
                    list.append(PlanAlert(.low, .GAS_ALERT_LIMITS, "\(gc.gas.displayName) uses \(kRound(gc.usedLitres)) L, above your \(kRound(budgetLimit)) L gas budget alert (Settings › Safety Alert Thresholds). Cylinders hold \(kRound(gc.availableLitres)) L, so the plan has enough gas."))
                }
            }
        }

        // Stable sort by severity (Kotlin sortedBy is stable)
        return list.enumerated()
            .sorted { $0.element.level.priority != $1.element.level.priority ? $0.element.level.priority < $1.element.level.priority : $0.offset < $1.offset }
            .map(\.element)
    }
}
