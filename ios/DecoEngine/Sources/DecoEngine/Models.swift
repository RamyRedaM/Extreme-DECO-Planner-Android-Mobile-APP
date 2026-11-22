import Foundation

// Data models (mirror Models.kt). Value types with `var` fields so `copy(...)`-style edits are simple.

public enum PlanningType: String, Codable, CaseIterable, Hashable {
    case openCircuit, ccr
    public var label: String { self == .openCircuit ? "Open Circuit" : "CCR (Closed Circuit)" }
}

public enum GasSelectionMode: String, Codable, CaseIterable, Hashable {
    case bestGasMix, userInputGas
    public var label: String { self == .bestGasMix ? "Best GAS Mix" : "User input GAS" }
}

public enum UnitSystem: String, Codable, CaseIterable, Hashable {
    case metric, imperial
    public var label: String { self == .metric ? "Metric (m / L / bar / °C)" : "Imperial (ft / cuft / psi / °F)" }
    public var depthUnit: String { self == .metric ? "m" : "ft" }
    public var volUnit: String { self == .metric ? "L" : "ft³" }
    public var pressureUnit: String { self == .metric ? "bar" : "psi" }
    public var tempUnit: String { self == .metric ? "°C" : "°F" }
}

public enum DecoModel: String, Codable, CaseIterable, Hashable {
    case zhl16A, zhl16B, zhl16C, vpmB
    public var label: String {
        switch self {
        case .zhl16A: return "Bühlmann ZHL-16A"
        case .zhl16B: return "Bühlmann ZHL-16B"
        case .zhl16C: return "Bühlmann ZHL-16C"
        case .vpmB: return "VPM-B (Experimental)"
        }
    }
    public var shortLabel: String {
        switch self {
        case .zhl16A: return "ZHL-16A"
        case .zhl16B: return "ZHL-16B"
        case .zhl16C: return "ZHL-16C"
        case .vpmB: return "VPM-B"
        }
    }
    public var modelDescription: String {
        switch self {
        case .zhl16A: return "Original experimental/theoretical coefficients"
        case .zhl16B: return "More conservative middle compartments, for printed tables"
        case .zhl16C: return "Most conservative middle compartments, standard for dive computers (Recommended)"
        case .vpmB: return "Varying Permeability bubble model with Boyle's law compensation (Experimental)"
        }
    }
}

public enum SalinityPreset: String, Codable, CaseIterable, Hashable {
    case fresh, en13319, salt, redSea, custom
    public var label: String {
        switch self {
        case .fresh: return "Fresh (1000 kg/m³)"
        case .en13319: return "EN13319 (1020 kg/m³)"
        case .salt: return "Salt (1030 kg/m³)"
        case .redSea: return "Red Sea (1030 kg/m³)"
        case .custom: return "Custom"
        }
    }
    public var densityKgM3: Double {
        switch self {
        case .fresh: return Constants.SALINITY_FRESH
        case .en13319: return Constants.SALINITY_EN13319
        case .salt: return Constants.SALINITY_SALT
        case .redSea: return Constants.SALINITY_RED_SEA
        case .custom: return Constants.SALINITY_SALT
        }
    }
}

public enum PlanStyle: String, Codable, CaseIterable, Hashable {
    case detailed, compact
    public var label: String { self == .detailed ? "Detailed" : "Compact (Stops Only)" }
}

public enum GasRole: String, Codable, CaseIterable, Hashable {
    case travel, bottom, diluent, deco, bailout
    public var label: String {
        switch self {
        case .travel: return "Travel"
        case .bottom: return "Bottom"
        case .diluent: return "Diluent"
        case .deco: return "Deco"
        case .bailout: return "Bailout"
        }
    }
}

public enum ReserveRuleBottom: String, Codable, CaseIterable, Hashable {
    case thirds, quarters, half, rockBottom
    public var label: String {
        switch self {
        case .thirds: return "Rule of Thirds (1.5×)"
        case .quarters: return "Rule of Quarters (1.33×)"
        case .half: return "Half Reserve (2.0×)"
        case .rockBottom: return "Rock-Bottom (2× Stress)"
        }
    }
}

