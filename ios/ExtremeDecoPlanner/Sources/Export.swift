import SwiftUI
import UIKit
import DecoEngine

/// CSV and PDF exports (same content as ExportManager.kt on Android), shared through the iOS share sheet.
enum ExportBuilder {
    static func csv(_ day: MultiDiveDayResult, _ index: Int) -> String {
        guard let res = day.diveResults[safe: index] else { return "" }
        let u = res.input.general.units
        let df = DateFormatter(); df.locale = Locale(identifier: "en_US_POSIX"); df.dateFormat = "yyyy-MM-dd HH:mm"
        var s = ""
        func line(_ t: String = "") { s += t + "\n" }
        func d(_ m: Double, _ n: Int = 1) -> String { fmt(UnitsAndPressure.depthDisplay(m, u), n) }
        line("Extreme DECO Planner Ver \(appVersion) by Ramy.DECO, Technical Dive Plan Export")
        line("Generated,\(df.string(from: Date()))")
        line("Dive Number,\(index + 1) of \(day.diveResults.count)")
        line("Surface Interval Before (min),\(day.surfaceIntervalsMin[safe: index] ?? 0)")
        line("Model,\(res.input.general.decoModel.label)")
        line("Gradient Factors,\(res.input.general.gfLow)/\(res.input.general.gfHigh)")
        line("Planning Type,\(res.input.general.planningType.label)")
        line("Salinity (kg/m3),\(res.env.salinityKgM3)")
        line("Elevation (m),\(res.env.elevationM)")
        line("Max Depth (\(u.depthUnit)),\(d(res.maxDepthM))")
        line("Total Runtime (min),\(fmt(res.totalRuntimeMin, 2))")
        line("Total Deco (min),\(fmt(res.totalDecoMin, 2))")
        line("TTS (min),\(fmt(res.ttsMin, 2))")
        line("Start CNS (%),\(fmt(res.oxygen.startCnsPct, 1))")
        line("End CNS (%),\(fmt(res.oxygen.endCnsPct, 1))")
        line("Dive OTU,\(fmt(res.oxygen.diveOtu, 1))")
        line("Cumulative Daily OTU,\(fmt(res.oxygen.dailyOtu, 1))")
        line("Surface GF (%),\(fmt(res.endSurfaceGfPct, 1))")
        line()
        line("--- RUNTIME SCHEDULE ---")
        line("Action,FromDepth(\(u.depthUnit)),ToDepth(\(u.depthUnit)),Duration(min),Runtime(min),Runtime(mm:ss),TTS(min),TTS(mm:ss),Gas,ppO2(bar),ppN2(bar),ppHe(bar),END(\(u.depthUnit)),Density(g/L),Ceiling(\(u.depthUnit)),GF99(%),SurfGF(%)")
        for seg in res.segments {
            let tts = max(res.totalRuntimeMin - seg.runtimeMin, 0)
            line([seg.type.label, d(seg.fromDepthM), d(seg.toDepthM), fmt(seg.durationMin, 2), fmt(seg.runtimeMin, 2),
                  UnitsAndPressure.formatRuntimeMmSs(seg.runtimeMin), fmt(tts, 2), UnitsAndPressure.formatRuntimeMmSs(tts), seg.gas.displayName,
                  fmt(seg.ppO2End, 2), fmt(seg.ppN2End, 2), fmt(seg.ppHeEnd, 2), d(seg.endM), fmt(seg.densityGL, 2), d(seg.ceilingM),
                  fmt(seg.gf99Pct, 1), fmt(seg.surfaceGfPct, 1)].joined(separator: ","))
        }
        line()
        line("--- GAS CONSUMPTION & RESERVES ---")
        line("Gas,Role,Used(\(u.volUnit)),Reserve(\(u.volUnit)),Required(\(u.volUnit)),Available(\(u.volUnit)),Turn(\(u.pressureUnit)),MinPressure(\(u.pressureUnit)),Sufficient")
        for gc in res.gasConsumption {
            line([gc.gas.displayName, gc.gas.role.label,
                  fmt(UnitsAndPressure.volumeDisplay(gc.usedLitres, u), 1), fmt(UnitsAndPressure.volumeDisplay(gc.reserveLitres, u), 1),
                  fmt(UnitsAndPressure.volumeDisplay(gc.totalRequiredLitres, u), 1), fmt(UnitsAndPressure.volumeDisplay(gc.availableLitres, u), 1),
                  fmt(UnitsAndPressure.pressureDisplay(gc.turnPressureBar, u), 0), fmt(UnitsAndPressure.pressureDisplay(gc.minPressureBar, u), 0),
                  String(gc.isSufficient)].joined(separator: ","))
        }
        line()
        line("--- 16-COMPARTMENT TISSUE TENSIONS (bar N2+He) ---")
        let comp = (1...16).map { "C\($0)_Inert(bar),C\($0)_Sat(%)" }.joined(separator: ",")
        line("Runtime(min),Depth(\(u.depthUnit)),P_amb(bar),Ceiling(\(u.depthUnit)),GF99(%),\(comp)")
        let step = max(res.tissueTimeline.count / 120, 1)
        for (i, snap) in res.tissueTimeline.enumerated() where i % step == 0 || i == res.tissueTimeline.count - 1 {
            let cols = (0..<16).map { "\(fmt(snap.tissues.compartments[$0].pInert, 3)),\(fmt(snap.compartmentSaturationPct[$0], 1))" }.joined(separator: ",")
            line("\(fmt(snap.runtimeMin, 2)),\(d(snap.depthM)),\(fmt(snap.pAmbBar, 3)),\(d(snap.ceilingM)),\(fmt(snap.gf99Pct, 1)),\(cols)")
        }
        line()
        line("DISCLAIMER,Planning purposes only. Cross-check independently and dive within training limits.")
        return s
    }

