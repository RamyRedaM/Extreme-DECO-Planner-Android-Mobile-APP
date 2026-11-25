import SwiftUI
import DecoEngine

struct GasesView: View {
    @EnvironmentObject var store: PlannerStore

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HeroBanner(image: "img_deco_tanks", title: "Gas Blending & Cylinder Kit",
                               subtitle: store.input.general.gasSelectionMode.label)
                    modeCard
                    if let rec = store.bestMixRec { recommendationCard(rec) }
                    quickAddCard
                    ForEach(store.input.gases) { gas in GasEditor(gas: gas) }
                }.padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Gases")
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var modeCard: some View {
        Card(title: "Gas Selection", systemImage: "wand.and.stars") {
            Picker("Mode", selection: Binding(get: { store.input.general.gasSelectionMode }, set: { m in
                if m == .bestGasMix { store.applyBestMixRecommendation() } else { store.setGasSelectionMode(m) }
            })) {
                ForEach(GasSelectionMode.allCases, id: \.self) { Text($0.label).tag($0) }
            }.pickerStyle(.segmented)
            Text(store.input.general.gasSelectionMode == .bestGasMix
                 ? "The planner picks bottom, travel and deco mixes for the deepest level and resizes cylinders to the plan."
                 : "You choose every gas and cylinder. Editing a mix in Best mode switches here automatically.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private func recommendationCard(_ rec: MixRecommendation) -> some View {
        if let bb = rec.bailoutBottomGas {
            ccrRecommendationCard(rec, bb)
        } else {
            ocRecommendationCard(rec)
        }
    }

    private func cylTag(_ g: Gas) -> String {
        "\(g.cylinder.count)×\(kRound(g.cylinder.waterVolumeL))L @ \(kRound(g.cylinder.fillPressureBar))b"
    }

    private func ccrRecommendationCard(_ rec: MixRecommendation, _ bb: Gas) -> some View {
        Card(title: "CCR Best GAS Mix", systemImage: "arrow.triangle.2.circlepath") {
            HStack(spacing: 8) {
                StatTile(label: "Diluent · \(cylTag(rec.bottomGas))", value: rec.bottomGas.displayName, color: Theme.gasColor(rec.bottomGas))
                StatTile(label: "Bottom bailout · \(cylTag(bb))", value: bb.displayName, color: Theme.gasColor(bb))
            }
            if !rec.decoGases.isEmpty {
                HStack(spacing: 8) {
                    ForEach(rec.decoGases) { g in
                        StatTile(label: "@ \(kRound(g.modOverrideM ?? 0)) m · \(cylTag(g))", value: g.displayName, color: Theme.gasColor(g))
                    }
                }
            }
            Text("Diluent ppO₂ \(rec.ppO2AtMaxDepth.f(2)) bar at max · END \(UnitsAndPressure.formatDepth(rec.endAtMaxDepthM, store.units)) · ρ \(rec.densityAtMaxDepthGL.f(1)) g/L")
                .font(.caption).foregroundStyle(.secondary)
            ForEach(rec.notes, id: \.self) { Text("• \($0)").font(.caption2).foregroundStyle(.secondary) }
            if store.input.general.gasSelectionMode != .bestGasMix {
                Button("Apply recommendation") { store.applyBestMixRecommendation() }.buttonStyle(.borderedProminent)
            }
        }
    }

    private func ocRecommendationCard(_ rec: MixRecommendation) -> some View {
        Card(title: rec.isRecreational ? "REC Dive (Within NDL)" : "Best GAS Mix Recommendation", systemImage: "star.circle") {
            HStack(spacing: 8) {
                StatTile(label: "Bottom", value: rec.bottomGas.displayName, color: Theme.gasColor(rec.bottomGas))
                StatTile(label: "Travel", value: rec.travelGasDisplay.displayName, color: Theme.gasColor(rec.travelGasDisplay))
            }
            HStack(spacing: 8) {
                StatTile(label: "21 m deco", value: rec.deco21mEan50?.displayName ?? "-")
                StatTile(label: "6 m deco", value: rec.deco6mO2?.displayName ?? "-")
                if let deep = rec.deepDecoGas { StatTile(label: "Deep deco", value: deep.displayName) }
            }
            Text("MOD \(UnitsAndPressure.formatDepth(rec.modM, store.units)) · Min \(UnitsAndPressure.formatDepth(rec.minDepthM, store.units)) · END \(UnitsAndPressure.formatDepth(rec.endAtMaxDepthM, store.units)) · ρ \(rec.densityAtMaxDepthGL.f(1)) g/L · ppO₂ \(rec.ppO2AtMaxDepth.f(2))")
                .font(.caption).foregroundStyle(.secondary)
            if !rec.travelRangeNote.isEmpty { Text(rec.travelRangeNote).font(.caption2).foregroundStyle(.secondary) }
            ForEach(rec.notes, id: \.self) { Text("• \($0)").font(.caption2).foregroundStyle(.secondary) }
            if store.input.general.gasSelectionMode != .bestGasMix {
                Button("Apply recommendation") { store.applyBestMixRecommendation() }.buttonStyle(.borderedProminent)
            }
        }
    }

    private var quickAddCard: some View {
        Card(title: "Quick Add", systemImage: "plus.circle") {
            let presets: [(String, Int, Int, GasRole)] = [
                ("Air", 21, 0, .bottom), ("EAN32", 32, 0, .bottom), ("TX 21/35", 21, 35, .bottom), ("TX 18/45", 18, 45, .bottom),
                ("TX 15/55", 15, 55, .bottom), ("TX 10/70", 10, 70, .bottom), ("EAN32 travel", 32, 0, .travel),
                ("EAN50", 50, 0, .deco), ("EAN80", 80, 0, .deco), ("O2", 100, 0, .deco), ("EAN50 bailout", 50, 0, .bailout)
            ]
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 6)], spacing: 6) {
                ForEach(presets, id: \.0) { p in
                    Button(p.0) { store.addQuickGas(p.1, p.2, store.input.general.planningType == .ccr && p.3 == .bottom ? .diluent : p.3) }
                        .buttonStyle(.bordered).font(.caption)
                }
            }
        }
    }
}