public enum ReserveRuleDeco: String, Codable, CaseIterable, Hashable {
    case multiplier15, lostGas
    public var label: String { self == .multiplier15 ? "1.5× Required" : "Lost-Gas Scenario" }
}

public struct CylinderConfig: Codable, Hashable {
    public var waterVolumeL: Double = 12.0
    public var fillPressureBar: Double = 200.0
    public var count: Int = 2
    public init(waterVolumeL: Double = 12.0, fillPressureBar: Double = 200.0, count: Int = 2) {
        self.waterVolumeL = waterVolumeL; self.fillPressureBar = fillPressureBar; self.count = count
    }
    public var availableLitres: Double { waterVolumeL * fillPressureBar * Double(count) }
}

public struct Gas: Codable, Hashable, Identifiable {
    public var id: String
    public var o2: Double
    public var he: Double
    public var role: GasRole
    public var enabled: Bool = true
    public var modOverrideM: Double? = nil
    public var cylinder: CylinderConfig

    public init(id: String, o2: Double, he: Double, role: GasRole, enabled: Bool = true,
                modOverrideM: Double? = nil, cylinder: CylinderConfig? = nil) {
        self.id = id; self.o2 = o2; self.he = he; self.role = role; self.enabled = enabled
        self.modOverrideM = modOverrideM
        self.cylinder = cylinder ?? Gas.defaultCylinder(for: role)
    }

    static func defaultCylinder(for role: GasRole) -> CylinderConfig {
        (role == .deco || role == .bailout || role == .travel)
            ? CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: 1)
            : CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: 2)
    }

    public var n2: Double { (1.0 - o2 - he).coerceAtLeast(0.0) }
    public var o2Pct: Int { kRound(o2 * 100.0) }
    public var hePct: Int { kRound(he * 100.0) }
    public var n2Pct: Int { (100 - o2Pct - hePct).coerceAtLeast(0) }

    public var displayName: String {
        if hePct > 0 { return "TX \(o2Pct)/\(hePct)" }
        if o2Pct == 100 { return "O2 100%" }
        if o2Pct == 21 { return "Air" }
        return "EAN\(o2Pct)"
    }

    public static func fromPercent(_ id: String, _ o2Pct: Int, _ hePct: Int, _ role: GasRole, enabled: Bool = true,
                                   modOverrideM: Double? = nil, cylinder: CylinderConfig? = nil) -> Gas {
        let clampedO2 = o2Pct.coerceIn(5, 100)
        let clampedHe = hePct.coerceIn(0, 100 - clampedO2)
        return Gas(id: id, o2: Double(clampedO2) / 100.0, he: Double(clampedHe) / 100.0, role: role,
                   enabled: enabled, modOverrideM: modOverrideM, cylinder: cylinder)
    }
}

public struct DiveLevel: Codable, Hashable, Identifiable {
    public var id: String
    public var depthM: Double
    public var timeMin: Double
    public var gasId: String? = nil
    public init(_ id: String, _ depthM: Double, _ timeMin: Double, gasId: String? = nil) {
        self.id = id; self.depthM = depthM; self.timeMin = timeMin; self.gasId = gasId
    }
}

public struct GeneralSettings: Codable, Hashable {
    public var planningType: PlanningType = .openCircuit
    public var units: UnitSystem = .metric
    public var decoModel: DecoModel = .zhl16C
    public var gfLow: Int = 30
    public var gfHigh: Int = 70
    public var vpmConservatism: Int = 2
    public var useCompartment1b: Bool = false
    public var salinityPreset: SalinityPreset = .salt
    public var customSalinityKgM3: Double = Constants.SALINITY_SALT
    public var elevationM: Int = 0
    public var arrivedUnder12hAtAltitude: Bool = false
    public var surfaceTempC: Int = 22
    public var darkTheme: Bool = false
    public var planStyle: PlanStyle = .detailed
    public var btIncludesDescent: Bool = true
    public var standardMixesOnly: Bool = false
    public var gasSelectionMode: GasSelectionMode = .bestGasMix

    public init() {}

    public static let RED_SEA_MIN_KGM3 = 1020.0
    public static let RED_SEA_MAX_KGM3 = 1040.0

