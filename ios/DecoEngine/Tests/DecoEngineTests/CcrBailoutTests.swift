import XCTest
@testable import DecoEngine

/// CCR Best GAS Mix and worst-point open-circuit bailout (port of CcrBailoutTest.kt).
final class CcrBailoutTests: XCTestCase {

    var ccrBest: GeneralSettings { var g = GeneralSettings(); g.planningType = .ccr; g.gasSelectionMode = .bestGasMix; return g }
    var ccrUser: GeneralSettings { var g = GeneralSettings(); g.planningType = .ccr; g.gasSelectionMode = .userInputGas; return g }
    let env = UnitsAndPressure.buildEnvironment(GeneralSettings())
    let dive = DiveSettings()

    func rec(_ depth: Double, _ general: GeneralSettings? = nil) -> MixRecommendation {
        GasCalculator.recommendBestMix(maxDepthM: depth, general: general ?? ccrBest, dive: dive, levels: [DiveLevel("a", depth, 25.0)])
    }

    func input(_ general: GeneralSettings, _ gases: [Gas], _ levels: [DiveLevel]) -> DivePlanInput {
        var i = DivePlanInput(); i.general = general; i.gases = gases; i.levels = levels; return i
    }

    func bestPlan(_ depth: Double, _ time: Double, contingencies: Bool = false) -> DivePlanResult {
        DivePlannerEngine.planDive(input(ccrBest, rec(depth).plannerGases, [DiveLevel("a", depth, time)]), includeContingencies: contingencies)
    }

    func g(_ id: String, _ o2: Int, _ he: Int, _ role: GasRole, _ l: Double, _ n: Int = 1) -> Gas {
        Gas.fromPercent(id, o2, he, role, cylinder: CylinderConfig(waterVolumeL: l, fillPressureBar: 200.0, count: n))
    }

    func testDiluentIsSizedForPpO2AboutOnePointOneAtDepth() {
        for depth in [45.0, 60.0, 80.0, 100.0] {
            let r = rec(depth)
            let ppO2 = UnitsAndPressure.ambientPressure(depth, env) * r.bottomGas.o2
            XCTAssertEqual(r.bottomGas.role, .diluent)
            XCTAssertTrue(ppO2 <= Constants.CCR_DILUENT_PPO2 + 0.005 && ppO2 >= 0.95, "diluent ppO2 \(ppO2) at \(depth) m")
            XCTAssertLessThanOrEqual(GasCalculator.endM(r.bottomGas, depth, env, dive.o2Narcotic), dive.maxEndM + 1.0)
        }
    }

    func testShallowDiluentIsAirNotNitrox() {
        let r = rec(30.0)
        XCTAssertEqual(r.bottomGas.o2Pct, 21)
        XCTAssertEqual(r.bottomGas.hePct, 0)
    }

    func testBestMixHasBottomBailoutAndBailoutRolesAndNoTravel() {
        let r = rec(60.0)
        let bb = r.bailoutBottomGas!
        XCTAssertEqual(bb.role, .bailout)
        let p = UnitsAndPressure.ambientPressure(60.0, env)
        XCTAssertTrue(GasCalculator.minDepthM(bb, dive.ppO2Min, env) <= 60.0 && p * bb.o2 <= dive.ppO2Bottom + 0.005)
        XCTAssertTrue(r.plannerGases.allSatisfy { $0.role != .travel && $0.role != .bottom && $0.role != .deco })
        XCTAssertTrue(r.plannerGases.dropFirst().allSatisfy { $0.role == .bailout })
        XCTAssertEqual(r.plannerGases.filter { $0.role == .diluent }.count, 1)
        XCTAssertFalse(r.isRecreational)
        let oc = rec(60.0, GeneralSettings())
        XCTAssertNil(oc.bailoutBottomGas)
        XCTAssertTrue(oc.decoGases.allSatisfy { $0.role == .deco })
    }

