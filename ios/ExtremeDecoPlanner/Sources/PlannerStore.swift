import Foundation
import SwiftUI
import DecoEngine

/// Saved plan entry (mirrors SavedPlanSummary on Android).
struct SavedPlan: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let summary: String
    let savedAt: Date
    let input: DivePlanInput
    let dives: [MultiDiveEntry]
}

private struct PersistedState: Codable {
    var input: DivePlanInput
    var dives: [MultiDiveEntry]
}

/// App state and actions (mirrors PlannerViewModel.kt). Plans are computed off the main thread.
@MainActor
final class PlannerStore: ObservableObject {
    @Published var input = DivePlanInput()
    @Published var dives: [MultiDiveEntry] = [MultiDiveEntry(id: "dive_1", label: "Dive 1", levels: [DiveLevel("lvl_1", 40.0, 20.0)])]
    @Published var activeDiveIndex = 0
    @Published var dayResult: MultiDiveDayResult?
    @Published var bestMixRec: MixRecommendation?
    @Published var isComputing = false
    @Published var disclaimerAccepted: Bool = UserDefaults.standard.bool(forKey: "disclaimerAccepted")
    @Published var savedPlans: [SavedPlan] = []
    @Published var message: String?

    private var computeTask: Task<Void, Never>?
    private var persistTask: Task<Void, Never>?

    var activeDive: MultiDiveEntry { dives.indices.contains(activeDiveIndex) ? dives[activeDiveIndex] : dives[0] }
    var activeResult: DivePlanResult? {
        guard let day = dayResult, day.diveResults.indices.contains(activeDiveIndex) else { return nil }
        return day.diveResults[activeDiveIndex]
    }
    var units: UnitSystem { input.general.units }

    init() {
        if let data = UserDefaults.standard.data(forKey: "lastState"),
           let state = try? JSONDecoder().decode(PersistedState.self, from: data), !state.dives.isEmpty {
            input = state.input
            dives = state.dives
        }
        if let data = UserDefaults.standard.data(forKey: "savedPlans"),
           let plans = try? JSONDecoder().decode([SavedPlan].self, from: data) {
            savedPlans = plans
        }
        recomputePlan(immediate: true)
    }

    func acceptDisclaimer() {
        disclaimerAccepted = true
        UserDefaults.standard.set(true, forKey: "disclaimerAccepted")
    }

    func show(_ text: String) { message = text }

    // ---- Dives ----

    func selectDive(_ index: Int) {
        guard dives.indices.contains(index), index != activeDiveIndex else { return }
        activeDiveIndex = index
        recomputePlan(immediate: true)
    }

    func addRepetitiveDive(surfaceIntervalMin: Double = 90.0) {
        guard dives.count < 6 else { show("Maximum 6 dives per day plan."); return }
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        let prevDepth = activeDive.levels.first?.depthM ?? 30.0
        let newDive = MultiDiveEntry(id: "dive_\(stamp)", label: "Dive \(dives.count + 1)",
                                     surfaceIntervalBeforeMin: min(max(surfaceIntervalMin, 0), 1440),
                                     levels: [DiveLevel("lvl_\(stamp)", max(prevDepth - 6.0, 18.0), 25.0)])
        dives.append(newDive)
        activeDiveIndex = dives.count - 1
        recomputePlan(immediate: true)
    }

    func removeDive(_ index: Int) {
        guard dives.count > 1, dives.indices.contains(index) else { return }
        var updated = dives
        updated.remove(at: index)
        dives = updated.enumerated().map { i, e in
            var c = e
            c.label = "Dive \(i + 1)"
            c.surfaceIntervalBeforeMin = i == 0 ? 0.0 : max(e.surfaceIntervalBeforeMin, 15.0)
            return c
        }
        activeDiveIndex = min(activeDiveIndex, dives.count - 1)
        recomputePlan(immediate: true)
    }

    func updateSurfaceInterval(_ diveIndex: Int, _ minutes: Double) {
        guard dives.indices.contains(diveIndex), diveIndex > 0 else { return }
        dives[diveIndex].surfaceIntervalBeforeMin = min(max(minutes, 0), 1440)
        recomputePlan()
    }

    // ---- Levels ----

    func addLevel() {
        var d = activeDive
        guard d.levels.count < 6 else { return }
        let last = d.levels.last
        let stamp = Int(Date().timeIntervalSince1970 * 1000)
        d.levels.append(DiveLevel("lvl_\(stamp)", max((last?.depthM ?? 30.0) - 9.0, 9.0), 10.0, gasId: last?.gasId))
        dives[activeDiveIndex] = d
        recomputePlan()
    }

    func removeLevel(_ levelId: String) {
        var d = activeDive
        guard d.levels.count > 1 else { return }
        d.levels.removeAll { $0.id == levelId }
        dives[activeDiveIndex] = d
        recomputePlan()
    }

