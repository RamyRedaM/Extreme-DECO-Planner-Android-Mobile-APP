import XCTest
@testable import DecoEngine

/// Best GAS Mix by diving agency (port of GasAgencyTest.kt).
final class GasAgencyTests: XCTestCase {

    let env = UnitsAndPressure.buildEnvironment(GeneralSettings())

    func settings(_ a: GasAgency, ccr: Bool = false) -> GeneralSettings {
        var g = GeneralSettings(); g.gasAgency = a; if ccr { g.planningType = .ccr }; return g
    }

    func rec(_ a: GasAgency, _ depth: Double, levels: Bool = false, ccr: Bool = false, dive: DiveSettings = DiveSettings()) -> MixRecommendation {
        GasCalculator.recommendBestMix(maxDepthM: depth, general: settings(a, ccr: ccr), dive: dive,
                                       levels: levels ? [DiveLevel("a", depth, 5.0)] : [])
    }

    func mix(_ g: Gas) -> String { "\(g.o2Pct)/\(g.hePct)" }

    func testTdiUsesAirToFiftyFiveWithEan50AndOxygenOnly() {
        for d in [40.0, 50.0, 55.0] {
            let r = rec(.tdi, d)
            XCTAssertEqual(mix(r.bottomGas), "21/0", "bottom at \(d) m")
            XCTAssertEqual(r.decoGases.map(mix), ["50/0", "100/0"])
            XCTAssertNil(r.travelGas)
            XCTAssertNil(r.deepDecoGas)
            XCTAssertEqual(r.plannerGases.count, 3)
            XCTAssertTrue(r.notes.first!.hasPrefix("TDI method"))
        }
    }

    func testDeepAirUsesEan32WhenShallowEnough() {
        XCTAssertEqual(mix(rec(.tdi, 30.0).bottomGas), "32/0")
    }

    func testDeepAirSwitchesToTrimixBeyondTheAgencyLimit() {
        XCTAssertGreaterThan(rec(.tdi, 60.0).bottomGas.hePct, 0)
        XCTAssertEqual(mix(rec(.padi, 50.0).bottomGas), "21/0")
        XCTAssertGreaterThan(rec(.padi, 52.0).bottomGas.hePct, 0)
        XCTAssertEqual(mix(rec(.iantd, 52.0).bottomGas), "21/0")
        XCTAssertGreaterThan(rec(.iantd, 54.0).bottomGas.hePct, 0)
        var lowPpO2 = DiveSettings(); lowPpO2.ppO2Bottom = 1.2
        XCTAssertGreaterThan(rec(.tdi, 55.0, dive: lowPpO2).bottomGas.hePct, 0)
    }

    func testGueAndUtdUseStandardGasesByDepth() {
        let gue: [(Double, String)] = [(33, "30/30"), (45, "21/35"), (55, "18/45"), (70, "15/55"), (90, "10/70")]
        for (d, m) in gue { XCTAssertEqual(mix(rec(.gue, d).bottomGas), m, "GUE at \(d) m") }
        XCTAssertEqual(mix(rec(.utd, 38.0).bottomGas), "25/25")
        let r = rec(.gue, 55.0)
        XCTAssertEqual(r.decoGases.map(mix), ["50/0", "100/0"])
        XCTAssertNil(r.travelGas)
        XCTAssertNil(r.deepDecoGas)
    }

    func testHypoxicStandardGasGetsA2135Travel() {
        let r = rec(.gue, 70.0)
        XCTAssertEqual(mix(r.bottomGas), "15/55")
        XCTAssertEqual(mix(r.travelGas!), "21/35")
        XCTAssertEqual(r.travelGas!.role, .travel)
        XCTAssertEqual(r.plannerGases.first, r.travelGas)
    }

    func testGueRecreationalDiveUsesTriox() {
        let r = rec(.gue, 35.0, levels: true)
        XCTAssertTrue(r.isRecreational)
        XCTAssertEqual(mix(r.bottomGas), "30/30")
        let tdi = rec(.tdi, 35.0, levels: true)
        XCTAssertTrue(tdi.isRecreational)
        XCTAssertEqual(tdi.bottomGas.hePct, 0)
    }

    func testDensityAndEndAgenciesUseTheirLimits() {
        XCTAssertLessThanOrEqual(GasCalculator.densityGL(rec(.bsac, 60.0).bottomGas, 60.0, env), 5.25)
        var loose = DiveSettings(); loose.maxEndM = 40.0; loose.maxGasDensityGL = 8.0
        let naui = rec(.naui, 50.0, dive: loose)
        XCTAssertLessThanOrEqual(GasCalculator.endM(naui.bottomGas, 50.0, env, false), 31.0)
        XCTAssertLessThan(rec(.optimized, 50.0, dive: loose).bottomGas.hePct, naui.bottomGas.hePct)
    }

    func testDeepAirPlanAlertsAreHighNotCritical() {
        func plan(_ a: GasAgency) -> DivePlanResult {
            let r = GasCalculator.recommendBestMix(maxDepthM: 55.0, general: settings(.tdi), dive: DiveSettings())
            var i = DivePlanInput(); i.general = settings(a); i.gases = r.plannerGases; i.levels = [DiveLevel("a", 55.0, 20.0)]
            return DivePlannerEngine.planDive(i)
        }
        let tdi = plan(.tdi)
        XCTAssertFalse(tdi.alerts.contains { $0.level == .critical && ($0.code == .DENSITY_HIGH || $0.code == .END_HIGH) })
        XCTAssertTrue(tdi.alerts.contains { $0.level == .high && $0.code == .DENSITY_HIGH && $0.message.contains("TDI") })
        XCTAssertTrue(tdi.alerts.contains { $0.level == .medium && $0.code == .END_HIGH && $0.message.contains("Deep air") })
        XCTAssertTrue(plan(.gue).alerts.contains { $0.level == .critical && $0.code == .DENSITY_HIGH })
    }

    func testCcrBottomBailoutFollowsTheAgency() {
        XCTAssertEqual(mix(rec(.tdi, 50.0, ccr: true).bailoutBottomGas!), "21/0")
        XCTAssertEqual(mix(rec(.gue, 55.0, ccr: true).bailoutBottomGas!), "18/45")
        XCTAssertEqual(rec(.gue, 55.0, ccr: true).bailoutBottomGas!.role, .bailout)
    }

    func testEveryAgencyProducesAPlannableKitAtEveryDepth() {
        for a in GasAgency.allCases {
            for d in [20.0, 35.0, 45.0, 55.0, 70.0, 90.0] {
                let g = settings(a)
                let r = GasCalculator.recommendBestMix(maxDepthM: d, general: g, dive: DiveSettings(), levels: [DiveLevel("a", d, 20.0)])
                var i = DivePlanInput(); i.general = g; i.gases = r.plannerGases; i.levels = [DiveLevel("a", d, 20.0)]
                let res = DivePlannerEngine.planDive(i)
                XCTAssertNil(res.abortedReason, "\(a) at \(d) m")
                XCTAssertLessThanOrEqual(UnitsAndPressure.ambientPressure(d, env) * r.bottomGas.o2, 1.6 + 1e-6)
            }
        }
    }

    func testOldSavedSettingsWithoutAgencyStillDecode() throws {
        let json = try JSONEncoder().encode(GeneralSettings())
        var obj = try JSONSerialization.jsonObject(with: json) as! [String: Any]
        obj.removeValue(forKey: "gasAgencyStored")
        let decoded = try JSONDecoder().decode(GeneralSettings.self, from: JSONSerialization.data(withJSONObject: obj))
        XCTAssertEqual(decoded.gasAgency, .optimized)
    }
}