    public var effectiveSalinity: Double {
        switch salinityPreset {
        case .custom: return customSalinityKgM3
        case .redSea:
            return (customSalinityKgM3 >= GeneralSettings.RED_SEA_MIN_KGM3 && customSalinityKgM3 <= GeneralSettings.RED_SEA_MAX_KGM3)
                ? customSalinityKgM3 : SalinityPreset.redSea.densityKgM3
        default: return salinityPreset.densityKgM3
        }
    }
}

public struct DiveSettings: Codable, Hashable {
    public var ppO2Bottom: Double = 1.40
    public var ppO2Deco: Double = 1.60
    public var ppO2Min: Double = 0.18
    public var ppN2Max: Double = 3.95
    public var ppN2MaxDeco: Double = 3.16
    public var maxEndM: Double = 30.0
    public var o2Narcotic: Bool = true
    public var maxGasDensityGL: Double = 5.2
    public var descentRateMMin: Double = 20.0
    public var ascentRateToFirstStopMMin: Double = 9.0
    public var ascentDecoRateMMin: Double = 9.0
    public var ascentRateToSurfaceMMin: Double = 3.0
    public var stopIncrementM: Int = 3
    public var lastStopM: Int = 3
    public var minStopTimeMin: Double = 1.0
    public var mixChangeExtraTimeMin: Double = 1.0
    public var stopOnGasSwitch: Bool = true
    public init() {}
}

public struct GasBreakSettings: Codable, Hashable {
    public var enabled: Bool = false
    public var applyDepthMaxM: Double = 6.0
    public var breakAfterMin: Double = 20.0
    public var breakTimeMin: Double = 5.0
    public init() {}
}

public struct ConsumptionSettings: Codable, Hashable {
    public var bottomRmvLMin: Double = 20.0
    public var decoRmvLMin: Double = 15.0
    public var stressFactor: Double = 1.5
    public var bottomReserveRule: ReserveRuleBottom = .thirds
    public var decoReserveRule: ReserveRuleDeco = .multiplier15
    public init() {}
}

public struct CcrSettings: Codable, Hashable {
    public var setpointDescent: Double = 0.7
    public var setpointBottom: Double = 1.3
    public var setpointDeco: Double = 1.3
    public var switchToHighDepthM: Double = 20.0
    public var switchToDecoDepthM: Double = 6.0
    public init() {}
}

public struct AlertSettings: Codable, Hashable {
    public var icdDeltaPpN2Max: Double = 0.5
    public var icdRuleOfFifths: Bool = true
    public var considerLipidResponse: Bool = false
    public var bottomMixMaxLitres: Double = 4800.0
    public var decoMixUpTo49MaxLitres: Double = 2400.0
    public var decoMix50To99MaxLitres: Double = 2400.0
    public var decoO2MaxLitres: Double = 1200.0
    public var cnsWarnPct: Double = 80.0
    public var cnsErrorPct: Double = 100.0
    public var otuWarnPerDive: Double = 300.0
    public init() {}
}

public struct MultiDiveEntry: Codable, Hashable, Identifiable {
    public var id: String
    public var label: String
    public var surfaceIntervalBeforeMin: Double = 0.0
    public var levels: [DiveLevel]
    public init(id: String, label: String, surfaceIntervalBeforeMin: Double = 0.0, levels: [DiveLevel]) {
        self.id = id; self.label = label; self.surfaceIntervalBeforeMin = surfaceIntervalBeforeMin; self.levels = levels
    }
}

public struct Tissue: Codable, Hashable {
    public var pN2: Double
    public var pHe: Double
    public init(pN2: Double, pHe: Double) { self.pN2 = pN2; self.pHe = pHe }
    public var pInert: Double { pN2 + pHe }
}

public struct TissueState: Codable, Hashable {
    public var compartments: [Tissue]
    public init(_ compartments: [Tissue]) {
        precondition(compartments.count == 16, "TissueState must have exactly 16 compartments")
        self.compartments = compartments
    }
}