    func updateLevel(_ levelId: String, depthM: Double? = nil, timeMin: Double? = nil) {
        var d = activeDive
        d.levels = d.levels.map { l in
            guard l.id == levelId else { return l }
            var c = l
            if let v = depthM { c.depthM = min(max(v, 3), 200) }
            if let v = timeMin { c.timeMin = min(max(v, 1), 360) }
            return c
        }
        dives[activeDiveIndex] = d
        recomputePlan()
    }

    // ---- Gases ----

    func addQuickGas(_ o2Pct: Int, _ hePct: Int, _ role: GasRole) {
        let isDecoGroup = role == .deco || role == .bailout
        let sameGroup = input.gases.filter { isDecoGroup ? ($0.role == .deco || $0.role == .bailout) : ($0.role == .bottom || $0.role == .travel || $0.role == .diluent) }.count
        guard sameGroup < (isDecoGroup ? 6 : 10) else { show("Maximum gas rows reached for this category."); return }
        let wasBest = input.general.gasSelectionMode == .bestGasMix
        input.gases.append(Gas.fromPercent("g_\(Int(Date().timeIntervalSince1970 * 1000))", o2Pct, hePct, role))
        input.general.gasSelectionMode = .userInputGas
        if wasBest { show("Switched to User input GAS mode.") }
        recomputePlan()
    }

    func updateGas(_ updated: Gas) {
        guard let prev = input.gases.first(where: { $0.id == updated.id }) else { return }
        let changed = prev.o2Pct != updated.o2Pct || prev.hePct != updated.hePct || prev.enabled != updated.enabled || prev.cylinder.count != updated.cylinder.count
        input.gases = input.gases.map { $0.id == updated.id ? updated : $0 }
        if changed && input.general.gasSelectionMode == .bestGasMix {
            input.general.gasSelectionMode = .userInputGas
            show("Custom gas/tank count edited — switched to User input GAS mode.")
        }
        recomputePlan()
    }

    func removeGas(_ id: String) {
        guard input.gases.count > 1 else { return }
        let wasBest = input.general.gasSelectionMode == .bestGasMix
        input.gases.removeAll { $0.id == id }
        input.general.gasSelectionMode = .userInputGas
        if wasBest { show("Switched to User input GAS mode.") }
        recomputePlan()
    }

    func setGasSelectionMode(_ mode: GasSelectionMode) { updateGeneral { $0.gasSelectionMode = mode } }

    func applyBestMixRecommendation() {
        let rec = GasCalculator.recommendBestMix(
            maxDepthM: activeDive.levels.map(\.depthM).max() ?? 40.0, general: input.general, dive: input.dive,
            existingGases: input.gases, alerts: input.alerts, levels: activeDive.levels,
            previousTissues: activeResult?.input.previousTissues, surfaceIntervalMin: activeResult?.input.surfaceIntervalMin ?? 0.0,
            consumption: input.consumption)
        dives[activeDiveIndex].levels = activeDive.levels.map { var l = $0; l.gasId = nil; return l }
        input.gases = rec.plannerGases
        input.general.gasSelectionMode = .bestGasMix
        bestMixRec = rec
        show(rec.isRecreational
             ? "REC Dive (Within NDL): Best GAS Mix \(rec.bottomGas.displayName) (Air / Nitrox only)"
             : "Best GAS Mix active: 6m \(rec.deco6mO2?.displayName ?? "O2") · 21m \(rec.deco21mEan50?.displayName ?? "EAN50") · Travel \(rec.travelGasDisplay.displayName) · MOD \(rec.bottomGas.displayName)")
        recomputePlan(immediate: true)
    }

    // ---- Settings ----

    func updateGeneral(_ transform: (inout GeneralSettings) -> Void) {
        var g = input.general
        transform(&g)
        g.gfLow = min(max(g.gfLow, 5), 100)
        g.gfHigh = min(max(g.gfHigh, g.gfLow), 100)
        input.general = g
        recomputePlan()
    }
    func updateDive(_ t: (inout DiveSettings) -> Void) { t(&input.dive); recomputePlan() }
    func updateGasBreaks(_ t: (inout GasBreakSettings) -> Void) { t(&input.gasBreaks); recomputePlan() }
    func updateConsumption(_ t: (inout ConsumptionSettings) -> Void) { t(&input.consumption); recomputePlan() }
    func updateCcr(_ t: (inout CcrSettings) -> Void) { t(&input.ccr); recomputePlan() }
    func updateAlerts(_ t: (inout AlertSettings) -> Void) { t(&input.alerts); recomputePlan() }

    // ---- Saved plans ----

