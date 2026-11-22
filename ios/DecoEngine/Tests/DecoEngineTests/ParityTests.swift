import XCTest
@testable import DecoEngine

/// Cross-platform parity: each scenario must reproduce the Kotlin engine's output
/// (app/src/test/java/com/example/engine/ParityFixturesTest.kt). When the engine changes on purpose,
/// rerun that Kotlin test and paste the new "PARITY|..." lines into `kotlinReference` below.
final class ParityTests: XCTestCase {

    static let kotlinReference: [String: String] = [
        "S1": "33.11|8.00|21|21:1.00:EAN50,9:2.00:EAN50,6:2.00:O2 100%,3:3.00:O2 100%|19.04|38.36|74.7|Air=3108/4800,EAN50=266/2200,O2 100%=195/1400|CRITICAL:DENSITY_HIGH,MEDIUM:END_HIGH",
        "S2": "83.33|51.00|33|30:1.00:TX 18/45,27:2.00:TX 18/45,24:2.00:TX 18/45,21:2.00:EAN50,18:2.00:EAN50,15:3.00:EAN50,12:4.00:EAN50,9:8.00:EAN50,6:9.00:O2 100%,3:18.00:O2 100%|63.24|111.03|67.0|TX 18/45=5963/5568,EAN50=1053/2200,O2 100%=880/1400|CRITICAL:GAS_VOLUME,HIGH:DENSITY_HIGH,HIGH:NO_O2_BREAKS,MEDIUM:ICD_FIFTHS,MEDIUM:LONG_TTS_EXPOSURE,LOW:CNS",
        "S3": "41.00|7.00|3|3:7.00:Air|7.47|22.23|95.0|Air=3966/4800|HIGH:SURFACE_GF_HIGH,MEDIUM:GF_EXTREME",
        "S4": "81.33|49.00|33|27:1.00:TX 18/45,24:2.00:TX 18/45,21:2.00:TX 18/45,18:2.00:TX 18/45,15:4.00:TX 18/45,12:4.00:TX 18/45,9:7.00:TX 18/45,6:10.00:TX 18/45,3:17.00:TX 18/45|43.97|117.59|66.3|TX 18/45=388/600|HIGH:DENSITY_HIGH,MEDIUM:LONG_TTS_EXPOSURE",
        "S5": "83.33|51.00|33|30:1.00:TX 18/45,27:2.00:TX 18/45,24:2.00:TX 18/45,21:2.00:EAN50,18:2.00:EAN50,15:3.00:EAN50,12:4.00:EAN50,9:8.00:EAN50,6:9.00:O2 100%,3:18.00:O2 100%|63.24|111.03|67.0|TX 18/45=5963/5568,EAN50=2309/2200,O2 100%=1227/1400|CRITICAL:GAS_VOLUME,CRITICAL:GAS_VOLUME,HIGH:DENSITY_HIGH,HIGH:NO_O2_BREAKS,MEDIUM:ICD_FIFTHS,MEDIUM:LONG_TTS_EXPOSURE,LOW:CNS",
        "S6": "58.67|28.00|24|21:1.00:EAN50,18:1.00:EAN50,15:1.00:EAN50,12:3.00:EAN50,9:4.00:EAN50,6:6.00:O2 100%,3:12.00:O2 100%|43.11|78.23|52.6|TX 21/35=4277/4800,EAN50=599/2200,O2 100%=599/1400|",
        "S7": "57.00|13.00|12|9:2.00:EAN32,6:3.00:O2 100%,3:8.00:O2 100%|26.05|70.94|63.4|EAN32=5593/4800,O2 100%=392/1400|CRITICAL:GAS_VOLUME,MEDIUM:ALTITUDE_DIVING",
        "S8": "80.33|43.00|27|33:1.00:EAN32,21:1.00:EAN50,18:2.00:EAN50,15:3.00:EAN50,12:4.00:EAN50,9:7.00:EAN50,6:9.00:O2 100%,3:16.00:O2 100%|60.79|107.52|67.1|EAN32=373/2200,TX 18/45=5641/5568,EAN50=949/2200,O2 100%=822/1400|CRITICAL:GAS_VOLUME,HIGH:DENSITY_HIGH,HIGH:ICD_N2,HIGH:NO_O2_BREAKS,MEDIUM:END_HIGH,MEDIUM:ICD_FIFTHS,MEDIUM:LONG_TTS_EXPOSURE,LOW:CNS",
        "S9a": "167.73|121.00|42|48:1.00:TX 23/37,39:1.00:TX 32/46,33:2.00:TX 32/46,30:3.00:TX 32/46,27:3.00:TX 32/46,24:5.00:TX 32/46,21:4.00:EAN50,18:5.00:EAN50,15:7.00:EAN50,12:10.00:EAN50,9:17.00:EAN50,6:21.00:O2 100%,3:42.00:O2 100%|140.27|235.40|68.1|TX 23/37=459/2400,TX 15/67=8736/9600,TX 32/46=1396/2400,EAN50=2278/2400,O2 100%=2006/2400|CRITICAL:CNS,HIGH:ICD_N2,HIGH:NO_O2_BREAKS,HIGH:LONG_TTS_EXPOSURE,MEDIUM:GF_EXTREME,MEDIUM:ICD_FIFTHS,LOW:GAS_ALERT_LIMITS,LOW:GAS_ALERT_LIMITS,LOW:GAS_ALERT_LIMITS",
        "BM70": "travel:TX 26/28:-,bottom:TX 17/59:-,deco:TX 32/41:39.0,deco:EAN50:21.0,deco:O2 100%:6.0",
        "SI": "53.36|13|17.29|39.84|20.62|20.58|375.0",
        "S9b": "28.44|0.00|0||86.53|41.12|52.0|EAN39=2764/4800|HIGH:CNS,HIGH:SHORT_SURFACE_INTERVAL"
    ]

