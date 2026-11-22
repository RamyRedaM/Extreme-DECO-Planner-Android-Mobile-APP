import XCTest
@testable import DecoEngine

/// Ports of the Android engine tests (DecompressionEngineTest.kt and AuditFixesTest.kt).
final class EngineTests: XCTestCase {

    let salt: Environment = UnitsAndPressure.buildEnvironment(GeneralSettings())

    func userInput(_ gases: [Gas], _ levels: [DiveLevel], gfLow: Int = 30, gfHigh: Int = 70) -> DivePlanInput {
        var i = DivePlanInput()
        i.general.gasSelectionMode = .userInputGas
        i.general.gfLow = gfLow
        i.general.gfHigh = gfHigh
        i.gases = gases
        i.levels = levels
        return i
    }

    let air = Gas.fromPercent("air", 21, 0, .bottom, cylinder: CylinderConfig(waterVolumeL: 12, fillPressureBar: 200, count: 2))
    let ean50 = Gas.fromPercent("ean50", 50, 0, .deco, cylinder: CylinderConfig(waterVolumeL: 11, fillPressureBar: 200, count: 1))
    let o2 = Gas.fromPercent("o2", 100, 0, .deco, cylinder: CylinderConfig(waterVolumeL: 7, fillPressureBar: 200, count: 1))
    let tx = Gas.fromPercent("tx", 18, 45, .bottom, cylinder: CylinderConfig(waterVolumeL: 12, fillPressureBar: 232, count: 2))

    func testPressureAndAltitude() {
        XCTAssertEqual(UnitsAndPressure.ambientPressure(30, salt), 4.0435, accuracy: 0.001)
        XCTAssertEqual(UnitsAndPressure.surfacePressureBar(2000), 0.7950, accuracy: 0.001)
    }

    func testModEndDensity() {
        XCTAssertEqual(GasCalculator.modDisplayM(ean50, 1.6, salt), 21.0, accuracy: 0.001)
        XCTAssertEqual(GasCalculator.modDisplayM(o2, 1.6, salt), 5.0, accuracy: 0.001)
        XCTAssertEqual(GasCalculator.endM(tx, 60, salt, true), 28.5, accuracy: 0.2)
        let d40 = GasCalculator.densityGL(air, 40, salt)
        XCTAssertEqual(d40, 6.4, accuracy: 0.15)
    }

    func testHaldaneSchreinerGf() {
        let h = TissuesAndBuhlmann.haldane(0.7405, 3.0, 4.0, 4.0)
        XCTAssertEqual(h, 1.8703, accuracy: 0.001)
        XCTAssertEqual(TissuesAndBuhlmann.schreiner(0.7405, 3.0, 0.0, 4.0, 4.0), h, accuracy: 1e-9)
        let c = Coefficients.tableFor(.zhl16C)[4]
        let t = Tissue(pN2: 3.0, pHe: 0.0)
        XCTAssertEqual(TissuesAndBuhlmann.toleratedAmbientBar(t, c, 1.0), (3.0 - c.aN2) * c.bN2, accuracy: 1e-9)
    }

    func testCnsAndOtu() {
        XCTAssertEqual(OxygenAndConsumption.segmentCnsPct(1.4, 1.4, 30), 20.0, accuracy: 0.05)
        XCTAssertEqual(OxygenAndConsumption.segmentOtu(1.4, 1.4, 30), 48.9, accuracy: 0.3)
    }

    func stop(_ r: DivePlanResult, _ d: Int) -> Double { r.stops.filter { $0.depthM == d }.reduce(0) { $0 + $1.durationMin } }

    func testGoldenSchedules() {
        let a = DivePlannerEngine.planDive(userInput([air, ean50, o2], [DiveLevel("a", 40, 20)], gfLow: 20, gfHigh: 80))
        XCTAssertTrue((32.5...37.5).contains(a.totalRuntimeMin), "Case A runtime \(a.totalRuntimeMin)")
        XCTAssertLessThanOrEqual(abs(stop(a, 3) - 4.0), 1.5)
        let b = DivePlannerEngine.planDive(userInput([air, ean50, o2], [DiveLevel("a", 40, 20)], gfLow: 100, gfHigh: 100))
        XCTAssertTrue((26.5...31.5).contains(b.totalRuntimeMin), "Case B runtime \(b.totalRuntimeMin)")
        let txc = Gas.fromPercent("tx", 18, 45, .bottom)
        let c = DivePlannerEngine.planDive(userInput([txc, ean50, o2], [DiveLevel("a", 60, 20)], gfLow: 30, gfHigh: 70))
        XCTAssertTrue((63.0...71.5).contains(c.totalRuntimeMin), "Case C runtime \(c.totalRuntimeMin)")
        XCTAssertTrue((27...33).contains(c.firstStopM), "Case C first stop \(c.firstStopM)")
        let d = DivePlannerEngine.planDive(userInput([air], [DiveLevel("a", 30, 30)], gfLow: 100, gfHigh: 100))
        XCTAssertTrue((38.5...43.5).contains(d.totalRuntimeMin), "Case D runtime \(d.totalRuntimeMin)")
        XCTAssertLessThanOrEqual(abs(stop(d, 3) - 7.0), 1.5)
    }