    func saveCurrentPlan(_ name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return }
        let res = activeResult
        let summary = "\(dives.count) dive(s) · Max \(kRound(res?.maxDepthM ?? 0))m · RT \(UnitsAndPressure.formatRuntimeMmSs(res?.totalRuntimeMin ?? 0)) · \(input.general.decoModel.shortLabel) GF \(input.general.gfLow)/\(input.general.gfHigh)"
        let plan = SavedPlan(id: UUID().uuidString, name: trimmed, summary: summary, savedAt: Date(), input: input, dives: dives)
        savedPlans.insert(plan, at: 0)
        if savedPlans.count > 50 { savedPlans = Array(savedPlans.prefix(50)) }
        persistSavedPlans()
        show("Saved plan \"\(trimmed)\"")
    }

    func loadSavedPlan(_ plan: SavedPlan) {
        input = plan.input
        dives = plan.dives
        activeDiveIndex = 0
        show("Loaded \"\(plan.name)\"")
        recomputePlan(immediate: true)
    }

    func deleteSavedPlan(_ id: String) {
        savedPlans.removeAll { $0.id == id }
        persistSavedPlans()
    }

    private func persistSavedPlans() {
        if let data = try? JSONEncoder().encode(savedPlans) { UserDefaults.standard.set(data, forKey: "savedPlans") }
    }

    // ---- Engine recomputation (same 2 phases as Android) ----

    func recomputePlan(immediate: Bool = false) {
        computeTask?.cancel()
        let snapshotInput = input
        let snapshotDives = dives
        let activeIdx = activeDiveIndex
        computeTask = Task { [weak self] in
            if !immediate { try? await Task.sleep(nanoseconds: 160_000_000) }
            if Task.isCancelled { return }
            self?.isComputing = true
            let (day, rec, synced) = await Task.detached(priority: .userInitiated) { () -> (MultiDiveDayResult, MixRecommendation, [Gas]) in
                let isBest = snapshotInput.general.gasSelectionMode == .bestGasMix
                let effective = isBest ? snapshotDives.map { d in var c = d; c.levels = d.levels.map { var l = $0; l.gasId = nil; return l }; return c } : snapshotDives
                let res = DivePlannerEngine.planMultiDiveDay(snapshotInput, effective, includeContingenciesForIndex: -1)
                let activeRes = res.diveResults.indices.contains(activeIdx) ? res.diveResults[activeIdx] : nil
                let active = snapshotDives.indices.contains(activeIdx) ? snapshotDives[activeIdx] : snapshotDives[0]
                var rec = GasCalculator.recommendBestMix(
                    maxDepthM: active.levels.map(\.depthM).max() ?? 40.0, general: snapshotInput.general, dive: snapshotInput.dive,
                    existingGases: snapshotInput.gases, alerts: snapshotInput.alerts, levels: active.levels,
                    previousTissues: activeRes?.input.previousTissues, surfaceIntervalMin: activeRes?.input.surfaceIntervalMin ?? 0.0,
                    consumption: snapshotInput.consumption)
                let finalGases: [Gas]
                if isBest, let ar = activeRes {
                    finalGases = ar.input.gases
                    var byId = [String: Gas]()
                    for g in finalGases { byId[g.id] = g }
                    func sync(_ g: Gas) -> Gas { byId[g.id] ?? g }
                    rec.bottomGas = sync(rec.bottomGas)
                    rec.travelGas = rec.travelGas.map(sync)
                    rec.travelGasDisplay = sync(rec.travelGasDisplay)
                    rec.deco6mO2 = rec.deco6mO2.map(sync)
                    rec.deco21mEan50 = rec.deco21mEan50.map(sync)
                    rec.deepDecoGas = rec.deepDecoGas.map(sync)
                    rec.decoGases = rec.decoGases.map(sync)
                    rec.plannerGases = finalGases
                } else if isBest {
                    finalGases = rec.plannerGases
                } else {
                    finalGases = snapshotInput.gases
                }
                return (res, rec, finalGases)
            }.value
            guard let self, !Task.isCancelled else { return }
            if self.input.general.gasSelectionMode == .bestGasMix { self.input.gases = synced }
            self.dayResult = day
            self.bestMixRec = rec
            self.isComputing = false
            self.schedulePersist()

            // Phase 2: contingencies for the active dive
            guard day.diveResults.indices.contains(activeIdx), day.diveResults[activeIdx].abortedReason == nil else { return }
            let activeInput = day.diveResults[activeIdx].input
            let (cont, bail) = await Task.detached(priority: .utility) { () -> ([ContingencySummary], ContingencySummary?) in
                let c = DivePlannerEngine.generateContingencies(activeInput)
                let b = activeInput.general.planningType == .ccr ? DivePlannerEngine.generateCcrBailout(activeInput) : nil
                return (c, b)
            }.value
            guard !Task.isCancelled, let current = self.dayResult, current.diveResults.indices.contains(activeIdx) else { return }
            var results = current.diveResults
            results[activeIdx].contingencies = cont
            results[activeIdx].ccrBailout = bail
            self.dayResult = MultiDiveDayResult(diveResults: results, surfaceIntervalsMin: current.surfaceIntervalsMin,
                                                postSurfaceIntervalTissues: current.postSurfaceIntervalTissues,
                                                totalDailyOtu: current.totalDailyOtu, peakEndCnsPct: current.peakEndCnsPct)
        }
    }

    private func schedulePersist() {
        persistTask?.cancel()
        let state = PersistedState(input: input, dives: dives)
        persistTask = Task {
            try? await Task.sleep(nanoseconds: 600_000_000)
            if Task.isCancelled { return }
            if let data = try? JSONEncoder().encode(state) { UserDefaults.standard.set(data, forKey: "lastState") }
        }
    }
}