public struct DivePlanInput: Codable, Hashable {
    public var general = GeneralSettings()
    public var dive = DiveSettings()
    public var gasBreaks = GasBreakSettings()
    public var consumption = ConsumptionSettings()
    public var ccr = CcrSettings()
    public var alerts = AlertSettings()
    public var gases: [Gas] = DivePlanInput.defaultGases()
    public var levels: [DiveLevel] = [DiveLevel("lvl1", 40.0, 20.0)]
    public var previousTissues: TissueState? = nil
    public var previousCnsPct: Double = 0.0
    public var previousDailyOtu: Double = 0.0
    public var surfaceIntervalMin: Double = 0.0
    public var previousMaxDepthM: Double? = nil
    public init() {}

    public static func defaultGases() -> [Gas] {
        [
            Gas.fromPercent("g_bottom", 21, 35, .bottom, cylinder: CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: 2)),
            Gas.fromPercent("g_ean50", 50, 0, .deco, cylinder: CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: 1)),
            Gas.fromPercent("g_o2", 100, 0, .deco, cylinder: CylinderConfig(waterVolumeL: 12.0, fillPressureBar: 200.0, count: 1))
        ]
    }
}

public struct Environment: Codable, Hashable {
    public let pSurfaceBar: Double
    public let barPerMetre: Double
    public let salinityKgM3: Double
    public let elevationM: Int
}

public struct GasMetrics: Hashable {
    public let gas: Gas
    public let depthM: Double
    public let ppO2: Double
    public let ppN2: Double
    public let ppHe: Double
    public let endM: Double
    public let eadM: Double
    public let densityGL: Double
    public let modM: Double
    public let modExactM: Double
    public let minDepthM: Double
}

public enum SegmentType: String, Codable, CaseIterable, Hashable {
    case descent, level, ascent, switchGas, stop, airBreak
    public var label: String {
        switch self {
        case .descent: return "Descent"
        case .level: return "Level"
        case .ascent: return "Ascent"
        case .switchGas: return "Switch"
        case .stop: return "Stop"
        case .airBreak: return "Air Break"
        }
    }
}

public struct Segment: Codable, Hashable {
    public let type: SegmentType
    public let fromDepthM: Double
    public let toDepthM: Double
    public let durationMin: Double
    public let runtimeMin: Double
    public var gas: Gas
    public let ppO2Start: Double
    public let ppO2End: Double
    public let ppN2End: Double
    public let ppHeEnd: Double
    public let endM: Double
    public let densityGL: Double
    public let ceilingM: Double
    public let gf99Pct: Double
    public let surfaceGfPct: Double
    public var isCcr: Bool = false
    public var setpoint: Double? = nil
}

public struct StopSummary: Codable, Hashable {
    public let depthM: Int
    public let durationMin: Double
    public let runtimeMin: Double
    public var gas: Gas
    public var isSwitch: Bool = false
}

public struct TissueSnapshot: Codable, Hashable {
    public let runtimeMin: Double
    public let depthM: Double
    public let pAmbBar: Double
    public let ceilingM: Double
    public var gas: Gas
    public let ppO2: Double
    public let ppN2: Double
    public let ppHe: Double
    public let tissues: TissueState
    public let gf99Pct: Double
    public let surfaceGfPct: Double
    public let leadingCompartmentIndex: Int
    public let compartmentSaturationPct: [Double]
}

public struct OxygenSummary: Codable, Hashable {
    public let diveCnsPct: Double
    public let startCnsPct: Double
    public let endCnsPct: Double
    public let diveOtu: Double
    public let dailyOtu: Double
    public let maxPpO2: Double
}

public struct GasConsumptionResult: Codable, Hashable {
    public let gas: Gas
    public let usedLitres: Double
    public let reserveLitres: Double
    public let totalRequiredLitres: Double
    public let availableLitres: Double
    public let turnPressureBar: Double
    public let minPressureBar: Double
    public let remainingBar: Double
    public let isSufficient: Bool
}

public enum AlertLevel: String, Codable, CaseIterable, Hashable {
    case critical, high, medium, low
    public var label: String { rawValue.uppercased() }
    public var priority: Int {
        switch self {
        case .critical: return 0
        case .high: return 1
        case .medium: return 2
        case .low: return 3
        }
    }
}