struct GasEditor: View {
    @EnvironmentObject var store: PlannerStore
    let gas: Gas

    var body: some View {
        let env = UnitsAndPressure.buildEnvironment(store.input.general)
        let maxDepth = store.activeDive.levels.map(\.depthM).max() ?? 40
        let evalDepth = GasCalculator.displayEvalDepthM(gas, maxDepth, store.input.dive, env)
        let m = GasCalculator.gasMetrics(gas, evalDepth, env, store.input.dive)
        let usage = store.activeResult?.gasConsumption.first { $0.gas.id == gas.id }
        let u = store.units
        Card(title: gas.displayName, systemImage: "circle.fill") {
            HStack {
                Picker("Role", selection: Binding(get: { gas.role }, set: { v in var g = gas; g.role = v; store.updateGas(g) })) {
                    ForEach(GasRole.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                Spacer()
                Toggle("Enabled", isOn: Binding(get: { gas.enabled }, set: { v in var g = gas; g.enabled = v; store.updateGas(g) })).labelsHidden()
                Button(role: .destructive) { store.removeGas(gas.id) } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
            }
            StepperRow(label: "O₂", value: gas.o2Pct, range: 5...100, suffix: "%") { v in
                store.updateGas(Gas.fromPercent(gas.id, v, min(gas.hePct, 100 - v), gas.role, enabled: gas.enabled, modOverrideM: gas.modOverrideM, cylinder: gas.cylinder))
            }
            StepperRow(label: "He", value: gas.hePct, range: 0...(100 - gas.o2Pct), suffix: "%") { v in
                store.updateGas(Gas.fromPercent(gas.id, gas.o2Pct, v, gas.role, enabled: gas.enabled, modOverrideM: gas.modOverrideM, cylinder: gas.cylinder))
            }
            NumberField(label: "Cylinder water volume", value: gas.cylinder.waterVolumeL, unit: "L", decimals: 1) { v in
                var g = gas; g.cylinder.waterVolumeL = min(max(v, 0.5), 30); store.updateGas(g)
            }
            NumberField(label: "Fill pressure", value: gas.cylinder.fillPressureBar, unit: "bar") { v in
                var g = gas; g.cylinder.fillPressureBar = min(max(v, 50), 300); store.updateGas(g)
            }
            StepperRow(label: "Cylinders", value: gas.cylinder.count, range: 1...4) { v in var g = gas; g.cylinder.count = v; store.updateGas(g) }
            NumberField(label: "MOD override (0 = auto)", value: gas.modOverrideM ?? 0, unit: "m") { v in
                var g = gas; g.modOverrideM = v > 0 ? v : nil; store.updateGas(g)
            }
            Divider()
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 3), spacing: 6) {
                StatTile(label: "MOD", value: UnitsAndPressure.formatDepth(m.modM, u))
                StatTile(label: "Min depth", value: UnitsAndPressure.formatDepth(m.minDepthM, u))
                StatTile(label: "END @\(kRound(UnitsAndPressure.depthDisplay(evalDepth, u)))", value: UnitsAndPressure.formatDepth(m.endM, u),
                         color: m.endM > store.input.dive.maxEndM ? Theme.color(.high) : .primary)
                StatTile(label: "Density", value: "\(m.densityGL.f(2)) g/L", color: m.densityGL > store.input.dive.maxGasDensityGL ? Theme.color(.high) : .primary)
                StatTile(label: "ppO₂", value: m.ppO2.f(2))
                StatTile(label: "Available", value: UnitsAndPressure.formatVolume(gas.cylinder.availableLitres, u))
            }
            if let c = usage {
                Text("Required \(UnitsAndPressure.formatVolume(c.totalRequiredLitres, u)) · \(c.isSufficient ? "sufficient" : "INSUFFICIENT")")
                    .font(.caption.bold()).foregroundStyle(c.isSufficient ? Theme.ok : Theme.color(.critical))
            }
        }
    }
}
