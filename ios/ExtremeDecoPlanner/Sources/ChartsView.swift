import SwiftUI
import Charts
import DecoEngine

struct ChartsView: View {
    @EnvironmentObject var store: PlannerStore
    @State private var scrub: Double? = nil

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HeroBanner(image: "img_charts_reef", title: "Decompression & Tissue Charts",
                               subtitle: "16-compartment saturation, inert gas tension, gas use & M-value envelope")
                    if let r = store.activeResult, r.abortedReason == nil {
                        profileChart(r)
                        ppChart(r)
                        gfChart(r)
                        saturationChart(r)
                        tensionChart(r)
                        gasChart(r)
                    } else {
                        Text("No plan to chart yet.").foregroundStyle(.secondary).padding(40)
                    }
                }.padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Charts")
        }
    }

    private var u: UnitSystem { store.units }
    private func dd(_ m: Double) -> Double { UnitsAndPressure.depthDisplay(m, u) }

    private func snapshot(_ r: DivePlanResult) -> TissueSnapshot? {
        guard !r.tissueTimeline.isEmpty else { return nil }
        guard let t = scrub else { return r.tissueTimeline.last }
        return r.tissueTimeline.min { abs($0.runtimeMin - t) < abs($1.runtimeMin - t) }
    }

    private func profileChart(_ r: DivePlanResult) -> some View {
        Card(title: "Depth Profile & Ceiling", systemImage: "chart.line.downtrend.xyaxis") {
            Chart {
                LineMark(x: .value("Run", 0.0), y: .value("Depth", 0.0), series: .value("S", "Depth"))
                ForEach(Array(r.segments.enumerated()), id: \.offset) { _, s in
                    LineMark(x: .value("Run", s.runtimeMin), y: .value("Depth", -dd(s.toDepthM)), series: .value("S", "Depth"))
                        .foregroundStyle(Theme.accent)
                }
                ForEach(Array(r.tissueTimeline.enumerated()), id: \.offset) { _, t in
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("Depth", -dd(t.ceilingM)), series: .value("S", "Ceiling"))
                        .foregroundStyle(Theme.color(.critical)).lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                }
                if let t = scrub { RuleMark(x: .value("Run", t)).foregroundStyle(.secondary) }
            }
            .chartYAxisLabel(u.depthUnit)
            .chartXAxisLabel("runtime (min)")
            .chartYAxis { AxisMarks { v in AxisGridLine(); AxisValueLabel { if let d = v.as(Double.self) { Text("\(kRound(-d))") } } } }
            .chartOverlay { proxy in
                GeometryReader { geo in
                    Rectangle().fill(.clear).contentShape(Rectangle())
                        .gesture(DragGesture(minimumDistance: 0).onChanged { g in
                            let x = g.location.x - geo[proxy.plotAreaFrame].origin.x
                            if let v: Double = proxy.value(atX: x) { scrub = max(0, min(v, r.totalRuntimeMin)) }
                        })
                }
            }
            .frame(height: 220)
            HStack {
                Text("Blue: depth · Red dashed: ceiling. Drag to inspect.").font(.caption2).foregroundStyle(.secondary)
                Spacer()
                if scrub != nil { Button("Reset") { scrub = nil }.font(.caption) }
            }
            if let s = snapshot(r) {
                Text("RT \(UnitsAndPressure.formatRuntimeMmSs(s.runtimeMin)) · \(UnitsAndPressure.formatDepth(s.depthM, u)) · \(s.gas.displayName) · ppO₂ \(s.ppO2.f(2)) · ceiling \(UnitsAndPressure.formatDepth(s.ceilingM, u)) · GF99 \(kRound(s.gf99Pct))%")
                    .font(.caption.monospacedDigit())
            }
        }
    }

    private func ppChart(_ r: DivePlanResult) -> some View {
        Card(title: "Partial Pressures", systemImage: "waveform.path.ecg") {
            Chart {
                ForEach(Array(r.tissueTimeline.enumerated()), id: \.offset) { _, t in
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("bar", t.ppO2), series: .value("Gas", "ppO₂")).foregroundStyle(by: .value("Gas", "ppO₂"))
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("bar", t.ppN2), series: .value("Gas", "ppN₂")).foregroundStyle(by: .value("Gas", "ppN₂"))
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("bar", t.ppHe), series: .value("Gas", "ppHe")).foregroundStyle(by: .value("Gas", "ppHe"))
                }
                RuleMark(y: .value("limit", store.input.dive.ppO2Deco)).foregroundStyle(Theme.color(.high).opacity(0.6)).lineStyle(StrokeStyle(dash: [3, 3]))
            }
            .chartForegroundStyleScale(["ppO₂": Theme.gasColor(Gas.fromPercent("o", 100, 0, .deco)), "ppN₂": Color.blue, "ppHe": Color.purple])
            .chartXAxisLabel("runtime (min)")
            .frame(height: 200)
        }
    }

    private func gfChart(_ r: DivePlanResult) -> some View {
        Card(title: "GF99 & Surface GF", systemImage: "percent") {
            Chart {
                ForEach(Array(r.tissueTimeline.enumerated()), id: \.offset) { _, t in
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("%", t.gf99Pct), series: .value("K", "GF99")).foregroundStyle(by: .value("K", "GF99"))
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("%", t.surfaceGfPct), series: .value("K", "Surface GF")).foregroundStyle(by: .value("K", "Surface GF"))
                }
                RuleMark(y: .value("GF High", Double(store.input.general.gfHigh))).foregroundStyle(.secondary).lineStyle(StrokeStyle(dash: [3, 3]))
            }
            .chartForegroundStyleScale(["GF99": Theme.accent, "Surface GF": Theme.color(.high)])
            .chartXAxisLabel("runtime (min)")
            .frame(height: 200)
        }
    }

    private func saturationChart(_ r: DivePlanResult) -> some View {
        let s = snapshot(r)
        return Card(title: "Compartment Saturation (% of M-value)", systemImage: "chart.bar") {
            if let s {
                Chart {
                    ForEach(Array(s.compartmentSaturationPct.enumerated()), id: \.offset) { i, v in
                        BarMark(x: .value("Compartment", "\(i + 1)"), y: .value("%", v))
                            .foregroundStyle(i == s.leadingCompartmentIndex ? Theme.color(.high) : (v > 100 ? Theme.color(.critical) : Theme.accent))
                    }
                    RuleMark(y: .value("M-value", 100.0)).foregroundStyle(Theme.color(.critical)).lineStyle(StrokeStyle(dash: [4, 3]))
                }
                .frame(height: 200)
                Text("At RT \(UnitsAndPressure.formatRuntimeMmSs(s.runtimeMin)) · leading compartment \(s.leadingCompartmentIndex + 1)").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }

    private func tensionChart(_ r: DivePlanResult) -> some View {
        Card(title: "Inert Gas Tension (fast, middle, slow)", systemImage: "chart.line.uptrend.xyaxis") {
            Chart {
                ForEach([0, 4, 8, 15], id: \.self) { ci in
                    ForEach(Array(r.tissueTimeline.enumerated()), id: \.offset) { _, t in
                        LineMark(x: .value("Run", t.runtimeMin), y: .value("bar", t.tissues.compartments[ci].pInert), series: .value("C", "C\(ci + 1)"))
                            .foregroundStyle(by: .value("C", "C\(ci + 1)"))
                    }
                }
                ForEach(Array(r.tissueTimeline.enumerated()), id: \.offset) { _, t in
                    LineMark(x: .value("Run", t.runtimeMin), y: .value("bar", t.pAmbBar), series: .value("C", "Ambient"))
                        .foregroundStyle(by: .value("C", "Ambient")).lineStyle(StrokeStyle(lineWidth: 1, dash: [2, 2]))
                }
            }
            .chartXAxisLabel("runtime (min)")
            .frame(height: 200)
        }
    }

    private func gasChart(_ r: DivePlanResult) -> some View {
        Card(title: "Gas Use vs Available", systemImage: "cylinder") {
            Chart {
                ForEach(Array(r.gasConsumption.enumerated()), id: \.offset) { _, c in
                    BarMark(x: .value("Gas", c.gas.displayName), y: .value("L", UnitsAndPressure.volumeDisplay(c.availableLitres, u)))
                        .foregroundStyle(by: .value("K", "Available")).position(by: .value("K", "Available"))
                    BarMark(x: .value("Gas", c.gas.displayName), y: .value("L", UnitsAndPressure.volumeDisplay(c.totalRequiredLitres, u)))
                        .foregroundStyle(by: .value("K", "Required")).position(by: .value("K", "Required"))
                }
            }
            .chartForegroundStyleScale(["Available": Color.gray.opacity(0.5), "Required": Theme.accent])
            .chartYAxisLabel(u.volUnit)
            .frame(height: 200)
        }
    }
}
