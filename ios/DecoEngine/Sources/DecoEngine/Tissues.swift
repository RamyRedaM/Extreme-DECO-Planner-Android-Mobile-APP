import Foundation

/// Haldane / Schreiner tissue loading, Bühlmann M-values, gradient factors, ceilings, NDL and the
/// Surface Interval Calculator (mirrors TissuesAndBuhlmann.kt).
public enum TissuesAndBuhlmann {
    static let LN2 = log(2.0)

    public static func initTissues(_ env: Environment, arrivedUnder12hAtAltitude: Bool = false) -> TissueState {
        let pSurf = arrivedUnder12hAtAltitude ? Constants.P_SURFACE_SEA : env.pSurfaceBar
        let pN2 = (pSurf - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * Constants.FN2_AIR
        return TissueState(Array(repeating: Tissue(pN2: pN2, pHe: 0.0), count: 16))
    }

    public static func haldane(_ p0: Double, _ pInspired: Double, _ halfTimeMin: Double, _ durationMin: Double) -> Double {
        if durationMin <= 0.0 { return p0 }
        let k = LN2 / halfTimeMin
        return p0 + (pInspired - p0) * (1.0 - exp(-k * durationMin))
    }

    public static func schreiner(_ p0: Double, _ pInspiredStart: Double, _ rateBarPerMin: Double,
                                 _ halfTimeMin: Double, _ durationMin: Double) -> Double {
        if durationMin <= 0.0 { return p0 }
        let k = LN2 / halfTimeMin
        return pInspiredStart + rateBarPerMin * (durationMin - 1.0 / k) - (pInspiredStart - p0 - rateBarPerMin / k) * exp(-k * durationMin)
    }

    public static func advanceTissues(_ tissues: TissueState, _ fromDepthM: Double, _ toDepthM: Double, _ durationMin: Double,
                                      _ gas: Gas, _ env: Environment, _ coeffs: [CompartmentCoeff],
                                      isCcr: Bool = false, setpoint: Double? = nil) -> TissueState {
        if durationMin <= 0.0 { return tissues }
        if !isCcr || setpoint == nil {
            let pStart = UnitsAndPressure.ambientPressure(fromDepthM, env)
            let pEnd = UnitsAndPressure.ambientPressure(toDepthM, env)
            let dPdt = (pEnd - pStart) / durationMin
            let pi0N2 = (pStart - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * gas.n2
            let pi0He = (pStart - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * gas.he
            let rN2 = dPdt * gas.n2
            let rHe = dPdt * gas.he
            var updated = [Tissue]()
            updated.reserveCapacity(16)
            for i in 0..<16 {
                let c = coeffs[i]
                let t = tissues.compartments[i]
                let nextN2 = schreiner(t.pN2, pi0N2, rN2, c.n2HalfTime, durationMin).coerceAtLeast(0.0)
                let nextHe = schreiner(t.pHe, pi0He, rHe, c.heHalfTime, durationMin).coerceAtLeast(0.0)
                updated.append(Tissue(pN2: nextN2, pHe: nextHe))
            }
            return TissueState(updated)
        } else {
            let deltaD = abs(toDepthM - fromDepthM)
            let steps = max(1, Int(deltaD.rounded(.up)))
            let dt = durationMin / Double(steps)
            var current = tissues
            for s in 0..<steps {
                let fracMid = (Double(s) + 0.5) / Double(steps)
                let midDepth = fromDepthM + (toDepthM - fromDepthM) * fracMid
                let pAmb = UnitsAndPressure.ambientPressure(midDepth, env)
                let insp = UnitsAndPressure.inspiredPressuresCCR(pAmb, gas, setpoint!)
                var next = [Tissue]()
                next.reserveCapacity(16)
                for i in 0..<16 {
                    let c = coeffs[i]
                    let t = current.compartments[i]
                    next.append(Tissue(pN2: haldane(t.pN2, insp.pInspiredN2, c.n2HalfTime, dt).coerceAtLeast(0.0),
                                       pHe: haldane(t.pHe, insp.pInspiredHe, c.heHalfTime, dt).coerceAtLeast(0.0)))
                }
                current = TissueState(next)
            }
            return current
        }
    }

    /// Surface interval off-gassing on air at ambient surface pressure.
    public static func surfaceOffgas(_ tissues: TissueState, _ intervalMin: Double, _ env: Environment, _ coeffs: [CompartmentCoeff]) -> TissueState {
        if intervalMin <= 0.0 { return tissues }
        let pInN2 = (env.pSurfaceBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0) * Constants.FN2_AIR
        return TissueState((0..<16).map { i in
            let c = coeffs[i]
            let t = tissues.compartments[i]
            return Tissue(pN2: haldane(t.pN2, pInN2, c.n2HalfTime, intervalMin).coerceAtLeast(0.0),
                          pHe: haldane(t.pHe, 0.0, c.heHalfTime, intervalMin).coerceAtLeast(0.0))
        })
    }

    public static func mixedAB(_ tissue: Tissue, _ coeff: CompartmentCoeff) -> (Double, Double) {
        let pInert = tissue.pInert
        if pInert <= 1e-12 { return (coeff.aN2, coeff.bN2) }
        let a = (coeff.aN2 * tissue.pN2 + coeff.aHe * tissue.pHe) / pInert
        let b = (coeff.bN2 * tissue.pN2 + coeff.bHe * tissue.pHe) / pInert
        return (a, b)
    }

    public static func mValue(_ pAmbBar: Double, _ a: Double, _ b: Double) -> Double { pAmbBar / b + a }

    public static func toleratedAmbientBar(_ tissue: Tissue, _ coeff: CompartmentCoeff, _ gf: Double,
                                           model: DecoModel = .zhl16C, vpmConservatism: Int = 2) -> Double {
        let pInert = tissue.pInert
        let (aRaw, b) = mixedAB(tissue, coeff)
        let a: Double
        if model == .vpmB {
            let cFactor = 1.0 + 0.05 * Double(vpmConservatism.coerceIn(0, 5))
            let radiusRatio = (Coefficients.VPM_CRITICAL_RADIUS_HE_UM * tissue.pHe + Coefficients.VPM_CRITICAL_RADIUS_N2_UM * tissue.pN2) / max(pInert, 1e-9)
            let vpmScale = (0.86 * (radiusRatio / Coefficients.VPM_CRITICAL_RADIUS_N2_UM) / cFactor).coerceIn(0.62, 0.92)
            a = aRaw * vpmScale
        } else {
            a = aRaw
        }
        let effectiveGf = gf.coerceIn(0.05, 1.0)
        return (pInert - a * effectiveGf) / (effectiveGf / b + 1.0 - effectiveGf)
    }

    public static func ceilingPressureBar(_ tissues: TissueState, _ gf: Double, _ coeffs: [CompartmentCoeff],
                                          model: DecoModel = .zhl16C, vpmConservatism: Int = 2) -> Double {
        var maxP = 0.0
        for i in 0..<16 {
            let pTol = toleratedAmbientBar(tissues.compartments[i], coeffs[i], gf, model: model, vpmConservatism: vpmConservatism)
            if pTol > maxP { maxP = pTol }
        }
        return maxP
    }

    public static func ceilingDepthM(_ tissues: TissueState, _ gf: Double, _ coeffs: [CompartmentCoeff], _ env: Environment,
                                     model: DecoModel = .zhl16C, vpmConservatism: Int = 2) -> Double {
        let pCeil = ceilingPressureBar(tissues, gf, coeffs, model: model, vpmConservatism: vpmConservatism)
        return UnitsAndPressure.depthFromPressure(pCeil, env).coerceAtLeast(0.0)
    }

    public static func gfAtDepth(_ depthM: Double, _ firstStopDepthM: Double, _ gfLow: Double, _ gfHigh: Double) -> Double {
        if firstStopDepthM <= 0.0 { return gfHigh }
        let clampedDepth = depthM.coerceIn(0.0, firstStopDepthM)
        return gfHigh + (gfLow - gfHigh) * (clampedDepth / firstStopDepthM)
    }

    public static func gf99WithLeader(_ tissues: TissueState, _ pAmbBar: Double, _ coeffs: [CompartmentCoeff]) -> (Double, Int) {
        var maxGf = -Double.infinity
        var leader = 0
        for i in 0..<16 {
            let t = tissues.compartments[i]
            let (a, b) = mixedAB(t, coeffs[i])
            let mVal = mValue(pAmbBar, a, b)
            let denom = (mVal - pAmbBar).coerceAtLeast(1e-6)
            let gfPct = ((t.pInert - pAmbBar) / denom) * 100.0
            if gfPct > maxGf { maxGf = gfPct; leader = i }
        }
        return (max(0.0, maxGf), leader)
    }

    public static func gf99Pct(_ tissues: TissueState, _ pAmbBar: Double, _ coeffs: [CompartmentCoeff]) -> Double {
        gf99WithLeader(tissues, pAmbBar, coeffs).0
    }

    public static func surfaceGfPct(_ tissues: TissueState, _ env: Environment, _ coeffs: [CompartmentCoeff]) -> Double {
        gf99Pct(tissues, env.pSurfaceBar, coeffs)
    }

    public static func compartmentSurfaceLoadPct(_ tissues: TissueState, _ env: Environment, _ coeffs: [CompartmentCoeff]) -> [Double] {
        let pSurf = env.pSurfaceBar
        return (0..<16).map { i in
            let t = tissues.compartments[i]
            let (a, b) = mixedAB(t, coeffs[i])
            let mSurf = mValue(pSurf, a, b)
            return ((t.pInert / mSurf) * 100.0).coerceAtLeast(0.0)
        }
    }

    public static func computeNdlMin(_ depthM: Double, _ gas: Gas, _ general: GeneralSettings, _ env: Environment,
                                     initialTissues: TissueState? = nil, isCcr: Bool = false, setpoint: Double? = nil) -> Double {
        let coeffs = Coefficients.tableFor(general.decoModel, use1b: general.useCompartment1b)
        let gfHigh = Double(general.gfHigh) / 100.0
        var tissues = initialTissues ?? initTissues(env, arrivedUnder12hAtAltitude: general.arrivedUnder12hAtAltitude)
        func ceilAt(_ t: TissueState) -> Double {
            ceilingDepthM(t, gfHigh, coeffs, env, model: general.decoModel, vpmConservatism: general.vpmConservatism)
        }
        if ceilAt(tissues) > 0.0 { return 0.0 }
        var minutes = 0
        while minutes < 999 {
            let next = advanceTissues(tissues, depthM, depthM, 1.0, gas, env, coeffs, isCcr: isCcr, setpoint: setpoint)
            if ceilAt(next) > 0.0 {
                var secTissues = tissues
                for sec in 1...60 {
                    secTissues = advanceTissues(secTissues, depthM, depthM, 1.0 / 60.0, gas, env, coeffs, isCcr: isCcr, setpoint: setpoint)
                    if ceilAt(secTissues) > 0.0 { return Double(minutes) + Double(sec - 1) / 60.0 }
                }
                return Double(minutes)
            }
            tissues = next
            minutes += 1
        }
        return 999.0
    }

    /// Index of the first maximum (Kotlin `maxByOrNull` semantics).
    static func firstMaxIndex(_ values: [Double]) -> Int {
        var best = 0
        for i in values.indices where values[i] > values[best] { best = i }
        return best
    }

    /// Surface Interval Calculator based on the Bühlmann ZHL-16C model.
    public static func estimateSurfaceInterval(endTissues: TissueState, endCnsPct: Double, surfaceIntervalMin: Double,
                                               env: Environment, nextDiveDepthM: Double, nextDiveGas: Gas,
                                               general: GeneralSettings) -> SurfaceIntervalEstimate {
        let zhl16c = Coefficients.tableFor(.zhl16C, use1b: general.useCompartment1b)
        let si = surfaceIntervalMin.coerceIn(0.0, 1440.0)
        let ambientN2 = ((env.pSurfaceBar - Constants.P_WATER_VAPOUR) * Constants.FN2_AIR).coerceAtLeast(0.0)
        let postSi = si > 0.0 ? surfaceOffgas(endTissues, si, env, zhl16c) : endTissues
        let loadsBefore = compartmentSurfaceLoadPct(endTissues, env, zhl16c)
        let loadsAfter = compartmentSurfaceLoadPct(postSi, env, zhl16c)
        func excessSum(_ t: TissueState) -> Double { t.compartments.reduce(0.0) { $0 + ($1.pInert - ambientN2).coerceAtLeast(0.0) } }
        func excessPeak(_ t: TissueState) -> Double { t.compartments.map { ($0.pInert - ambientN2).coerceAtLeast(0.0) }.max() ?? 0.0 }
        let excessBeforeSum = excessSum(endTissues)
        let excessAfterSum = excessSum(postSi)
        let remainingExcessPct = excessBeforeSum > 1e-4 ? ((excessAfterSum / excessBeforeSum) * 100.0).coerceIn(0.0, 100.0) : 0.0
        let leaderIdx = firstMaxIndex(loadsAfter)
        let cnsAfter = endCnsPct * pow(0.5, si / Constants.CNS_DECAY_HALF_TIME_MIN)
        var zhlGeneral = general
        zhlGeneral.decoModel = .zhl16C
        let freshTissues = initTissues(env, arrivedUnder12hAtAltitude: general.arrivedUnder12hAtAltitude)
        let safeNextDepth = nextDiveDepthM.coerceIn(6.0, 100.0)
        let freshNdl = computeNdlMin(safeNextDepth, nextDiveGas, zhlGeneral, env, initialTissues: freshTissues)
        let nextNdl = computeNdlMin(safeNextDepth, nextDiveGas, zhlGeneral, env, initialTissues: postSi)
        var cleanTime = 0.0
        if excessBeforeSum > 1e-4 {
            var tMin = 0.0
            while tMin <= 1440.0 {
                let sim = surfaceOffgas(endTissues, tMin, env, zhl16c)
                if excessSum(sim) / excessBeforeSum <= 0.10 { cleanTime = tMin; break }
                tMin += 15.0
                cleanTime = tMin
            }
        }
        return SurfaceIntervalEstimate(
            surfaceIntervalMin: si, startTissues: endTissues, postSiTissues: postSi, ambientN2Bar: ambientN2,
            excessInertBeforeBar: excessPeak(endTissues), excessInertAfterBar: excessPeak(postSi),
            remainingExcessInertPct: remainingExcessPct,
            residualN2PeakBar: postSi.compartments.map(\.pN2).max() ?? 0.0,
            residualHePeakBar: postSi.compartments.map(\.pHe).max() ?? 0.0,
            leadingCompartmentNumber: leaderIdx + 1, leadingN2HalfTimeMin: zhl16c[leaderIdx].n2HalfTime,
            leadingSaturationBeforePct: loadsBefore[leaderIdx], leadingSaturationAfterPct: loadsAfter[leaderIdx],
            surfaceGfBeforePct: surfaceGfPct(endTissues, env, zhl16c), surfaceGfAfterPct: surfaceGfPct(postSi, env, zhl16c),
            cnsBeforePct: endCnsPct, cnsAfterPct: cnsAfter, freshNdlMin: freshNdl, nextDiveNdlMin: nextNdl,
            compartmentLoadsBeforePct: loadsBefore, compartmentLoadsAfterPct: loadsAfter,
            estimatedCleanTimeMin: cleanTime.coerceAtMost(1440.0))
    }
}