    static var appVersion: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "1.27.2" }

    static func pdf(_ day: MultiDiveDayResult, _ index: Int) -> Data {
        guard let res = day.diveResults[safe: index] else { return Data() }
        let u = res.input.general.units
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        return renderer.pdfData { ctx in
            var y: CGFloat = 40
            func newPageIfNeeded(_ h: CGFloat) { if y + h > page.height - 40 { ctx.beginPage(); y = 40 } }
            func text(_ t: String, _ size: CGFloat = 10, bold: Bool = false, color: UIColor = .black) {
                let font = bold ? UIFont.boldSystemFont(ofSize: size) : UIFont.systemFont(ofSize: size)
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
                let rect = (t as NSString).boundingRect(with: CGSize(width: page.width - 80, height: .greatestFiniteMagnitude), options: .usesLineFragmentOrigin, attributes: attrs, context: nil)
                newPageIfNeeded(rect.height + 4)
                (t as NSString).draw(in: CGRect(x: 40, y: y, width: page.width - 80, height: rect.height), withAttributes: attrs)
                y += rect.height + 4
            }
            ctx.beginPage()
            text("Extreme DECO Planner v\(appVersion)", 18, bold: true, color: UIColor(Theme.accent))
            text("\(res.input.general.decoModel.label) · GF \(res.input.general.gfLow)/\(res.input.general.gfHigh) · \(res.input.general.planningType.label) · Dive \(index + 1) of \(day.diveResults.count)", 10)
            y += 6
            text("Max \(UnitsAndPressure.formatDepth(res.maxDepthM, u)) · Runtime \(UnitsAndPressure.formatRuntimeMmSs(res.totalRuntimeMin)) · Deco \(kRound(res.totalDecoMin)) min · TTS \(kRound(res.ttsMin)) min", 11, bold: true)
            text("CNS \(fmt(res.oxygen.endCnsPct, 1))% · OTU \(fmt(res.oxygen.diveOtu, 1)) (daily \(fmt(res.oxygen.dailyOtu, 1))) · Surface GF \(fmt(res.endSurfaceGfPct, 1))%", 10)
            if let r = res.abortedReason { text("ABORTED: \(r)", 11, bold: true, color: .systemRed) }
            y += 8
            text("Runtime schedule", 13, bold: true)
            text("Action · Depth · Time · Runtime · Gas · ppO₂", 9, bold: true)
            for s in res.segments {
                let depth = s.fromDepthM == s.toDepthM ? "\(kRound(UnitsAndPressure.depthDisplay(s.toDepthM, u)))" : "\(kRound(UnitsAndPressure.depthDisplay(s.fromDepthM, u)))→\(kRound(UnitsAndPressure.depthDisplay(s.toDepthM, u)))"
                text("\(s.type.label.padding(toLength: 10, withPad: " ", startingAt: 0))  \(depth) \(u.depthUnit)   \(fmt(s.durationMin, 1)) min   RT \(UnitsAndPressure.formatRuntimeMmSs(s.runtimeMin))   \(s.gas.displayName)   ppO₂ \(fmt(s.ppO2End, 2))", 9)
            }
            y += 8
            text("Gas requirements", 13, bold: true)
            for gc in res.gasConsumption {
                text("\(gc.gas.displayName) (\(gc.gas.role.label)): required \(UnitsAndPressure.formatVolume(gc.totalRequiredLitres, u)) of \(UnitsAndPressure.formatVolume(gc.availableLitres, u)), turn \(UnitsAndPressure.formatPressure(gc.turnPressureBar, u)) · \(gc.isSufficient ? "OK" : "INSUFFICIENT")",
                     9, color: gc.isSufficient ? .black : .systemRed)
            }
            y += 8
            text("Safety alerts", 13, bold: true)
            if res.alerts.isEmpty { text("No warnings.", 9) }
            for a in res.alerts { text("[\(a.level.label)] \(a.code.title): \(a.message)", 9, color: a.level == .medium ? .systemOrange : UIColor(Theme.color(a.level))) }
            if !res.contingencies.isEmpty {
                y += 8
                text("Contingencies", 13, bold: true)
                for c in res.contingencies + (res.ccrBailout.map { [$0] } ?? []) {
                    text("\(c.title): RT \(UnitsAndPressure.formatRuntimeMmSs(c.totalRuntimeMin)) · deco \(kRound(c.totalDecoMin)) min · stops " + c.stops.map { "\($0.depthM)m/\(kRound($0.durationMin))" }.joined(separator: " "), 9)
                }
            }
            y += 12
            text("Planning purposes only. Cross-check independently and dive within training limits.", 8, color: .darkGray)
        }
    }

    static func write(_ data: Data, name: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do { try data.write(to: url); return url } catch { return nil }
    }
}

