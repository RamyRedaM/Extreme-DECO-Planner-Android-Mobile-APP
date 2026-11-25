import Foundation

/// MOD, END, EAD, density, Best GAS Mix, ICD and gas selection (mirrors GasCalculator.kt).
public enum GasCalculator {

    public static func modExactM(_ gas: Gas, _ ppO2Max: Double, _ env: Environment) -> Double {
        if gas.o2 <= 0.0 { return 0.0 }
        let pMax = ppO2Max / gas.o2
        return UnitsAndPressure.depthFromPressure(pMax, env).coerceAtLeast(0.0)
    }

    public static func modDisplayM(_ gas: Gas, _ ppO2Max: Double, _ env: Environment) -> Double {
        modExactM(gas, ppO2Max, env).rounded(.down)
    }

    public static func effectiveSwitchModM(_ gas: Gas, _ ppO2Deco: Double, _ env: Environment) -> Double {
        if let o = gas.modOverrideM { return o }
        let exact = modExactM(gas, ppO2Deco, env)
        if gas.o2 >= 0.99 && ppO2Deco >= 1.58 && exact >= 5.5 { return 6.0 }
        return exact.rounded(.down)
    }

    public static func minDepthM(_ gas: Gas, _ ppO2Min: Double, _ env: Environment) -> Double {
        if gas.o2 <= 0.0 { return Double.infinity }
        let pMin = ppO2Min / gas.o2
        let d = UnitsAndPressure.depthFromPressure(pMin, env)
        return d <= 0.0 ? 0.0 : d.rounded(.up)
    }

    public static func endM(_ gas: Gas, _ depthM: Double, _ env: Environment, _ o2Narcotic: Bool) -> Double {
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        let pNarc = o2Narcotic ? (gas.n2 + gas.o2) * pAmb : gas.n2 * pAmb / Constants.FN2_AIR
        return UnitsAndPressure.depthFromPressure(pNarc, env).coerceAtLeast(0.0)
    }

    public static func eadM(_ gas: Gas, _ depthM: Double, _ env: Environment) -> Double {
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        return UnitsAndPressure.depthFromPressure(gas.n2 * pAmb / Constants.FN2_AIR, env).coerceAtLeast(0.0)
    }

    public static func densityGL(_ gas: Gas, _ depthM: Double, _ env: Environment) -> Double {
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        let d1Atm = gas.o2 * Constants.DENSITY_O2 + gas.n2 * Constants.DENSITY_N2 + gas.he * Constants.DENSITY_HE
        return d1Atm * pAmb / Constants.P_SURFACE_SEA
    }

    public static func gasMetrics(_ gas: Gas, _ depthM: Double, _ env: Environment, _ dive: DiveSettings) -> GasMetrics {
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        let ppO2Limit = (gas.role == .deco || gas.role == .bailout) ? dive.ppO2Deco : dive.ppO2Bottom
        let exactMod = modExactM(gas, ppO2Limit, env)
        return GasMetrics(gas: gas, depthM: depthM, ppO2: gas.o2 * pAmb, ppN2: gas.n2 * pAmb, ppHe: gas.he * pAmb,
                          endM: endM(gas, depthM, env, dive.o2Narcotic), eadM: eadM(gas, depthM, env),
                          densityGL: densityGL(gas, depthM, env), modM: gas.modOverrideM ?? exactMod.rounded(.down),
                          modExactM: exactMod, minDepthM: minDepthM(gas, dive.ppO2Min, env))
    }