    func testModelConservatismAndGf() {
        var base = userInput([tx, ean50, o2], [DiveLevel("a", 50, 30)])
        base.general.decoModel = .zhl16A
        let ra = DivePlannerEngine.planDive(base)
        base.general.decoModel = .zhl16B
        let rb = DivePlannerEngine.planDive(base)
        base.general.decoModel = .zhl16C
        let rc = DivePlannerEngine.planDive(base)
        XCTAssertGreaterThanOrEqual(rc.totalDecoMin, rb.totalDecoMin - 0.01)
        XCTAssertGreaterThanOrEqual(rb.totalDecoMin, ra.totalDecoMin - 0.01)
        var g65 = base; g65.general.gfHigh = 65
        var g85 = base; g85.general.gfHigh = 85
        XCTAssertGreaterThanOrEqual(DivePlannerEngine.planDive(g65).totalDecoMin, DivePlannerEngine.planDive(g85).totalDecoMin)
    }

    func testMultiDiveDayCarriesResidualGas() {
        var base = DivePlanInput()
        base.general.gasSelectionMode = .userInputGas
        base.gases = [air, ean50, o2]
        let d1 = MultiDiveEntry(id: "1", label: "Dive 1", levels: [DiveLevel("a", 40, 20)])
        let short = DivePlannerEngine.planMultiDiveDay(base, [d1, MultiDiveEntry(id: "2", label: "Dive 2", surfaceIntervalBeforeMin: 45, levels: [DiveLevel("b", 40, 20)])], includeContingenciesForIndex: -1)
        let long = DivePlannerEngine.planMultiDiveDay(base, [d1, MultiDiveEntry(id: "2", label: "Dive 2", surfaceIntervalBeforeMin: 240, levels: [DiveLevel("b", 40, 20)])], includeContingenciesForIndex: -1)
        XCTAssertGreaterThan(short.diveResults[1].totalDecoMin, short.diveResults[0].totalDecoMin)
        XCTAssertLessThan(long.diveResults[1].totalDecoMin, short.diveResults[1].totalDecoMin)
        XCTAssertGreaterThan(short.totalDailyOtu, short.diveResults[0].oxygen.diveOtu)
    }

    func testRecreationalMode() {
        var general = GeneralSettings()
        general.gasSelectionMode = .bestGasMix
        let rec = GasCalculator.recommendBestMix(maxDepthM: 25, general: general, dive: DiveSettings(), levels: [DiveLevel("x", 25, 20)])
        XCTAssertTrue(rec.isRecreational)
        XCTAssertEqual(rec.bottomGas.o2Pct, 32)
        XCTAssertEqual(rec.plannerGases.count, 1)
        let tec = GasCalculator.recommendBestMix(maxDepthM: 30, general: general, dive: DiveSettings(), levels: [DiveLevel("x", 30, 45)])
        XCTAssertFalse(tec.isRecreational)
        XCTAssertEqual(tec.deco6mO2?.o2Pct, 100)
        XCTAssertEqual(tec.deco21mEan50?.o2Pct, 50)
    }

    func testAlertLevels() {
        let bad = DivePlannerEngine.planDive(userInput([Gas.fromPercent("bad", 50, 0, .bottom)], [DiveLevel("a", 40, 15)]))
        XCTAssertTrue(bad.hasErrors)
        XCTAssertTrue(bad.alerts.contains { $0.code == .PPO2_HIGH_BOTTOM && $0.level == .critical })
        let priorities = bad.alerts.map(\.level.priority)
        XCTAssertEqual(priorities, priorities.sorted())
    }

    func testSurfaceIntervalCalculator() {
        let dive = DivePlannerEngine.planDive(userInput([tx, ean50, o2], [DiveLevel("a", 60, 25)]))
        func est(_ si: Double) -> SurfaceIntervalEstimate {
            TissuesAndBuhlmann.estimateSurfaceInterval(endTissues: dive.endTissues, endCnsPct: dive.oxygen.endCnsPct, surfaceIntervalMin: si,
                                                       env: dive.env, nextDiveDepthM: 25, nextDiveGas: Gas.fromPercent("x", 32, 0, .bottom),
                                                       general: GeneralSettings())
        }
        let e0 = est(0), e60 = est(60), e180 = est(180)
        XCTAssertEqual(e60.compartmentLoadsAfterPct.count, 16)
        XCTAssertLessThan(e60.remainingExcessInertPct, e0.remainingExcessInertPct)
        XCTAssertLessThan(e180.remainingExcessInertPct, e60.remainingExcessInertPct)
        XCTAssertLessThanOrEqual(e180.cnsAfterPct, e0.cnsBeforePct * 0.26 + 0.01)
    }