    func testDeepBailoutStageIsAddedBelow54mAndIsIcdSafe() {
        let r = rec(80.0)
        let deep = r.deepDecoGas!
        XCTAssertEqual(deep.role, .bailout)
        let icd = GasCalculator.evaluateIcd(r.bailoutBottomGas!, deep, deep.modOverrideM!, env, AlertSettings())
        XCTAssertFalse(icd.violatesDeltaN2)
    }

    func testLoopNeverBreathesBailoutGases() {
        let res = bestPlan(60.0, 25.0)
        XCTAssertTrue(res.segments.allSatisfy { $0.gas.role == .diluent && $0.isCcr })
    }

    func testBailoutStartsAtDepthAtTheEndOfTheBottomPhase() {
        let res = bestPlan(60.0, 25.0)
        let bo = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!.worst!
        let bottomEnd = res.levelEnds.last!.runtimeMin
        XCTAssertEqual(bo.input.bailoutStartRuntimeMin!, bottomEnd, accuracy: 1e-9)
        XCTAssertFalse(bo.segments.contains { $0.type == .descent })
        XCTAssertEqual(bo.segments.first!.fromDepthM, 60.0, accuracy: 1e-9)
        XCTAssertEqual(bo.segments.first!.runtimeMin, bottomEnd + Constants.CCR_BAILOUT_PROBLEM_SOLVING_MIN, accuracy: 1e-6)
        XCTAssertFalse(bo.segments.contains { $0.isCcr })
        XCTAssertGreaterThan(bo.totalRuntimeMin, bottomEnd + Constants.CCR_BAILOUT_PROBLEM_SOLVING_MIN)
        XCTAssertGreaterThan(bo.ttsMin, res.ttsMin)
        XCTAssertGreaterThan(bo.oxygen.startCnsPct, 0.0)
        XCTAssertFalse(bo.alerts.contains { $0.code == .SHORT_SURFACE_INTERVAL || $0.code == .REVERSE_PROFILE })
        XCTAssertFalse(bo.alerts.contains { $0.code == .PPO2_HIGH_BOTTOM || $0.code == .NO_DECO_REDUNDANCY })
    }

    func testBestModeSizesBailoutCylindersFromTheBailoutPlan() {
        let res = bestPlan(60.0, 25.0)
        let a = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!
        for gas in res.input.gases where gas.role == .bailout {
            let need = a.requiredById[gas.id] ?? 0.0
            XCTAssertGreaterThan(need, 0.0, gas.displayName)
            XCTAssertGreaterThanOrEqual(gas.cylinder.availableLitres + 0.5, need, gas.displayName)
        }
        XCTAssertFalse(res.alerts.contains { $0.code == .CCR_BAILOUT_RISK })
    }

    func testBailoutContingencyIsReportedForBestPlan() {
        let res = bestPlan(60.0, 25.0, contingencies: true)
        let bo = res.ccrBailout!
        XCTAssertFalse(bo.hasErrors)
        XCTAssertTrue(bo.subtitle.contains("60 m"))
        XCTAssertFalse(bo.stops.isEmpty)
        XCTAssertEqual(DivePlannerEngine.generateCcrBailout(res.input).totalRuntimeMin, bo.totalRuntimeMin, accuracy: 1e-6)
    }