struct ExportCard: View {
    @EnvironmentObject var store: PlannerStore
    let result: DivePlanResult
    @State private var csvURL: URL?
    @State private var pdfURL: URL?

    var body: some View {
        Card(title: "Export & Share", systemImage: "square.and.arrow.up") {
            HStack {
                if let u = pdfURL { ShareLink(item: u) { Label("Share PDF", systemImage: "doc.richtext") }.buttonStyle(.borderedProminent) }
                if let u = csvURL { ShareLink(item: u) { Label("Share CSV", systemImage: "tablecells") }.buttonStyle(.bordered) }
            }
            Text("PDF dive slate and full CSV (runtime, gas, 16-compartment tensions) for the selected dive.").font(.caption2).foregroundStyle(.secondary)
        }
        .task(id: result) { build() }
    }

    private func build() {
        guard let day = store.dayResult else { return }
        let i = store.activeDiveIndex
        let stamp = "Dive\(i + 1)-\(kRound(result.maxDepthM))m"
        csvURL = ExportBuilder.write(Data(ExportBuilder.csv(day, i).utf8), name: "ExtremeDECO-\(stamp).csv")
        pdfURL = ExportBuilder.write(ExportBuilder.pdf(day, i), name: "ExtremeDECO-\(stamp).pdf")
    }
}