    static func resolveCylinder(_ slotId: String, _ role: GasRole, _ defaultCount: Int, _ existingGases: [Gas]) -> CylinderConfig {
        if let exact = existingGases.first(where: { $0.id == slotId })?.cylinder { return exact }
        let byRole: CylinderConfig?
        switch role {
        case .bottom, .diluent: byRole = existingGases.first(where: { $0.role == .bottom || $0.role == .diluent })?.cylinder
        case .travel: byRole = existingGases.first(where: { $0.role == .travel })?.cylinder
        default: byRole = nil
        }
        return byRole ?? CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: defaultCount)
    }

    static func round2(_ x: Double) -> Double { Double(kRound(x * 100.0)) / 100.0 }

    static func computeOptimalMixAtDepth(id: String, targetDepthM: Double, ppO2Limit: Double, ppN2Limit: Double, role: GasRole,
                                         general: GeneralSettings, dive: DiveSettings, env: Environment,
                                         minO2: Double, maxO2: Double, cylinder: CylinderConfig, modOverrideM: Double? = nil) -> Gas {
        let depth = targetDepthM.coerceAtLeast(3.0)
        let pAmb = UnitsAndPressure.ambientPressure(depth, env)
        var fO2 = ((ppO2Limit / pAmb) * 100.0).rounded(.down) / 100.0
        fO2 = fO2.coerceIn(minO2, maxO2)
        let pEndMax = UnitsAndPressure.ambientPressure(dive.maxEndM, env)
        let fHeForEnd: Double
        if dive.o2Narcotic {
            fHeForEnd = max(0.0, 1.0 - pEndMax / pAmb)
        } else {
            fHeForEnd = max(0.0, 1.0 - fO2 - Constants.FN2_AIR * pEndMax / pAmb)
        }
        let fHeForPpN2 = max(0.0, 1.0 - fO2 - ppN2Limit / pAmb)
        var fHe = max(fHeForEnd, fHeForPpN2)
        let step = general.standardMixesOnly ? 5.0 : 1.0
        fHe = ((fHe * 100.0) / step).rounded(.up) * step / 100.0
        if fO2 + fHe > 1.0 { fHe = (1.0 - fO2).coerceAtLeast(0.0) }
        var candidate = Gas(id: id, o2: round2(fO2), he: round2(fHe), role: role, modOverrideM: modOverrideM, cylinder: cylinder)
        while densityGL(candidate, depth, env) > dive.maxGasDensityGL && candidate.n2 >= 0.01 {
            let nextHe = (candidate.he + 0.01).coerceAtMost(1.0 - candidate.o2)
            candidate.he = round2(nextHe)
        }
        return practicalHelium(candidate, depth, ppN2Limit, dive, env)
    }

    /// Depth used for a gas card's END/density: deco at switch depth, travel at its MOD (capped), bottom at max depth.
    public static func displayEvalDepthM(_ gas: Gas, _ maxDepthM: Double, _ dive: DiveSettings, _ env: Environment) -> Double {
        switch gas.role {
        case .deco, .bailout: return effectiveSwitchModM(gas, dive.ppO2Deco, env)
        case .travel: return min(maxDepthM, gas.modOverrideM ?? modDisplayM(gas, dive.ppO2Bottom, env)).coerceAtLeast(0.0)
        default: return maxDepthM
        }
    }

    /// Next-dive gas for the Surface Interval Calculator: the next dive's own bottom gas, else EAN32 if within limits, else Air.
    public static func defaultNextDiveGas(_ plannedNextDiveGases: [Gas]?, _ nextDepthM: Double, _ dive: DiveSettings, _ env: Environment) -> Gas {
        if let g = plannedNextDiveGases?.first(where: { $0.enabled && ($0.role == .bottom || $0.role == .diluent) }) { return g }
        let ean32 = Gas.fromPercent("si_ean32", 32, 0, .bottom)
        return modExactM(ean32, dive.ppO2Bottom, env) >= nextDepthM ? ean32 : Gas.fromPercent("si_air", 21, 0, .bottom)
    }

    public static let MIN_PRACTICAL_HE = 0.10

    /// Helium between 0% and 10% becomes plain nitrox when limits allow, otherwise 10%.
    public static func practicalHelium(_ gas: Gas, _ depthM: Double, _ ppN2Limit: Double, _ dive: DiveSettings, _ env: Environment) -> Gas {
        if gas.he <= 0.0 || gas.he >= MIN_PRACTICAL_HE - 1e-9 { return gas }
        var nitrox = gas
        nitrox.he = 0.0
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        let nitroxOk = endM(nitrox, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 1e-6 &&
            nitrox.n2 * pAmb <= ppN2Limit + 1e-6 &&
            densityGL(nitrox, depthM, env) <= dive.maxGasDensityGL + 1e-6
        if nitroxOk { return nitrox }
        var raised = gas
        raised.he = round2(MIN_PRACTICAL_HE.coerceAtMost(1.0 - gas.o2))
        return raised
    }

    static func computeRecreationalMix(_ maxDepthM: Double, _ general: GeneralSettings, _ dive: DiveSettings,
                                       _ env: Environment, _ existingGases: [Gas]) -> Gas {
        let depth = maxDepthM.coerceAtLeast(3.0)
        let pAmb = UnitsAndPressure.ambientPressure(depth, env)
        let recCyl = resolveCylinder("best_bottom", .bottom, 1, existingGases)
        let maxSafeO2Pct = Int(((dive.ppO2Bottom / pAmb) * 100.0).rounded(.down)).coerceIn(21, 32)
        let chosenO2Pct: Int
        if maxSafeO2Pct >= 32 { chosenO2Pct = 32 }
        else if general.standardMixesOnly { chosenO2Pct = [32, 28, 21].first(where: { $0 <= maxSafeO2Pct }) ?? 21 }
        else { chosenO2Pct = maxSafeO2Pct }
        return Gas.fromPercent("best_bottom", chosenO2Pct, 0, .bottom, cylinder: recCyl)
    }

    public static func recommendBestMix(maxDepthM: Double, general: GeneralSettings, dive: DiveSettings,
                                        existingGases: [Gas] = [], alerts: AlertSettings = AlertSettings(),
                                        levels: [DiveLevel] = [], previousTissues: TissueState? = nil,
                                        surfaceIntervalMin: Double = 0.0,
                                        consumption: ConsumptionSettings = ConsumptionSettings()) -> MixRecommendation {
        let env = UnitsAndPressure.buildEnvironment(general)
        var notes = [String]()
        let validLevels = levels.filter { $0.depthM > 0.0 && $0.timeMin > 0.0 }
        let depth = (validLevels.map(\.depthM).max() ?? maxDepthM).coerceAtLeast(3.0)

        // REC Recreational Dive: under 40 m and within NDL
        if general.planningType == .openCircuit && depth < 40.0 && !validLevels.isEmpty {
            let recGas = computeRecreationalMix(depth, general, dive, env, existingGases)
            var probeInput = DivePlanInput()
            probeInput.general = general
            probeInput.general.gasSelectionMode = .userInputGas
            probeInput.dive = dive
            probeInput.consumption = consumption
            probeInput.alerts = alerts
            probeInput.gases = [recGas]
            probeInput.levels = validLevels.map { var l = $0; l.gasId = nil; return l }
            probeInput.previousTissues = previousTissues
            probeInput.surfaceIntervalMin = surfaceIntervalMin
            let probe = DivePlannerEngine.planDive(probeInput, includeContingencies: false, recordTimeline: false)
            if probe.abortedReason == nil && probe.firstStopM == 0 && probe.totalDecoMin <= 1e-6 {
                let m = gasMetrics(recGas, depth, env, dive)
                return MixRecommendation(
                    bottomGas: recGas, travelGas: nil, travelGasDisplay: recGas,
                    travelRangeNote: "0 m → \(kRound(depth)) m (single REC tank)",
                    deco6mO2: nil, deco21mEan50: nil, deepDecoGas: nil, decoGases: [], plannerGases: [recGas],
                    modM: m.modM, minDepthM: m.minDepthM, endAtMaxDepthM: m.endM, densityAtMaxDepthGL: m.densityGL,
                    ppO2AtMaxDepth: m.ppO2,
                    notes: ["REC Recreational Dive (< 40m, within NDL): \(recGas.displayName) selected (Air / Nitrox only — no DECO bottles or Trimix)."],
                    isRecreational: true)
            }
        }

        if general.planningType == .ccr {
            return recommendCcrBestMix(depth, general, dive, env, existingGases, alerts)
        }

        let bottomRole: GasRole = general.planningType == .ccr ? .diluent : .bottom
        let bottomCyl = resolveCylinder("best_bottom", bottomRole, 2, existingGases)

        var bottomGas = computeOptimalMixAtDepth(id: "best_bottom", targetDepthM: depth, ppO2Limit: dive.ppO2Bottom,
                                                 ppN2Limit: dive.ppN2Max, role: bottomRole, general: general, dive: dive,
                                                 env: env, minO2: 0.08, maxO2: 0.40, cylinder: bottomCyl)
        if densityGL(bottomGas, depth, env) > dive.maxGasDensityGL {
            notes.append("Gas density exceeds \(dive.maxGasDensityGL) g/L at \(kRound(depth)) m; reduce depth or increase helium.")
        }
        if general.standardMixesOnly {
            let snapped = Constants.STANDARD_BOTTOM_MIXES
                .map { Gas.fromPercent("best_bottom", $0.0, $0.1, bottomRole, cylinder: bottomCyl) }
                .filter { g in
                    g.o2 <= bottomGas.o2 + 0.005 &&
                        endM(g, depth, env, dive.o2Narcotic) <= dive.maxEndM + 1.0 &&
                        densityGL(g, depth, env) <= dive.maxGasDensityGL + 0.1
                }
                .max { $0.o2 < $1.o2 }
            if let s = snapped {
                bottomGas = s
                notes.append("Snapped bottom MOD mix to standard fill \(bottomGas.displayName).")
            }
        }

        // DECO @ 6 m
        let deco6mCyl = resolveCylinder("best_deco_6m_o2", .deco, 1, existingGases)
        let deco6mO2: Gas
        if dive.ppO2Deco >= 1.58 {
            deco6mO2 = Gas.fromPercent("best_deco_6m_o2", 100, 0, .deco, modOverrideM: 6.0, cylinder: deco6mCyl)
        } else {
            let p6 = UnitsAndPressure.ambientPressure(6.0, env)
            let optO2 = Int(((dive.ppO2Deco / p6) * 100.0).rounded(.down)).coerceIn(50, 100)
            deco6mO2 = Gas.fromPercent("best_deco_6m_o2", optO2, 0, .deco, modOverrideM: 6.0, cylinder: deco6mCyl)
        }

        // DECO @ 21 m
        let deco21mCyl = resolveCylinder("best_deco_21m_ean50", .deco, 1, existingGases)
        let deco21mEan50: Gas
        if dive.ppO2Deco >= 1.56 {
            deco21mEan50 = Gas.fromPercent("best_deco_21m_ean50", 50, 0, .deco, modOverrideM: 21.0, cylinder: deco21mCyl)
        } else {
            let p21 = UnitsAndPressure.ambientPressure(21.0, env)
            let optO2 = Int(((dive.ppO2Deco / p21) * 100.0).rounded(.down)).coerceIn(32, 50)
            deco21mEan50 = Gas.fromPercent("best_deco_21m_ean50", optO2, 0, .deco, modOverrideM: 21.0, cylinder: deco21mCyl)
        }

        // Deep DECO stage (> 54 m with helium bottom mix, ICD-protected)
        let deepDecoCyl = resolveCylinder("best_deco_deep", .deco, 1, existingGases)
        var deepDecoGas: Gas? = nil
        if depth > 54.0 && bottomGas.hePct >= 20 {
            let deepSwitchM = ((min(39.0, depth - 15.0) / 3.0).rounded(.down) * 3.0).coerceIn(27.0, 39.0)
            var deepCand = computeOptimalMixAtDepth(id: "best_deco_deep", targetDepthM: deepSwitchM, ppO2Limit: dive.ppO2Deco,
                                                    ppN2Limit: dive.ppN2MaxDeco, role: .deco, general: general, dive: dive, env: env,
                                                    minO2: (bottomGas.o2 + 0.08).coerceAtLeast(0.30), maxO2: 0.45,
                                                    cylinder: deepDecoCyl, modOverrideM: deepSwitchM)
            var icdGuard = 0
            while icdGuard < 40 && deepCand.n2 >= 0.02 {
                icdGuard += 1
                let icd = evaluateIcd(bottomGas, deepCand, deepSwitchM, env, alerts)
                if !icd.violatesDeltaN2 && !icd.violatesRuleOfFifths { break }
                deepCand.he = round2((deepCand.he + 0.01).coerceAtMost(1.0 - deepCand.o2))
            }
            deepDecoGas = deepCand
        }

        // Travel gas
        let travelCyl = resolveCylinder("best_travel", .travel, 1, existingGases)
        let minDepth = minDepthM(bottomGas, dive.ppO2Min, env)
        var hypoxicTravelGas: Gas? = nil
        let travelGasForDepth: Gas
        let travelRangeNote: String

        if minDepth > 0.0 {
            let targetSwitchM = ((max(minDepth + 9.0, min(54.0, depth * 0.62)) / 3.0).rounded(.down) * 3.0)
                .coerceIn(max(18.0, minDepth + 3.0), (depth - 6.0).coerceAtLeast(18.0))
            var computedTravel = computeOptimalMixAtDepth(id: "best_travel", targetDepthM: targetSwitchM, ppO2Limit: dive.ppO2Bottom,
                                                          ppN2Limit: dive.ppN2Max, role: .travel, general: general, dive: dive,
                                                          env: env, minO2: 0.19, maxO2: 0.36, cylinder: travelCyl)
            if bottomGas.hePct >= 30 && computedTravel.hePct < 15 {
                var guardCount = 0
                while guardCount < 35 && computedTravel.n2 >= 0.05 {
                    guardCount += 1
                    let icd = evaluateIcd(bottomGas, computedTravel, targetSwitchM, env, alerts)
                    if !icd.violatesDeltaN2 { break }
                    computedTravel.he = round2((computedTravel.he + 0.01).coerceAtMost(1.0 - computedTravel.o2))
                }
            }
            hypoxicTravelGas = computedTravel
            travelGasForDepth = computedTravel
            let swDepth = kRound(min(depth - 3.0, modDisplayM(computedTravel, dive.ppO2Bottom, env)))
            travelRangeNote = "0 m → \(swDepth) m (hypoxic bottom switch)"
            notes.append("Bottom MOD mix \(bottomGas.displayName) is hypoxic above \(kRound(minDepth)) m — Travel \(computedTravel.displayName) active 0→\(swDepth) m.")
        } else if depth > 33.0 {
            let targetSwitchM: Double = depth > 54.0
                ? (((depth * 0.65).coerceIn(33.0, 48.0) / 3.0).rounded(.down) * 3.0)
                : ((min(30.0, depth - 9.0).coerceAtLeast(21.0) / 3.0).rounded(.down) * 3.0)
            let computedTravel = computeOptimalMixAtDepth(id: "best_travel", targetDepthM: targetSwitchM, ppO2Limit: dive.ppO2Bottom,
                                                          ppN2Limit: dive.ppN2Max, role: .travel, general: general, dive: dive,
                                                          env: env, minO2: max(0.21, bottomGas.o2 + 0.04).coerceAtMost(0.36),
                                                          maxO2: 0.36, cylinder: travelCyl)
            travelGasForDepth = computedTravel
            let swDepth = kRound(min(depth - 3.0, modDisplayM(computedTravel, dive.ppO2Bottom, env)))
            travelRangeNote = "0 m → \(swDepth) m (optimal descent travel)"
        } else {
            var same = bottomGas
            same.id = "best_travel_same"
            same.role = .travel
            same.cylinder = travelCyl
            travelGasForDepth = same
            travelRangeNote = "0 m → \(kRound(depth)) m (bottom mix breathable from surface)"
        }

        var decoGases = [Gas]()
        if let d = deepDecoGas { decoGases.append(d) }
        if depth > 21.0 || bottomGas.o2Pct < deco21mEan50.o2Pct - 5 { decoGases.append(deco21mEan50) }
        if depth > 6.0 { decoGases.append(deco6mO2) }

        let deepNote = deepDecoGas.map { " · Deep Deco \($0.displayName) @ \($0.modOverrideM.map { kRound($0) } ?? 36)m" } ?? ""
        notes.append("Best GAS Kit (12L default): 6m \(deco6mO2.displayName) · 21m \(deco21mEan50.displayName)\(deepNote) · Travel \(travelGasForDepth.displayName) · MOD \(bottomGas.displayName).")

        var plannerGases = [Gas]()
        if hypoxicTravelGas != nil || (depth > 33.0 && travelGasForDepth.o2 > bottomGas.o2 + 0.02) {
            plannerGases.append(travelGasForDepth)
        }
        plannerGases.append(bottomGas)
        plannerGases.append(contentsOf: decoGases)

        let metrics = gasMetrics(bottomGas, depth, env, dive)
        return MixRecommendation(bottomGas: bottomGas, travelGas: hypoxicTravelGas, travelGasDisplay: travelGasForDepth,
                                 travelRangeNote: travelRangeNote, deco6mO2: deco6mO2, deco21mEan50: deco21mEan50,
                                 deepDecoGas: deepDecoGas, decoGases: decoGases, plannerGases: plannerGases,
                                 modM: metrics.modM, minDepthM: metrics.minDepthM, endAtMaxDepthM: metrics.endM,
                                 densityAtMaxDepthGL: metrics.densityGL, ppO2AtMaxDepth: metrics.ppO2, notes: notes)
    }

    static func cylinderFor(_ slotId: String, _ role: GasRole, _ existingGases: [Gas], _ def: CylinderConfig) -> CylinderConfig {
        existingGases.first { $0.id == slotId }?.cylinder ?? existingGases.first { $0.role == role }?.cylinder ?? def
    }

    /// Snaps a mix to the richest standard fill that still meets END and density at depth (Standard mixes only).
    static func snapToStandard(_ gas: Gas, _ depthM: Double, _ env: Environment, _ dive: DiveSettings) -> Gas? {
        Constants.STANDARD_BOTTOM_MIXES
            .map { Gas.fromPercent(gas.id, $0.0, $0.1, gas.role, enabled: gas.enabled, modOverrideM: gas.modOverrideM, cylinder: gas.cylinder) }
            .filter { g in
                g.o2 <= gas.o2 + 0.005 &&
                    endM(g, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 1.0 &&
                    densityGL(g, depthM, env) <= dive.maxGasDensityGL + 0.1
            }
            .max { $0.o2 < $1.o2 }
    }

    /// CCR Best GAS Mix (mirrors GasCalculator.kt recommendCcrBestMix).
    static func recommendCcrBestMix(_ depth: Double, _ general: GeneralSettings, _ dive: DiveSettings, _ env: Environment,
                                    _ existingGases: [Gas], _ alerts: AlertSettings) -> MixRecommendation {
        var notes = [String]()
        let pMax = UnitsAndPressure.ambientPressure(depth, env)

        // 1. Diluent
        let dilCyl = cylinderFor("best_diluent", .diluent, existingGases, CylinderConfig(waterVolumeL: 3.0, fillPressureBar: 200.0, count: 1))
        var diluent = computeOptimalMixAtDepth(id: "best_diluent", targetDepthM: depth, ppO2Limit: Constants.CCR_DILUENT_PPO2,
                                               ppN2Limit: dive.ppN2Max, role: .diluent, general: general, dive: dive, env: env,
                                               minO2: 0.05, maxO2: 0.21, cylinder: dilCyl)
        if general.standardMixesOnly, let snapped = snapToStandard(diluent, depth, env, dive) { diluent = snapped }
        if densityGL(diluent, depth, env) > dive.maxGasDensityGL {
            notes.append("Diluent density exceeds \(dive.maxGasDensityGL) g/L at \(kRound(depth)) m; reduce depth or increase helium.")
        }

        // 2. Bottom bailout (open circuit at the maximum depth)
        let bbCyl = cylinderFor("best_bailout_bottom", .bailout, existingGases.filter { $0.id == "best_bailout_bottom" },
                                CylinderConfig(waterVolumeL: 11.0, fillPressureBar: 200.0, count: 1))
        var bottomBailout = computeOptimalMixAtDepth(id: "best_bailout_bottom", targetDepthM: depth, ppO2Limit: dive.ppO2Bottom,
                                                     ppN2Limit: dive.ppN2Max, role: .bailout, general: general, dive: dive, env: env,
                                                     minO2: 0.08, maxO2: 0.40, cylinder: bbCyl)
        if general.standardMixesOnly, let snapped = snapToStandard(bottomBailout, depth, env, dive) { bottomBailout = snapped }

        // 3. Deep bailout stage (> 54 m with helium), ICD-checked against the bottom bailout
        var deepBailout: Gas? = nil
        if depth > 54.0 && bottomBailout.hePct >= 20 {
            let deepSwitchM = ((min(39.0, depth - 15.0) / 3.0).rounded(.down) * 3.0).coerceIn(27.0, 39.0)
            var cand = computeOptimalMixAtDepth(
                id: "best_bailout_deep", targetDepthM: deepSwitchM, ppO2Limit: dive.ppO2Deco, ppN2Limit: dive.ppN2MaxDeco,
                role: .bailout, general: general, dive: dive, env: env,
                minO2: (bottomBailout.o2 + 0.08).coerceAtLeast(0.30), maxO2: 0.45,
                cylinder: cylinderFor("best_bailout_deep", .bailout, existingGases.filter { $0.id == "best_bailout_deep" },
                                      CylinderConfig(waterVolumeL: 11.0, fillPressureBar: 200.0, count: 1)),
                modOverrideM: deepSwitchM)
            var guardCount = 0
            while guardCount < 40 && cand.n2 >= 0.02 {
                guardCount += 1
                let icd = evaluateIcd(bottomBailout, cand, deepSwitchM, env, alerts)
                if !icd.violatesDeltaN2 && !icd.violatesRuleOfFifths { break }
                let nextHe = (cand.he + 0.01).coerceAtMost(1.0 - cand.o2)
                cand.he = round2(nextHe)
            }
            deepBailout = cand
        }

        // 4. Shallow bailout stages (same switch rules as the OC deco kit)
        func shallowStage(_ id: String, _ switchM: Double, _ standardPct: Int, _ standardMinPpO2: Double, _ lowPct: Int, _ highPct: Int) -> Gas {
            let pct: Int
            if dive.ppO2Deco >= standardMinPpO2 {
                pct = standardPct
            } else {
                pct = Int(((dive.ppO2Deco / UnitsAndPressure.ambientPressure(switchM, env)) * 100.0).rounded(.down)).coerceIn(lowPct, highPct)
            }
            return Gas.fromPercent(id, pct, 0, .bailout, modOverrideM: switchM,
                                   cylinder: cylinderFor(id, .bailout, existingGases.filter { $0.id == id },
                                                         CylinderConfig(waterVolumeL: 11.0, fillPressureBar: 200.0, count: 1)))
        }
        let bailout21 = shallowStage("best_bailout_21m", 21.0, 50, 1.56, 32, 50)
        let bailout6 = shallowStage("best_bailout_6m", 6.0, 100, 1.58, 50, 100)

        var stages = [Gas]()
        if let d = deepBailout { stages.append(d) }
        if depth > 21.0 && bottomBailout.o2Pct < bailout21.o2Pct { stages.append(bailout21) }
        if depth > 6.0 && bottomBailout.o2Pct < bailout6.o2Pct { stages.append(bailout6) }

        let dilPpO2 = pMax * diluent.o2
        notes.append("CCR Best GAS Mix: diluent \(diluent.displayName) (ppO₂ \(fmt(dilPpO2, 2)) bar at \(kRound(depth)) m) · " +
                     "bailout \(bottomBailout.displayName)" + stages.map { " · \($0.displayName) @ \(kRound($0.modOverrideM ?? 0)) m" }.joined() + ".")
        notes.append("Bailout cylinders are sized from the worst-case open-circuit bailout (end of the bottom phase, stress RMV).")

        let m = gasMetrics(diluent, depth, env, dive)
        return MixRecommendation(
            bottomGas: diluent, travelGas: nil, travelGasDisplay: bottomBailout,
            travelRangeNote: "OC bailout at \(kRound(depth)) m",
            deco6mO2: stages.contains(bailout6) ? bailout6 : nil,
            deco21mEan50: stages.contains(bailout21) ? bailout21 : nil,
            deepDecoGas: deepBailout, decoGases: stages, plannerGases: [diluent, bottomBailout] + stages,
            modM: m.modM, minDepthM: m.minDepthM, endAtMaxDepthM: m.endM, densityAtMaxDepthGL: m.densityGL,
            ppO2AtMaxDepth: dilPpO2, notes: notes, isRecreational: false, bailoutBottomGas: bottomBailout)
    }

    public struct IcdEvaluation: Hashable {
        public let deltaPpN2: Double
        public let deltaPpHe: Double
        public let violatesDeltaN2: Bool
        public let violatesRuleOfFifths: Bool
    }

    public static func evaluateIcd(_ fromGas: Gas, _ toGas: Gas, _ depthM: Double, _ env: Environment, _ alerts: AlertSettings) -> IcdEvaluation {
        let pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        let deltaFN2 = toGas.n2 - fromGas.n2
        let deltaFHe = fromGas.he - toGas.he
        let violatesDeltaN2 = deltaFN2 * pAmb > alerts.icdDeltaPpN2Max + 1e-6
        let violatesFifths = alerts.icdRuleOfFifths && deltaFHe > 0.005 && deltaFN2 > (deltaFHe / 5.0) + 1e-6
        return IcdEvaluation(deltaPpN2: deltaFN2 * pAmb, deltaPpHe: deltaFHe * pAmb,
                             violatesDeltaN2: violatesDeltaN2, violatesRuleOfFifths: violatesFifths)
    }

    /// Kotlin `maxWithOrNull(compareBy...)`: first maximum by lexicographic keys.
    static func firstMax<T>(_ items: [T], _ isLess: (T, T) -> Bool) -> T? {
        guard var best = items.first else { return nil }
        for e in items.dropFirst() where isLess(best, e) { best = e }
        return best
    }

    public static func bestWorkingGasAt(_ depthM: Double, _ fallbackGas: Gas, _ availableGases: [Gas], _ env: Environment, _ dive: DiveSettings) -> Gas {
        let safe = availableGases.filter { $0.enabled && ($0.role == .bottom || $0.role == .travel || $0.role == .diluent) }
            .filter { g in
                depthM >= minDepthM(g, dive.ppO2Min, env) - 1e-6 &&
                    depthM <= modDisplayM(g, dive.ppO2Bottom, env) + 1e-6 &&
                    endM(g, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 3.0
            }
        return firstMax(safe) { a, b in a.o2 != b.o2 ? a.o2 < b.o2 : a.he < b.he } ?? fallbackGas
    }

    public static func bestGasAt(_ depthM: Double, _ currentGas: Gas, _ availableGases: [Gas], _ env: Environment, _ dive: DiveSettings) -> Gas {
        let enabled = availableGases.filter { $0.enabled }
        let currentSafe = depthM >= minDepthM(currentGas, dive.ppO2Min, env) - 1e-6
        let candidates = enabled.filter { g in
            let minD = minDepthM(g, dive.ppO2Min, env)
            let isDeco = g.role == .deco || g.role == .bailout
            let maxD = isDeco ? effectiveSwitchModM(g, dive.ppO2Deco, env)
                : effectiveSwitchModM(g, !currentSafe ? dive.ppO2Deco : dive.ppO2Bottom, env)
            let depthOk = depthM >= minD - 1e-6 && depthM <= maxD + 1e-6
            let endOk = isDeco || !currentSafe || endM(g, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 3.0
            return depthOk && endOk
        }
        if candidates.isEmpty { return currentGas }
        func decoRank(_ g: Gas) -> Int { (g.role == .deco || g.role == .bailout) ? 1 : 0 }
        let best = firstMax(candidates) { a, b in
            if a.o2 != b.o2 { return a.o2 < b.o2 }
            if decoRank(a) != decoRank(b) { return decoRank(a) < decoRank(b) }
            return a.he < b.he
        } ?? currentGas
        return (!currentSafe || best.o2 > currentGas.o2 + 1e-6) ? best : currentGas
    }
}
