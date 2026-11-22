import SwiftUI
import Charts
import DecoEngine

/// Surface Interval Calculator (ZHL-16C), same logic as SurfaceIntervalCalculatorCard.kt.
struct SurfaceIntervalCard: View {
    @EnvironmentObject var store: PlannerStore
    let result: DivePlanResult
    @State private var siMinutes: Double = 90
    @State private var nextDepthM: Double = 24
    @State private var gasChoice = "planned"
    @State private var key = ""

    private static let presets = [30, 60, 90, 120, 180, 240, 360, 720]

    var body: some View {
        let idx = store.activeDiveIndex
        let day = store.dayResult
        let hasPrev = idx > 0 && (day?.diveResults.indices.contains(idx - 1) ?? false)
        let hasNext = store.dives.indices.contains(idx + 1)
        let sourceIdx = hasPrev ? idx - 1 : idx
        let target: Int? = hasPrev ? idx : (hasNext ? idx + 1 : nil)
        let source = day?.diveResults[safe: sourceIdx] ?? result
        let plannedNext = target.flatMap { day?.diveResults[safe: $0]?.input.gases }
        let nextGas: Gas = {
            switch gasChoice {
            case "air": return Gas.fromPercent("si_air", 21, 0, .bottom)
            case "ean32": return Gas.fromPercent("si_ean32", 32, 0, .bottom)
            default: return GasCalculator.defaultNextDiveGas(plannedNext, nextDepthM, source.input.dive, source.env)
            }
        }()
        let est = TissuesAndBuhlmann.estimateSurfaceInterval(endTissues: source.endTissues, endCnsPct: source.oxygen.endCnsPct,
                                                             surfaceIntervalMin: siMinutes, env: source.env, nextDiveDepthM: nextDepthM,
                                                             nextDiveGas: nextGas, general: store.input.general)
        let sourceLabel = store.dives[safe: sourceIdx]?.label ?? "Dive \(sourceIdx + 1)"
        let targetLabel = target.flatMap { store.dives[safe: $0]?.label } ?? "Next Dive"
        let u = store.units
        let resetKey = "\(sourceIdx)-\(target ?? -1)"

        return Card(title: "Surface Interval Calculator", systemImage: "timer") {
            Text("Off-gassing after \(sourceLabel) → \(targetLabel) · Bühlmann ZHL-16C").font(.caption).foregroundStyle(.secondary)
            HStack {
                Text("Surface interval").font(.subheadline)
                Spacer()
                Text("\(Int(siMinutes / 60))h \(String(format: "%02d", Int(siMinutes) % 60))m").font(.subheadline.monospacedDigit().bold())
            }
            Slider(value: $siMinutes, in: 0...720, step: 5)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(Self.presets, id: \.self) { p in
                        Button(p < 60 ? "\(p)m" : "\(p / 60)h\(p % 60 == 0 ? "" : "\(p % 60)")") { siMinutes = Double(p) }
                            .buttonStyle(.bordered).font(.caption)
                    }
                }
            }
            HStack {
                Text("Next dive depth").font(.subheadline)
                Spacer()
                Text(UnitsAndPressure.formatDepth(nextDepthM, u)).font(.subheadline.monospacedDigit())
            }
            Slider(value: $nextDepthM, in: 9...60, step: 1)
            Picker("Next dive gas", selection: $gasChoice) {
                Text(plannedNext == nil ? "Auto" : "Planned (\(GasCalculator.defaultNextDiveGas(plannedNext, nextDepthM, source.input.dive, source.env).displayName))").tag("planned")
                Text("Air").tag("air")
                Text("EAN32").tag("ean32")
            }.pickerStyle(.segmented)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
                StatTile(label: "Remaining inert gas", value: "\(kRound(est.remainingExcessInertPct))%")
                StatTile(label: "Leading comp (ZHL-16C)", value: "C\(est.leadingCompartmentNumber) · \(kRound(est.leadingSaturationAfterPct))%")
                StatTile(label: "Surface GF · CNS", value: "\(kRound(est.surfaceGfAfterPct))% · \(kRound(est.cnsAfterPct))%")
                StatTile(label: "Next NDL @ \(UnitsAndPressure.formatDepth(nextDepthM, u))", value: "\(kRound(est.nextDiveNdlMin)) / \(kRound(est.freshNdlMin)) min")
            }
            Text("Leading compartment N₂ half-time \(kRound(est.leadingN2HalfTimeMin)) min · Surface GF before \(kRound(est.surfaceGfBeforePct))% · tissues near clean in about \(kRound(est.estimatedCleanTimeMin / 60)) h")
                .font(.caption2).foregroundStyle(.secondary)

            Chart {
                ForEach(0..<16, id: \.self) { i in
                    BarMark(x: .value("C", "\(i + 1)"), y: .value("%", est.compartmentLoadsBeforePct[i]))
                        .foregroundStyle(Theme.accent.opacity(0.25))
                    BarMark(x: .value("C", "\(i + 1)"), y: .value("%", est.compartmentLoadsAfterPct[i]))
                        .foregroundStyle(i + 1 == est.leadingCompartmentNumber ? Theme.color(.high) : Theme.accent)
                }
            }
            .chartXAxis(.hidden)
            .frame(height: 120)
            HStack {
                Text("C1 (4m fast)"); Spacer(); Text("Faded = Surfacing · Solid = Remaining"); Spacer(); Text("C16 (635m slow)")
            }.font(.system(size: 9)).foregroundStyle(.secondary)

            HStack {
                if let t = target {
                    Button("Apply \(Int(siMinutes)) min to \(targetLabel)") { store.updateSurfaceInterval(t, siMinutes) }.buttonStyle(.borderedProminent)
                } else {
                    Button("Add repetitive dive after \(Int(siMinutes)) min") { store.addRepetitiveDive(surfaceIntervalMin: siMinutes) }.buttonStyle(.borderedProminent)
                }
            }.font(.caption)
        }
        .onAppear { reset(resetKey, target: target, hasPrev: hasPrev, hasNext: hasNext, source: source) }
        .onChange(of: resetKey) { k in reset(k, target: target, hasPrev: hasPrev, hasNext: hasNext, source: source) }
    }

    private func reset(_ k: String, target: Int?, hasPrev: Bool, hasNext: Bool, source: DivePlanResult) {
        guard k != key else { return }
        key = k
        let initial = target.flatMap { store.dives[safe: $0]?.surfaceIntervalBeforeMin }.flatMap { $0 > 0 ? $0 : nil } ?? 90
        siMinutes = min(max(initial, 0), 720)
        let idx = store.activeDiveIndex
        let depth: Double? = hasPrev ? store.dives[safe: idx]?.levels.map(\.depthM).max()
            : hasNext ? store.dives[safe: idx + 1]?.levels.map(\.depthM).max()
            : max(source.maxDepthM - 6, 18)
        nextDepthM = min(max(depth ?? 24, 9), 60)
        gasChoice = "planned"
    }
}