public enum AlertCode: String, Codable, CaseIterable, Hashable {
    case PPO2_HIGH_BOTTOM, PPO2_HIGH_DECO, PPN2_HIGH_DECO, HYPOXIC, HYPOXIC_NO_TRAVEL, END_HIGH, DENSITY_HIGH,
         ICD_N2, ICD_FIFTHS, CNS, OTU, NO_O2_BREAKS, GAS_VOLUME, END_PRESSURE_LOW, GAS_ALERT_LIMITS, GF_EXTREME,
         SURFACE_GF_HIGH, ASCENT_FAST, DESCENT_FAST, MOD_EXCEEDED, SWITCH_MOD_MARGIN, CCR_DILUENT_HAZARD,
         CCR_BAILOUT_RISK, HPNS_RISK, REVERSE_PROFILE, SHORT_SURFACE_INTERVAL, YOYO_PROFILE, NO_DECO_REDUNDANCY,
         LONG_TTS_EXPOSURE, COLD_WATER, ALTITUDE_DIVING, TIGHT_NDL_MARGIN, LAST_STOP_6M_GAP, LIPID,
         NO_BREATHABLE_GAS, DECO_OVERFLOW

    public var title: String {
        switch self {
        case .PPO2_HIGH_BOTTOM: return "High Bottom ppO₂"
        case .PPO2_HIGH_DECO: return "High Deco ppO₂"
        case .PPN2_HIGH_DECO: return "High Deco ppN₂"
        case .HYPOXIC: return "Hypoxic Gas"
        case .HYPOXIC_NO_TRAVEL: return "Missing Travel Gas"
        case .END_HIGH: return "Narcosis / END"
        case .DENSITY_HIGH: return "Gas Density"
        case .ICD_N2: return "ICD ΔppN₂ Jump"
        case .ICD_FIFTHS: return "ICD Rule of Fifths"
        case .CNS: return "CNS O₂ Clock"
        case .OTU: return "Pulmonary OTU"
        case .NO_O2_BREAKS: return "Missing O₂ Air Breaks"
        case .GAS_VOLUME: return "Insufficient Gas"
        case .END_PRESSURE_LOW: return "Low Surfacing Pressure"
        case .GAS_ALERT_LIMITS: return "Gas Margin / Budget"
        case .GF_EXTREME: return "Gradient Factors"
        case .SURFACE_GF_HIGH: return "Surfacing Supersaturation"
        case .ASCENT_FAST: return "Fast Ascent Rate"
        case .DESCENT_FAST: return "Fast Descent Rate"
        case .MOD_EXCEEDED: return "MOD Exceeded"
        case .SWITCH_MOD_MARGIN: return "Tight Switch MOD Margin"
        case .CCR_DILUENT_HAZARD: return "CCR Diluent Hazard"
        case .CCR_BAILOUT_RISK: return "CCR Bailout Gas Risk"
        case .HPNS_RISK: return "HPNS Deep Helium Risk"
        case .REVERSE_PROFILE: return "Reverse Dive Profile"
        case .SHORT_SURFACE_INTERVAL: return "Short Surface Interval"
        case .YOYO_PROFILE: return "Sawtooth / Yo-Yo Profile"
        case .NO_DECO_REDUNDANCY: return "Deco Gas Strategy"
        case .LONG_TTS_EXPOSURE: return "Extended TTS / Thermal"
        case .COLD_WATER: return "Cold Water Exposure"
        case .ALTITUDE_DIVING: return "Altitude Diving"
        case .TIGHT_NDL_MARGIN: return "Tight NDL Margin"
        case .LAST_STOP_6M_GAP: return "6 m Final Stop Gap"
        case .LIPID: return "Helium Lipid Model"
        case .NO_BREATHABLE_GAS: return "No Breathable Gas"
        case .DECO_OVERFLOW: return "Deco Limit Exceeded"
        }
    }
}

public struct PlanAlert: Codable, Hashable {
    public let level: AlertLevel
    public let code: AlertCode
    public let message: String
    public var segmentIndex: Int? = nil
    public init(_ level: AlertLevel, _ code: AlertCode, _ message: String, _ segmentIndex: Int? = nil) {
        self.level = level; self.code = code; self.message = message; self.segmentIndex = segmentIndex
    }
}