    // ---- Audit fixes (same as AuditFixesTest.kt) ----

    func testFix1CcrDescentSetpoint() {
        var ccr = userInput([tx, ean50, o2], [DiveLevel("a", 60, 25)])
        ccr.general.planningType = .ccr
        let r07 = DivePlannerEngine.planDive(ccr)
        var c05 = ccr; c05.ccr.setpointDescent = 0.5
        let r05 = DivePlannerEngine.planDive(c05)
        let first = r07.segments.first { $0.type == .descent }!
        XCTAssertEqual(first.toDepthM, 20.0, accuracy: 1e-6)
        XCTAssertEqual(first.setpoint!, 0.7, accuracy: 1e-9)
        XCTAssertNotEqual(r07.endTissues.compartments[0].pN2, r05.endTissues.compartments[0].pN2)
    }

    func testFix2NextDiveGas() {
        let dv = DiveSettings()
        XCTAssertEqual(GasCalculator.defaultNextDiveGas([Gas.fromPercent("b", 39, 0, .bottom)], 25, dv, salt).displayName, "EAN39")
        XCTAssertEqual(GasCalculator.defaultNextDiveGas(nil, 25, dv, salt).displayName, "EAN32")
        XCTAssertEqual(GasCalculator.defaultNextDiveGas(nil, 40, dv, salt).displayName, "Air")
    }

    func testFix3RockBottom() {
        var rb = userInput([tx, ean50, o2], [DiveLevel("a", 60, 12)])
        rb.consumption.bottomReserveRule = .rockBottom
        let a = DivePlannerEngine.planDive(rb)
        let expected = OxygenAndConsumption.rockBottomReserveLitres(60, 21, rb, a.env)
        let gc = a.gasConsumption.first { $0.gas.id == "tx" }!
        XCTAssertEqual(gc.reserveLitres, max(expected, gc.usedLitres * 0.5), accuracy: 1.0)
        var withTravel = rb
        withTravel.gases = [Gas.fromPercent("travel", 32, 0, .travel, cylinder: CylinderConfig(waterVolumeL: 11, fillPressureBar: 200, count: 1)), tx, ean50, o2]
        let t = DivePlannerEngine.planDive(withTravel).gasConsumption.first { $0.gas.id == "travel" }!
        XCTAssertEqual(t.reserveLitres, t.usedLitres * 0.5, accuracy: 1.0)
    }

    func testFix4LostGas() {
        let base = userInput([tx, ean50, o2], [DiveLevel("a", 60, 25)])
        var lostIn = base; lostIn.consumption.decoReserveRule = .lostGas
        let mult = DivePlannerEngine.planDive(base)
        let lost = DivePlannerEngine.planDive(lostIn)
        func req(_ r: DivePlanResult, _ id: String) -> Double { r.gasConsumption.first { $0.gas.id == id }!.totalRequiredLitres }
        XCTAssertTrue(req(lost, "ean50") > req(mult, "ean50") + 1 || req(lost, "o2") > req(mult, "o2") + 1)
    }

    func testFix5TravelDisplayDepth() {
        let travel = Gas.fromPercent("t", 23, 37, .travel)
        let d = GasCalculator.displayEvalDepthM(travel, 82, DiveSettings(), salt)
        XCTAssertLessThanOrEqual(d, GasCalculator.modExactM(travel, 1.4, salt) + 1e-6)
        XCTAssertEqual(GasCalculator.displayEvalDepthM(ean50, 82, DiveSettings(), salt), 21.0, accuracy: 1e-9)
    }

    func testFix6RedSea() {
        var g = GeneralSettings()
        g.salinityPreset = .redSea
        g.customSalinityKgM3 = 1000
        XCTAssertEqual(g.effectiveSalinity, 1030, accuracy: 1e-9)
        g.customSalinityKgM3 = 1028
        XCTAssertEqual(g.effectiveSalinity, 1028, accuracy: 1e-9)
    }

    func testFix7PpN2DecoAlert() {
        var i = userInput([air], [DiveLevel("a", 40, 30)])
        XCTAssertFalse(DivePlannerEngine.planDive(i).alerts.contains { $0.code == .PPN2_HIGH_DECO })
        i.dive.ppN2MaxDeco = 1.5
        XCTAssertTrue(DivePlannerEngine.planDive(i).alerts.contains { $0.code == .PPN2_HIGH_DECO && $0.level == .medium })
    }

    func testFix9PracticalHelium() {
        for depth in [42.0, 45, 50, 55, 60, 70, 90] {
            let rec = GasCalculator.recommendBestMix(maxDepthM: depth, general: GeneralSettings(), dive: DiveSettings(), levels: [DiveLevel("x", depth, 20)])
            for g in rec.plannerGases { XCTAssertTrue(g.hePct == 0 || g.hePct >= 10, "\(g.displayName) at \(depth)") }
        }
    }
}