    func testWorstPointIsTheLevelWithTheLongestBailoutAscent() {
        let i = input(ccrUser, [g("dil", 15, 55, .diluent, 3.0), g("bo_tx", 18, 45, .bailout, 11.0, 2),
                                g("bo_50", 50, 0, .bailout, 11.0), g("bo_o2", 100, 0, .bailout, 7.0)],
                      [DiveLevel("a", 75.0, 15.0), DiveLevel("b", 50.0, 10.0)])
        let res = DivePlannerEngine.planDive(i)
        XCTAssertEqual(res.levelEnds.count, 2)
        let a = DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, res.levelEnds)!
        let perLevel = res.levelEnds.map { DivePlannerEngine.ccrBailoutAnalysis(res.input, res.segments, [$0])! }
        for (id, need) in a.requiredById {
            XCTAssertEqual(need, perLevel.map { $0.requiredById[id] ?? 0.0 }.max()!, accuracy: 1e-6)
        }
        XCTAssertEqual(a.worst!.ttsMin, perLevel.map { $0.worst!.ttsMin }.max()!, accuracy: 1e-6)
    }

    func testMissingBottomBailoutFallsBackToDiluentAndAlerts() {
        let res = DivePlannerEngine.planDive(input(ccrUser, [g("dil", 18, 45, .diluent, 3.0), g("bo", 50, 0, .bailout, 11.0)],
                                                   [DiveLevel("a", 60.0, 25.0)]))
        XCTAssertTrue(res.alerts.contains { $0.level == .high && $0.code == .CCR_BAILOUT_RISK && $0.message.contains("diluent") })
        XCTAssertTrue(res.alerts.contains { $0.level == .critical && $0.code == .CCR_BAILOUT_RISK && $0.message.contains("TX 18/45") })
    }

    func testUndersizedUserBailoutIsCritical() {
        let res = DivePlannerEngine.planDive(input(ccrUser, [g("dil", 18, 45, .diluent, 3.0), g("bo_tx", 18, 45, .bailout, 3.0),
                                                             g("bo_50", 50, 0, .bailout, 11.0), g("bo_o2", 100, 0, .bailout, 7.0)],
                                                   [DiveLevel("a", 60.0, 25.0)]))
        XCTAssertTrue(res.alerts.contains { $0.level == .critical && $0.code == .CCR_BAILOUT_RISK && $0.message.contains("Insufficient bailout gas TX 18/45") })
        XCTAssertFalse(res.alerts.contains { $0.code == .CCR_BAILOUT_RISK && $0.message.contains("diluent") })
    }

    func testBailoutStressRmvAndReserveRulesAreApplied() {
        var base = bestPlan(60.0, 25.0).input
        base.general = ccrUser
        func need(_ c: ConsumptionSettings) -> Double {
            var i = base; i.consumption = c
            let r = DivePlannerEngine.planDive(i)
            return DivePlannerEngine.ccrBailoutAnalysis(r.input, r.segments, r.levelEnds)!.requiredById.values.reduce(0, +)
        }
        let normal = need(ConsumptionSettings())
        var c1 = ConsumptionSettings(); c1.stressFactor = 2.0
        var c2 = ConsumptionSettings(); c2.bottomRmvLMin = 25.0
        var c3 = ConsumptionSettings(); c3.bottomReserveRule = .half
        var c4 = ConsumptionSettings(); c4.decoReserveRule = .lostGas; c4.bottomReserveRule = .rockBottom
        XCTAssertGreaterThan(need(c1), normal)
        XCTAssertGreaterThan(need(c2), normal)
        XCTAssertGreaterThan(need(c3), normal)
        XCTAssertGreaterThan(need(c4), 0.0)
    }

    func testCcrPlanIsDeterministic() {
        let a = bestPlan(70.0, 20.0, contingencies: true)
        let b = bestPlan(70.0, 20.0, contingencies: true)
        XCTAssertEqual(a.totalRuntimeMin, b.totalRuntimeMin)
        XCTAssertEqual(a.input.gases, b.input.gases)
        XCTAssertEqual(a.ccrBailout, b.ccrBailout)
    }

    func testMultiDiveDayWithCcrStillPlansEveryDive() {
        var base = DivePlanInput(); base.general = ccrBest; base.gases = rec(60.0).plannerGases
        let day = DivePlannerEngine.planMultiDiveDay(base, [
            MultiDiveEntry(id: "d1", label: "Dive 1", surfaceIntervalBeforeMin: 0, levels: [DiveLevel("a", 60.0, 20.0)]),
            MultiDiveEntry(id: "d2", label: "Dive 2", surfaceIntervalBeforeMin: 120, levels: [DiveLevel("b", 30.0, 30.0)])
        ], includeContingenciesForIndex: 1)
        XCTAssertEqual(day.diveResults.count, 2)
        XCTAssertTrue(day.diveResults.allSatisfy { $0.abortedReason == nil })
        XCTAssertNotNil(day.diveResults[1].ccrBailout)
    }
}