public struct MixRecommendation: Hashable {
    public var bottomGas: Gas
    public var travelGas: Gas?
    public var travelGasDisplay: Gas
    public var travelRangeNote: String
    public var deco6mO2: Gas?
    public var deco21mEan50: Gas?
    public var deepDecoGas: Gas? = nil
    public var decoGases: [Gas]
    public var plannerGases: [Gas]
    public var modM: Double
    public var minDepthM: Double
    public var endAtMaxDepthM: Double
    public var densityAtMaxDepthGL: Double
    public var ppO2AtMaxDepth: Double
    public var notes: [String]
    public var isRecreational: Bool = false
}

public struct ContingencySummary: Codable, Hashable {
    public let title: String
    public let subtitle: String
    public let maxDepthM: Double
    public let totalRuntimeMin: Double
    public let totalDecoMin: Double
    public let ttsMin: Double
    public let cnsPct: Double
    public let otu: Double
    public let stops: [StopSummary]
    public let hasErrors: Bool
}

public struct DivePlanResult: Hashable {
    public var input: DivePlanInput
    public let env: Environment
    public var segments: [Segment]
    public var stops: [StopSummary]
    public let maxDepthM: Double
    public let totalRuntimeMin: Double
    public let totalDecoMin: Double
    public let ttsMin: Double
    public let firstStopM: Int
    public let ndlMin: Double
    public let startTissues: TissueState
    public let endTissues: TissueState
    public let endSurfaceGfPct: Double
    public var tissueTimeline: [TissueSnapshot]
    public let oxygen: OxygenSummary
    public var gasConsumption: [GasConsumptionResult]
    public var alerts: [PlanAlert]
    public var contingencies: [ContingencySummary] = []
    public var ccrBailout: ContingencySummary? = nil
    public var abortedReason: String? = nil

    public var hasErrors: Bool { abortedReason != nil || alerts.contains { $0.level == .critical } }
    public var hasWarnings: Bool { alerts.contains { $0.level != .critical } }
    public var highestAlertLevel: AlertLevel? {
        if abortedReason != nil { return .critical }
        return alerts.min { $0.level.priority < $1.level.priority }?.level
    }
}

public struct SurfaceIntervalEstimate: Hashable {
    public let surfaceIntervalMin: Double
    public let startTissues: TissueState
    public let postSiTissues: TissueState
    public let ambientN2Bar: Double
    public let excessInertBeforeBar: Double
    public let excessInertAfterBar: Double
    public let remainingExcessInertPct: Double
    public let residualN2PeakBar: Double
    public let residualHePeakBar: Double
    public let leadingCompartmentNumber: Int
    public let leadingN2HalfTimeMin: Double
    public let leadingSaturationBeforePct: Double
    public let leadingSaturationAfterPct: Double
    public let surfaceGfBeforePct: Double
    public let surfaceGfAfterPct: Double
    public let cnsBeforePct: Double
    public let cnsAfterPct: Double
    public let freshNdlMin: Double
    public let nextDiveNdlMin: Double
    public let compartmentLoadsBeforePct: [Double]
    public let compartmentLoadsAfterPct: [Double]
    public let estimatedCleanTimeMin: Double
}

public struct MultiDiveDayResult: Hashable {
    public let diveResults: [DivePlanResult]
    public let surfaceIntervalsMin: [Double]
    public let postSurfaceIntervalTissues: [TissueState]
    public let totalDailyOtu: Double
    public let peakEndCnsPct: Double
    public init(diveResults: [DivePlanResult], surfaceIntervalsMin: [Double], postSurfaceIntervalTissues: [TissueState],
                totalDailyOtu: Double, peakEndCnsPct: Double) {
        self.diveResults = diveResults; self.surfaceIntervalsMin = surfaceIntervalsMin
        self.postSurfaceIntervalTissues = postSurfaceIntervalTissues; self.totalDailyOtu = totalDailyOtu; self.peakEndCnsPct = peakEndCnsPct
    }
}
