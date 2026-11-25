import SwiftUI
import DecoEngine

struct SettingsView: View {
    @EnvironmentObject var store: PlannerStore
    @State private var planName = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    HeroBanner(image: "img_settings_computer", title: "Dive Computer & Model Settings",
                               subtitle: "\(g.decoModel.shortLabel) · GF \(g.gfLow)/\(g.gfHigh) · \(g.planningType.label)")
                    generalCard
                    limitsCard
                    breaksCard
                    consumptionCard
                    if g.planningType == .ccr { ccrCard }
                    alertsCard
                    savedCard
                    Text("Extreme DECO Planner v\(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") · iOS")
                        .font(.caption2).foregroundStyle(.secondary).padding(.bottom, 20)
                }.padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle("Settings")
            .scrollDismissesKeyboard(.interactively)
        }
    }

    private var g: GeneralSettings { store.input.general }
    private var d: DiveSettings { store.input.dive }

    private var generalCard: some View {
        Card(title: "General Settings & Algorithm", systemImage: "gearshape") {
            Picker("Planning", selection: Binding(get: { g.planningType }, set: { v in store.updateGeneral { $0.planningType = v } })) {
                ForEach(PlanningType.allCases, id: \.self) { Text($0.label).tag($0) }
            }.pickerStyle(.segmented)
            Picker("Units", selection: Binding(get: { g.units }, set: { v in store.updateGeneral { $0.units = v } })) {
                ForEach(UnitSystem.allCases, id: \.self) { Text($0 == .metric ? "Metric" : "Imperial").tag($0) }
            }.pickerStyle(.segmented)
            Picker("Deco model", selection: Binding(get: { g.decoModel }, set: { v in store.updateGeneral { $0.decoModel = v } })) {
                ForEach(DecoModel.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Text(g.decoModel.modelDescription).font(.caption).foregroundStyle(.secondary)
            if g.decoModel == .vpmB {
                StepperRow(label: "VPM-B conservatism", value: g.vpmConservatism, range: 0...5) { v in store.updateGeneral { $0.vpmConservatism = v } }
            }
            Picker("Diving agency (Best GAS Mix)", selection: Binding(get: { g.gasAgency }, set: { v in store.updateGeneral { $0.gasAgency = v } })) {
                ForEach(GasAgency.allCases) { Text($0.label).tag($0) }
            }
            Text(g.gasAgency.description).font(.caption).foregroundStyle(.secondary)
            StepperRow(label: "GF Low", value: g.gfLow, range: 5...100, step: 5, suffix: "%") { v in store.updateGeneral { $0.gfLow = v } }
            StepperRow(label: "GF High", value: g.gfHigh, range: g.gfLow...100, step: 5, suffix: "%") { v in store.updateGeneral { $0.gfHigh = v } }
            HStack {
                ForEach(["30/70", "30/85", "40/85", "50/80", "20/80"], id: \.self) { p in
                    let parts = p.split(separator: "/").compactMap { Int($0) }
                    Button(p) { store.updateGeneral { $0.gfLow = parts[0]; $0.gfHigh = parts[1] } }
                        .buttonStyle(.bordered).font(.caption)
                }
            }
            Toggle("Use compartment 1b (ZHL-16A)", isOn: Binding(get: { g.useCompartment1b }, set: { v in store.updateGeneral { $0.useCompartment1b = v } }))
            Picker("Water", selection: Binding(get: { g.salinityPreset }, set: { v in store.updateGeneral { $0.salinityPreset = v } })) {
                ForEach(SalinityPreset.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            if g.salinityPreset == .custom || g.salinityPreset == .redSea {
                NumberField(label: g.salinityPreset == .redSea ? "Red Sea density (1020–1040)" : "Custom density", value: g.customSalinityKgM3, unit: "kg/m³") { v in
                    store.updateGeneral { $0.customSalinityKgM3 = min(max(v, 990), 1050) }
                }
            }
            Text("Used in calculations: \(kRound(g.effectiveSalinity)) kg/m³").font(.caption).foregroundStyle(.secondary)
            NumberField(label: "Elevation", value: Double(g.elevationM), unit: "m") { v in store.updateGeneral { $0.elevationM = Int(min(max(v, 0), 5000)) } }
            Toggle("Arrived at altitude < 12 h ago", isOn: Binding(get: { g.arrivedUnder12hAtAltitude }, set: { v in store.updateGeneral { $0.arrivedUnder12hAtAltitude = v } }))
            NumberField(label: "Water temperature", value: Double(g.surfaceTempC), unit: "°C") { v in store.updateGeneral { $0.surfaceTempC = Int(min(max(v, -2), 40)) } }
            Picker("Plan style", selection: Binding(get: { g.planStyle }, set: { v in store.updateGeneral { $0.planStyle = v } })) {
                ForEach(PlanStyle.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Toggle("Bottom time includes descent", isOn: Binding(get: { g.btIncludesDescent }, set: { v in store.updateGeneral { $0.btIncludesDescent = v } }))
            Toggle("Standard mixes only (21/35, 18/45, 15/55…)", isOn: Binding(get: { g.standardMixesOnly }, set: { v in store.updateGeneral { $0.standardMixesOnly = v } }))
            Toggle("Dark Abyssal theme", isOn: Binding(get: { g.darkTheme }, set: { v in store.updateGeneral { $0.darkTheme = v } }))
        }
    }

    private var limitsCard: some View {
        Card(title: "Dive Limits, Rates & Stops", systemImage: "arrow.up.and.down") {
            NumberField(label: "ppO₂ bottom", value: d.ppO2Bottom, unit: "bar", decimals: 2) { v in store.updateDive { $0.ppO2Bottom = min(max(v, 0.8), 1.6) } }
            NumberField(label: "ppO₂ deco", value: d.ppO2Deco, unit: "bar", decimals: 2) { v in store.updateDive { $0.ppO2Deco = min(max(v, 1.0), 1.7) } }
            NumberField(label: "ppO₂ minimum", value: d.ppO2Min, unit: "bar", decimals: 2) { v in store.updateDive { $0.ppO2Min = min(max(v, 0.14), 0.21) } }
            NumberField(label: "ppN₂ max (bottom)", value: d.ppN2Max, unit: "bar", decimals: 2) { v in store.updateDive { $0.ppN2Max = min(max(v, 2.0), 5.0) } }
            NumberField(label: "ppN₂ max (deco)", value: d.ppN2MaxDeco, unit: "bar", decimals: 2) { v in store.updateDive { $0.ppN2MaxDeco = min(max(v, 2.0), 5.0) } }
            NumberField(label: "Max END", value: d.maxEndM, unit: "m") { v in store.updateDive { $0.maxEndM = min(max(v, 10), 60) } }
            Toggle("O₂ is narcotic", isOn: Binding(get: { d.o2Narcotic }, set: { v in store.updateDive { $0.o2Narcotic = v } }))
            NumberField(label: "Max gas density", value: d.maxGasDensityGL, unit: "g/L", decimals: 1) { v in store.updateDive { $0.maxGasDensityGL = min(max(v, 4.0), 7.0) } }
            NumberField(label: "Descent rate", value: d.descentRateMMin, unit: "m/min") { v in store.updateDive { $0.descentRateMMin = min(max(v, 3), 40) } }
            NumberField(label: "Ascent to first stop", value: d.ascentRateToFirstStopMMin, unit: "m/min") { v in store.updateDive { $0.ascentRateToFirstStopMMin = min(max(v, 1), 18) } }
            NumberField(label: "Ascent between stops", value: d.ascentDecoRateMMin, unit: "m/min") { v in store.updateDive { $0.ascentDecoRateMMin = min(max(v, 1), 18) } }
            NumberField(label: "Ascent to surface", value: d.ascentRateToSurfaceMMin, unit: "m/min") { v in store.updateDive { $0.ascentRateToSurfaceMMin = min(max(v, 1), 18) } }
            Picker("Stop increment", selection: Binding(get: { d.stopIncrementM }, set: { v in store.updateDive { $0.stopIncrementM = v } })) {
                Text("3 m").tag(3); Text("1 m").tag(1)
            }.pickerStyle(.segmented)
            Picker("Last stop", selection: Binding(get: { d.lastStopM }, set: { v in store.updateDive { $0.lastStopM = v } })) {
                Text("3 m").tag(3); Text("6 m").tag(6)
            }.pickerStyle(.segmented)
            NumberField(label: "Minimum stop time", value: d.minStopTimeMin, unit: "min") { v in store.updateDive { $0.minStopTimeMin = min(max(v, 0.5), 5) } }
            NumberField(label: "Extra time on gas switch", value: d.mixChangeExtraTimeMin, unit: "min") { v in store.updateDive { $0.mixChangeExtraTimeMin = min(max(v, 0), 5) } }
            Toggle("Stop on gas switch", isOn: Binding(get: { d.stopOnGasSwitch }, set: { v in store.updateDive { $0.stopOnGasSwitch = v } }))
        }
    }

    private var breaksCard: some View {
        let b = store.input.gasBreaks
        return Card(title: "Gas Breaks (Air Breaks)", systemImage: "pause.circle") {
            Toggle("Enable O₂ air breaks", isOn: Binding(get: { b.enabled }, set: { v in store.updateGasBreaks { $0.enabled = v } }))
            if b.enabled {
                NumberField(label: "Apply at or above", value: b.applyDepthMaxM, unit: "m") { v in store.updateGasBreaks { $0.applyDepthMaxM = min(max(v, 3), 9) } }
                NumberField(label: "Break after", value: b.breakAfterMin, unit: "min") { v in store.updateGasBreaks { $0.breakAfterMin = min(max(v, 5), 60) } }
                NumberField(label: "Break length", value: b.breakTimeMin, unit: "min") { v in store.updateGasBreaks { $0.breakTimeMin = min(max(v, 1), 15) } }
            }
        }
    }

    private var consumptionCard: some View {
        let c = store.input.consumption
        return Card(title: "Gas Consumption & Reserve Rules", systemImage: "gauge.with.dots.needle.50percent") {
            NumberField(label: "Bottom RMV", value: c.bottomRmvLMin, unit: "L/min") { v in store.updateConsumption { $0.bottomRmvLMin = min(max(v, 5), 60) } }
            NumberField(label: "Deco RMV", value: c.decoRmvLMin, unit: "L/min") { v in store.updateConsumption { $0.decoRmvLMin = min(max(v, 5), 60) } }
            NumberField(label: "Stress factor", value: c.stressFactor, unit: "×", decimals: 1) { v in store.updateConsumption { $0.stressFactor = min(max(v, 1), 4) } }
            Picker("Bottom reserve", selection: Binding(get: { c.bottomReserveRule }, set: { v in store.updateConsumption { $0.bottomReserveRule = v } })) {
                ForEach(ReserveRuleBottom.allCases, id: \.self) { Text($0.label).tag($0) }
            }
            Picker("Deco reserve", selection: Binding(get: { c.decoReserveRule }, set: { v in store.updateConsumption { $0.decoReserveRule = v } })) {
                ForEach(ReserveRuleDeco.allCases, id: \.self) { Text($0.label).tag($0) }
            }
        }
    }

    private var ccrCard: some View {
        let c = store.input.ccr
        return Card(title: "CCR Setpoints & Bailout", systemImage: "arrow.triangle.2.circlepath") {
            NumberField(label: "Setpoint descent", value: c.setpointDescent, unit: "bar", decimals: 2) { v in store.updateCcr { $0.setpointDescent = min(max(v, 0.4), 1.6) } }
            NumberField(label: "Setpoint bottom", value: c.setpointBottom, unit: "bar", decimals: 2) { v in store.updateCcr { $0.setpointBottom = min(max(v, 0.4), 1.6) } }
            NumberField(label: "Setpoint deco", value: c.setpointDeco, unit: "bar", decimals: 2) { v in store.updateCcr { $0.setpointDeco = min(max(v, 0.4), 1.6) } }
            NumberField(label: "Switch to high SP at", value: c.switchToHighDepthM, unit: "m") { v in store.updateCcr { $0.switchToHighDepthM = min(max(v, 0), 60) } }
            NumberField(label: "Switch to deco SP at", value: c.switchToDecoDepthM, unit: "m") { v in store.updateCcr { $0.switchToDecoDepthM = min(max(v, 0), 30) } }
        }
    }

    private var alertsCard: some View {
        let a = store.input.alerts
        return Card(title: "Safety Alert Thresholds", systemImage: "exclamationmark.triangle") {
            Text("4 levels: Critical · High · Medium · Low").font(.caption).foregroundStyle(.secondary)
            NumberField(label: "ICD ΔppN₂ max", value: a.icdDeltaPpN2Max, unit: "bar", decimals: 2) { v in store.updateAlerts { $0.icdDeltaPpN2Max = min(max(v, 0.1), 2) } }
            Toggle("ICD Rule of Fifths", isOn: Binding(get: { a.icdRuleOfFifths }, set: { v in store.updateAlerts { $0.icdRuleOfFifths = v } }))
            Toggle("Helium lipid advisory", isOn: Binding(get: { a.considerLipidResponse }, set: { v in store.updateAlerts { $0.considerLipidResponse = v } }))
            NumberField(label: "Bottom mix budget", value: a.bottomMixMaxLitres, unit: "L") { v in store.updateAlerts { $0.bottomMixMaxLitres = max(v, 100) } }
            NumberField(label: "Deco mix ≤49% budget", value: a.decoMixUpTo49MaxLitres, unit: "L") { v in store.updateAlerts { $0.decoMixUpTo49MaxLitres = max(v, 100) } }
            NumberField(label: "Deco mix 50–99% budget", value: a.decoMix50To99MaxLitres, unit: "L") { v in store.updateAlerts { $0.decoMix50To99MaxLitres = max(v, 100) } }
            NumberField(label: "O₂ budget", value: a.decoO2MaxLitres, unit: "L") { v in store.updateAlerts { $0.decoO2MaxLitres = max(v, 100) } }
            NumberField(label: "CNS HIGH above", value: a.cnsWarnPct, unit: "%") { v in store.updateAlerts { $0.cnsWarnPct = min(max(v, 10), a.cnsErrorPct) } }
            NumberField(label: "CNS CRITICAL above", value: a.cnsErrorPct, unit: "%") { v in store.updateAlerts { $0.cnsErrorPct = min(max(v, a.cnsWarnPct), 200) } }
            NumberField(label: "OTU HIGH above (per dive)", value: a.otuWarnPerDive, unit: "OTU") { v in store.updateAlerts { $0.otuWarnPerDive = min(max(v, 50), 850) } }
        }
    }

    private var savedCard: some View {
        Card(title: "Saved Dive Plans (\(store.savedPlans.count)/50)", systemImage: "tray.full") {
            HStack {
                TextField("Plan name", text: $planName).textFieldStyle(.roundedBorder)
                Button("Save") { store.saveCurrentPlan(planName); planName = "" }
                    .buttonStyle(.borderedProminent).disabled(planName.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            ForEach(store.savedPlans) { p in
                HStack {
                    VStack(alignment: .leading) {
                        Text(p.name).font(.subheadline.bold())
                        Text(p.summary).font(.caption2).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("Load") { store.loadSavedPlan(p) }.buttonStyle(.bordered).font(.caption)
                    Button(role: .destructive) { store.deleteSavedPlan(p.id) } label: { Image(systemName: "trash") }.buttonStyle(.borderless)
                }
            }
        }
    }
}
