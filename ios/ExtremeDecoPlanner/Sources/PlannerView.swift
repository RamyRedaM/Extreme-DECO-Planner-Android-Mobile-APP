import SwiftUI
import DecoEngine

struct PlannerView: View {
    @EnvironmentObject var store: PlannerStore
    @State private var showAllAlerts = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HeroBanner(image: "img_hero_planner", title: "Dive Planner",
                               subtitle: "\(store.input.general.decoModel.shortLabel) · GF \(store.input.general.gfLow)/\(store.input.general.gfHigh) · \(store.units.depthUnit)")
                    diveSelector
                    levelsCard
                    if let r = store.activeResult {
                        summaryCard(r)
                        alertsCard(r)
                        runtimeCard(r)
                        gasCard(r)
                        contingencyCard(r)
                        SurfaceIntervalCard(result: r)
                        ExportCard(result: r)
                    } else {
                        ProgressView("Computing plan…").padding(40)
                    }
                }.padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Planner")
            .toolbar { ToolbarItem(placement: .navigationBarTrailing) { HStack { if store.isComputing { ProgressView() }; StatusPill(result: store.activeResult) } } }
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var u: UnitSystem { store.units }

    // MARK: Dives

    private var diveSelector: some View {
        Card(title: "Multi-Dive Day", systemImage: "calendar") {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack {
                    ForEach(Array(store.dives.enumerated()), id: \.element.id) { i, d in
                        let r = store.dayResult?.diveResults[safe: i]
                        Button {
                            store.selectDive(i)
                        } label: {
                            HStack(spacing: 4) {
                                Circle().fill(r?.highestAlertLevel.map(Theme.color) ?? Theme.ok).frame(width: 8, height: 8)
                                Text(d.label)
                            }
                        }
                        .buttonStyle(.bordered)
                        .tint(i == store.activeDiveIndex ? Theme.accent : .gray)
                    }
                    Button { store.addRepetitiveDive() } label: { Label("Add", systemImage: "plus") }.buttonStyle(.bordered)
                }
            }
            if store.activeDiveIndex > 0 {
                NumberField(label: "Surface interval before this dive", value: store.activeDive.surfaceIntervalBeforeMin, unit: "min") { v in
                    store.updateSurfaceInterval(store.activeDiveIndex, v)
                }
                if let r = store.activeResult {
                    Text("Decayed CNS at start: \(kRound(r.input.previousCnsPct))% · Residual N₂/He active").font(.caption).foregroundStyle(.secondary)
                }
                Button(role: .destructive) { store.removeDive(store.activeDiveIndex) } label: { Label("Remove \(store.activeDive.label)", systemImage: "trash") }
                    .font(.caption)
            }
        }
    }

    private var levelsCard: some View {
        Card(title: "Dive Profile · \(store.activeDive.label)", systemImage: "chart.line.downtrend.xyaxis") {
            ForEach(Array(store.activeDive.levels.enumerated()), id: \.element.id) { i, l in
                VStack(spacing: 6) {
                    HStack {
                        Text("Level \(i + 1)").font(.subheadline.bold())
                        Spacer()
                        if store.activeDive.levels.count > 1 {
                            Button(role: .destructive) { store.removeLevel(l.id) } label: { Image(systemName: "minus.circle") }.buttonStyle(.borderless)
                        }
                    }
                    NumberField(label: "Depth", value: UnitsAndPressure.depthDisplay(l.depthM, u), unit: u.depthUnit) { v in
                        store.updateLevel(l.id, depthM: UnitsAndPressure.depthFromDisplay(v, u))
                    }
                    NumberField(label: "Time", value: l.timeMin, unit: "min") { v in store.updateLevel(l.id, timeMin: v) }
                }
                if i < store.activeDive.levels.count - 1 { Divider() }
            }
            if store.activeDive.levels.count < 6 {
                Button { store.addLevel() } label: { Label("Add level (multi-level dive)", systemImage: "plus.circle") }.font(.subheadline)
            }
        }
    }

    // MARK: Results

    private func summaryCard(_ r: DivePlanResult) -> some View {
        Card(title: "Plan Summary", systemImage: "gauge") {
            if let reason = r.abortedReason {
                Text(reason).font(.subheadline.bold()).foregroundStyle(Theme.color(.critical))
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                StatTile(label: "Runtime", value: UnitsAndPressure.formatRuntimeMmSs(r.totalRuntimeMin))
                StatTile(label: "Deco", value: "\(kRound(r.totalDecoMin)) min")
                StatTile(label: "TTS", value: "\(kRound(r.ttsMin)) min")
                StatTile(label: "Max depth", value: UnitsAndPressure.formatDepth(r.maxDepthM, u))
                StatTile(label: r.totalDecoMin > 0 ? "First stop" : "NDL",
                         value: r.totalDecoMin > 0 ? UnitsAndPressure.formatDepth(Double(r.firstStopM), u) : "\(kRound(r.ndlMin)) min")
                StatTile(label: "Surface GF", value: "\(kRound(r.endSurfaceGfPct))%", color: r.endSurfaceGfPct > 90 ? Theme.color(.high) : .primary)
                StatTile(label: "CNS", value: "\(kRound(r.oxygen.endCnsPct))%",
                         color: r.oxygen.endCnsPct >= store.input.alerts.cnsErrorPct ? Theme.color(.critical) : r.oxygen.endCnsPct >= store.input.alerts.cnsWarnPct ? Theme.color(.high) : .primary)
                StatTile(label: "OTU", value: "\(kRound(r.oxygen.diveOtu))")
                StatTile(label: "Max ppO₂", value: r.oxygen.maxPpO2.f(2))
            }
        }
    }

    private func alertsCard(_ r: DivePlanResult) -> some View {
        Card(title: "Safety Alerts (\(r.alerts.count))", systemImage: "exclamationmark.shield") {
            if r.alerts.isEmpty {
                Label("No warnings for this plan", systemImage: "checkmark.seal.fill").foregroundStyle(Theme.ok)
            } else {
                HStack(spacing: 6) {
                    ForEach(AlertLevel.allCases, id: \.self) { lvl in
                        let n = r.alerts.filter { $0.level == lvl }.count
                        if n > 0 {
                            Text("\(n) \(lvl.label)").font(.caption2.bold()).padding(.horizontal, 6).padding(.vertical, 3)
                                .background(Capsule().fill(Theme.color(lvl).opacity(0.2))).foregroundStyle(Theme.color(lvl))
                        }
                    }
                }
                let shown = showAllAlerts ? r.alerts : Array(r.alerts.prefix(4))
                ForEach(Array(shown.enumerated()), id: \.offset) { _, a in AlertRow(alert: a) }
                if r.alerts.count > 4 {
                    Button(showAllAlerts ? "Show fewer" : "Show all \(r.alerts.count) alerts") { showAllAlerts.toggle() }.font(.caption)
                }
            }
        }
    }

    private func runtimeCard(_ r: DivePlanResult) -> some View {
        Card(title: "Runtime Table", systemImage: "tablecells") {
            HStack {
                Text("Depth").frame(width: 56, alignment: .leading)
                Text("Stop").frame(width: 50, alignment: .trailing)
                Text("Run").frame(width: 56, alignment: .trailing)
                Text("Gas").frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 8)
            }.font(.caption.bold()).foregroundStyle(.secondary)
            if store.input.general.planStyle == .compact {
                ForEach(Array(r.stops.enumerated()), id: \.offset) { _, s in
                    row(depth: UnitsAndPressure.formatDepth(Double(s.depthM), u), dur: s.durationMin, run: s.runtimeMin, gas: s.gas, note: s.isSwitch ? "switch" : nil)
                }
            } else {
                ForEach(Array(r.segments.enumerated()), id: \.offset) { _, s in
                    let depth = s.fromDepthM == s.toDepthM ? UnitsAndPressure.formatDepth(s.toDepthM, u)
                        : "\(kRound(UnitsAndPressure.depthDisplay(s.fromDepthM, u)))→\(kRound(UnitsAndPressure.depthDisplay(s.toDepthM, u)))"
                    row(depth: depth, dur: s.durationMin, run: s.runtimeMin, gas: s.gas, note: s.type.label + (s.setpoint.map { " SP \($0.f(1))" } ?? ""))
                }
            }
        }
    }

    private func row(depth: String, dur: Double, run: Double, gas: Gas, note: String?) -> some View {
        HStack {
            Text(depth).frame(width: 56, alignment: .leading)
            Text(dur.f(1)).frame(width: 50, alignment: .trailing)
            Text(UnitsAndPressure.formatRuntimeMmSs(run)).frame(width: 56, alignment: .trailing)
            HStack(spacing: 4) {
                Circle().fill(Theme.gasColor(gas)).frame(width: 8, height: 8)
                Text(gas.displayName)
                if let n = note { Text(n).foregroundStyle(.secondary) }
            }.frame(maxWidth: .infinity, alignment: .leading).padding(.leading, 8)
        }
        .font(.caption.monospacedDigit())
    }

    private func gasCard(_ r: DivePlanResult) -> some View {
        Card(title: "Gas Requirements", systemImage: "cylinder.split.1x2") {
            ForEach(Array(r.gasConsumption.enumerated()), id: \.offset) { _, c in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Circle().fill(Theme.gasColor(c.gas)).frame(width: 10, height: 10)
                        Text(c.gas.displayName).font(.subheadline.bold())
                        Spacer()
                        Text(c.isSufficient ? "OK" : "SHORT").font(.caption.bold())
                            .foregroundStyle(c.isSufficient ? Theme.ok : Theme.color(.critical))
                    }
                    ProgressView(value: min(c.totalRequiredLitres / max(c.availableLitres, 1), 1))
                        .tint(c.isSufficient ? Theme.ok : Theme.color(.critical))
                    Text("Used \(UnitsAndPressure.formatVolume(c.usedLitres, u)) + reserve \(UnitsAndPressure.formatVolume(c.reserveLitres, u)) = \(UnitsAndPressure.formatVolume(c.totalRequiredLitres, u)) of \(UnitsAndPressure.formatVolume(c.availableLitres, u))")
                        .font(.caption).foregroundStyle(.secondary)
                    Text("Turn \(UnitsAndPressure.formatPressure(c.turnPressureBar, u)) · Min \(UnitsAndPressure.formatPressure(c.minPressureBar, u)) · Left \(UnitsAndPressure.formatPressure(c.remainingBar, u))")
                        .font(.caption2).foregroundStyle(.secondary)
                }
            }
        }
    }

    private func contingencyCard(_ r: DivePlanResult) -> some View {
        Card(title: "Contingency & Bailout Plans", systemImage: "lifepreserver") {
            let all = r.contingencies + (r.ccrBailout.map { [$0] } ?? [])
            if all.isEmpty {
                Text(r.abortedReason == nil ? "Calculating contingencies…" : "Not available for an aborted plan").font(.caption).foregroundStyle(.secondary)
            }
            ForEach(Array(all.enumerated()), id: \.offset) { _, c in
                DisclosureGroup {
                    ForEach(Array(c.stops.enumerated()), id: \.offset) { _, s in
                        row(depth: UnitsAndPressure.formatDepth(Double(s.depthM), u), dur: s.durationMin, run: s.runtimeMin, gas: s.gas, note: nil)
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(c.title).font(.subheadline.bold())
                            if c.hasErrors { Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.color(.critical)) }
                        }
                        Text("\(c.subtitle) · RT \(UnitsAndPressure.formatRuntimeMmSs(c.totalRuntimeMin)) · Deco \(kRound(c.totalDecoMin)) min · CNS \(kRound(c.cnsPct))%")
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