    static func fp(_ r: DivePlanResult) -> String {
        [
            fmt(r.totalRuntimeMin, 2), fmt(r.totalDecoMin, 2), String(r.firstStopM),
            r.stops.map { "\($0.depthM):\(fmt($0.durationMin, 2)):\($0.gas.displayName)" }.joined(separator: ","),
            fmt(r.oxygen.endCnsPct, 2), fmt(r.oxygen.diveOtu, 2), fmt(r.endSurfaceGfPct, 1),
            r.gasConsumption.map { "\($0.gas.displayName)=\(kRound($0.totalRequiredLitres))/\(kRound($0.availableLitres))" }.joined(separator: ","),
            r.alerts.map { "\($0.level.rawValue.uppercased()):\($0.code.rawValue)" }.joined(separator: ",")
        ].joined(separator: "|")
    }

    static func g(_ id: String, _ o2: Int, _ he: Int, _ role: GasRole, l: Double = 12.0, bar: Double = 200.0, n: Int = 1) -> Gas {
        Gas.fromPercent(id, o2, he, role, cylinder: CylinderConfig(waterVolumeL: l, fillPressureBar: bar, count: n))
    }

    static func input(_ general: GeneralSettings, _ gases: [Gas], _ levels: [DiveLevel]) -> DivePlanInput {
        var i = DivePlanInput()
        i.general = general; i.gases = gases; i.levels = levels
        return i
    }

    static func scenarios() -> [(String, DivePlanResult)] {
        var user = GeneralSettings(); user.gasSelectionMode = .userInputGas
        let air = g("air", 21, 0, .bottom, n: 2)
        let tx = g("tx", 18, 45, .bottom, l: 12.0, bar: 232.0, n: 2)
        let ean50 = g("ean50", 50, 0, .deco, l: 11.0)
        let o2 = g("o2", 100, 0, .deco, l: 7.0)
        var out: [(String, DivePlanResult)] = []

        var g1 = user; g1.gfLow = 20; g1.gfHigh = 80
        out.append(("S1", DivePlannerEngine.planDive(input(g1, [air, ean50, o2], [DiveLevel("a", 40, 20)]))))
        let s2In = input(user, [tx, ean50, o2], [DiveLevel("a", 60, 25)])
        out.append(("S2", DivePlannerEngine.planDive(s2In)))
        var g3 = user; g3.gfLow = 100; g3.gfHigh = 100
        out.append(("S3", DivePlannerEngine.planDive(input(g3, [air], [DiveLevel("a", 30, 30)]))))
        var g4 = user; g4.planningType = .ccr
        out.append(("S4", DivePlannerEngine.planDive(input(g4, [g("dil", 18, 45, .diluent, l: 3.0), g("bo", 50, 0, .bailout, l: 11.0)], [DiveLevel("a", 60, 25)]))))
        var s5In = s2In; s5In.consumption.bottomReserveRule = .rockBottom; s5In.consumption.decoReserveRule = .lostGas
        out.append(("S5", DivePlannerEngine.planDive(s5In)))
        var g6 = user; g6.decoModel = .vpmB
        out.append(("S6", DivePlannerEngine.planDive(input(g6, [g("t2", 21, 35, .bottom, n: 2), ean50, o2], [DiveLevel("a", 45, 25)]))))
        var g7 = user; g7.elevationM = 1500; g7.salinityPreset = .fresh
        var s7In = input(g7, [g("e32", 32, 0, .bottom, n: 2), o2], [DiveLevel("a", 30, 40)]); s7In.gasBreaks.enabled = true
        out.append(("S7", DivePlannerEngine.planDive(s7In)))
        out.append(("S8", DivePlannerEngine.planDive(input(user, [g("tv", 32, 0, .travel, l: 11.0), tx, ean50, o2], [DiveLevel("a", 60, 20), DiveLevel("b", 40, 10)]))))
        var base = DivePlanInput(); base.general.gfLow = 70; base.general.gfHigh = 70
        let day = DivePlannerEngine.planMultiDiveDay(base, [
            MultiDiveEntry(id: "d1", label: "Dive 1", surfaceIntervalBeforeMin: 0, levels: [DiveLevel("a", 43, 15), DiveLevel("b", 82, 20)]),
            MultiDiveEntry(id: "d2", label: "Dive 2", surfaceIntervalBeforeMin: 90, levels: [DiveLevel("c", 25, 25)])
        ], includeContingenciesForIndex: -1)
        out.append(("S9a", day.diveResults[0]))
        out.append(("S9b", day.diveResults[1]))
        return out
    }

    /// Compares field by field: numbers within a small tolerance (libm may differ in the last bits), text exactly.
    func assertParity(_ id: String, _ actual: String, _ expected: String) {
        let a = actual.components(separatedBy: "|"), e = expected.components(separatedBy: "|")
        XCTAssertEqual(a.count, e.count, "\(id) field count")
        guard a.count == e.count else { return }
        let tol: [Int: Double] = [0: 0.05, 1: 0.05, 4: 0.1, 5: 0.2, 6: 0.2]
        for i in 0..<e.count {
            if let t = tol[i], let x = Double(a[i]), let y = Double(e[i]) {
                XCTAssertEqual(x, y, accuracy: t, "\(id) field \(i)")
            } else if i == 7 {
                let ag = a[i].components(separatedBy: ","), eg = e[i].components(separatedBy: ",")
                XCTAssertEqual(ag.count, eg.count, "\(id) gas count: \(a[i]) vs \(e[i])")
                for (x, y) in zip(ag, eg) {
                    let xp = x.components(separatedBy: "="), yp = y.components(separatedBy: "=")
                    XCTAssertEqual(xp[0], yp[0], "\(id) gas name")
                    let xv = xp[1].components(separatedBy: "/").compactMap(Double.init), yv = yp[1].components(separatedBy: "/").compactMap(Double.init)
                    XCTAssertEqual(xv[0], yv[0], accuracy: 2.0, "\(id) \(xp[0]) required litres")
                    XCTAssertEqual(xv[1], yv[1], accuracy: 0.5, "\(id) \(xp[0]) available litres")
                }
            } else {
                XCTAssertEqual(a[i], e[i], "\(id) field \(i)")
            }
        }
    }

    func testScenariosMatchKotlinEngine() {
        for (id, r) in Self.scenarios() where !["BM70", "SI"].contains(id) {
            let line = Self.fp(r)
            print("DIAG SWIFT PARITY|\(id)|\(line)")
            guard let expected = Self.kotlinReference[id] else { XCTFail("no reference for \(id)"); continue }
            assertParity(id, line, expected)
        }
    }

    /// Best GAS Mix at 70 m and the Surface Interval calculator after S2 must match the Kotlin BM70/SI lines.
    func testBestMixAndSurfaceIntervalMatchKotlin() {
        var lv = DivePlanInput()
        lv.levels = [DiveLevel("x", 70, 20)]
        let bm = GasCalculator.recommendBestMix(maxDepthM: 70, general: GeneralSettings(), dive: DiveSettings(),
                                                existingGases: [], alerts: AlertSettings(), levels: lv.levels,
                                                previousTissues: nil, surfaceIntervalMin: 0, consumption: ConsumptionSettings())
        let bmLine = bm.plannerGases.map { "\($0.role.rawValue):\($0.displayName):\($0.modOverrideM.map { String($0) } ?? "-")" }.joined(separator: ",")
        print("DIAG SWIFT PARITY|BM70|" + bmLine)
        XCTAssertEqual(bmLine, Self.kotlinReference["BM70"])
        let s2 = Self.scenarios().first { $0.0 == "S2" }!.1
        let si = TissuesAndBuhlmann.estimateSurfaceInterval(endTissues: s2.endTissues, endCnsPct: s2.oxygen.endCnsPct, surfaceIntervalMin: 60,
                                                            env: s2.env, nextDiveDepthM: 25, nextDiveGas: Gas.fromPercent("x", 32, 0, .bottom),
                                                            general: GeneralSettings())
        let siLine = [fmt(si.remainingExcessInertPct, 2), String(si.leadingCompartmentNumber), fmt(si.surfaceGfAfterPct, 2),
                                         fmt(si.cnsAfterPct, 2), fmt(si.nextDiveNdlMin, 2), fmt(si.freshNdlMin, 2), String(si.estimatedCleanTimeMin)].joined(separator: "|")
        print("DIAG SWIFT PARITY|SI|" + siLine)
        assertParity("SI", siLine, Self.kotlinReference["SI"]!)
        XCTAssertLessThan(si.surfaceGfAfterPct, si.surfaceGfBeforePct)
    }
}
