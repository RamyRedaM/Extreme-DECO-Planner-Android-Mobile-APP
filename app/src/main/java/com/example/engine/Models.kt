package com.example.engine

import kotlin.math.roundToInt

enum class PlanningType(val label: String) {
    OPEN_CIRCUIT("Open Circuit"),
    CCR("CCR (Closed Circuit)")
}

enum class GasSelectionMode(val label: String) {
    BEST_GAS_MIX("Best GAS Mix"),
    USER_INPUT_GAS("User input GAS")
}

enum class UnitSystem(val label: String, val depthUnit: String, val volUnit: String, val pressureUnit: String, val tempUnit: String) {
    METRIC("Metric (m / L / bar / °C)", "m", "L", "bar", "°C"),
    IMPERIAL("Imperial (ft / cuft / psi / °F)", "ft", "ft³", "psi", "°F")
}

enum class DecoModel(val label: String, val shortLabel: String, val description: String) {
    ZHL16A("Bühlmann ZHL-16A", "ZHL-16A", "Original experimental/theoretical coefficients"),
    ZHL16B("Bühlmann ZHL-16B", "ZHL-16B", "More conservative middle compartments, for printed tables"),
    ZHL16C("Bühlmann ZHL-16C", "ZHL-16C", "Most conservative middle compartments, standard for dive computers (Recommended)"),
    VPMB("VPM-B (Experimental)", "VPM-B", "Varying Permeability bubble model with Boyle's law compensation (Experimental)")
}

enum class SalinityPreset(val label: String, val densityKgM3: Double) {
    FRESH("Fresh (1000 kg/m³)", Constants.SALINITY_FRESH),
    EN13319("EN13319 (1020 kg/m³)", Constants.SALINITY_EN13319),
    SALT("Salt (1030 kg/m³)", Constants.SALINITY_SALT),
    RED_SEA("Red Sea (1030 kg/m³)", Constants.SALINITY_RED_SEA),
    CUSTOM("Custom", Constants.SALINITY_SALT)
}

enum class PlanStyle(val label: String) {
    DETAILED("Detailed"),
    COMPACT("Compact (Stops Only)")
}

enum class GasRole(val label: String) {
    TRAVEL("Travel"),
    BOTTOM("Bottom"),
    DILUENT("Diluent"),
    DECO("Deco"),
    BAILOUT("Bailout")
}

enum class ReserveRuleBottom(val label: String) {
    THIRDS("Rule of Thirds (1.5×)"),
    QUARTERS("Rule of Quarters (1.33×)"),
    HALF("Half Reserve (2.0×)"),
    ROCK_BOTTOM("Rock-Bottom (2× Stress)")
}

enum class ReserveRuleDeco(val label: String) {
    MULTIPLIER_1_5("1.5× Required"),
    LOST_GAS("Lost-Gas Scenario")
}

data class CylinderConfig(
    val waterVolumeL: Double = 12.0,
    val fillPressureBar: Double = 200.0,
    val count: Int = 2
) {
    val availableLitres: Double get() = waterVolumeL * fillPressureBar * count
}

data class Gas(
    val id: String,
    val o2: Double,          // fraction 0.0..1.0
    val he: Double,          // fraction 0.0..1.0
    val role: GasRole,
    val enabled: Boolean = true,
    val modOverrideM: Double? = null,
    val cylinder: CylinderConfig = if (role == GasRole.DECO || role == GasRole.BAILOUT || role == GasRole.TRAVEL) {
        CylinderConfig(waterVolumeL = 12.0, fillPressureBar = 200.0, count = 1)
    } else {
        CylinderConfig(waterVolumeL = 12.0, fillPressureBar = 200.0, count = 2)
    }
) {
    val n2: Double get() = (1.0 - o2 - he).coerceAtLeast(0.0)
    val o2Pct: Int get() = (o2 * 100.0).roundToInt()
    val hePct: Int get() = (he * 100.0).roundToInt()
    val n2Pct: Int get() = (100 - o2Pct - hePct).coerceAtLeast(0)

    val displayName: String
        get() = when {
            hePct > 0 -> "TX $o2Pct/$hePct"
            o2Pct == 100 -> "O2 100%"
            o2Pct == 21 -> "Air"
            else -> "EAN$o2Pct"
        }

    companion object {
        fun fromPercent(
            id: String,
            o2Pct: Int,
            hePct: Int,
            role: GasRole,
            enabled: Boolean = true,
            modOverrideM: Double? = null,
            cylinder: CylinderConfig? = null
        ): Gas {
            val clampedO2 = o2Pct.coerceIn(5, 100)
            val clampedHe = hePct.coerceIn(0, 100 - clampedO2)
            return Gas(
                id = id,
                o2 = clampedO2 / 100.0,
                he = clampedHe / 100.0,
                role = role,
                enabled = enabled,
                modOverrideM = modOverrideM,
                cylinder = cylinder ?: if (role == GasRole.DECO || role == GasRole.BAILOUT || role == GasRole.TRAVEL) {
                    CylinderConfig(12.0, 200.0, 1)
                } else {
                    CylinderConfig(12.0, 200.0, 2)
                }
            )
        }
    }
}

data class DiveLevel(
    val id: String,
    val depthM: Double,
    val timeMin: Double,
    val gasId: String? = null  // null = auto bottom mix
)

data class GeneralSettings(
    val planningType: PlanningType = PlanningType.OPEN_CIRCUIT,
    val units: UnitSystem = UnitSystem.METRIC,
    val decoModel: DecoModel = DecoModel.ZHL16C,
    val gfLow: Int = 30,
    val gfHigh: Int = 70,
    val vpmConservatism: Int = 2,      // 0..5 for experimental VPM-B
    val useCompartment1b: Boolean = false,
    val salinityPreset: SalinityPreset = SalinityPreset.SALT,
    val customSalinityKgM3: Double = Constants.SALINITY_SALT,
    val elevationM: Int = 0,
    val arrivedUnder12hAtAltitude: Boolean = false,
    val surfaceTempC: Int = 22,
    val darkTheme: Boolean = false,
    val planStyle: PlanStyle = PlanStyle.DETAILED,
    val btIncludesDescent: Boolean = true,
    val standardMixesOnly: Boolean = false,
    val gasSelectionMode: GasSelectionMode = GasSelectionMode.BEST_GAS_MIX
) {
    val effectiveSalinity: Double
        get() = if (salinityPreset == SalinityPreset.CUSTOM || salinityPreset == SalinityPreset.RED_SEA) {
            customSalinityKgM3
        } else {
            salinityPreset.densityKgM3
        }
}

data class DiveSettings(
    val ppO2Bottom: Double = 1.40,
    val ppO2Deco: Double = 1.60,
    val ppO2Min: Double = 0.18,
    val ppN2Max: Double = 3.95,
    val ppN2MaxDeco: Double = 3.16,
    val maxEndM: Double = 30.0,
    val o2Narcotic: Boolean = true,
    val maxGasDensityGL: Double = 5.2,
    val descentRateMMin: Double = 20.0,
    val ascentRateToFirstStopMMin: Double = 9.0,
    val ascentDecoRateMMin: Double = 9.0,
    val ascentRateToSurfaceMMin: Double = 3.0,
    val stopIncrementM: Int = 3,
    val lastStopM: Int = 3,
    val minStopTimeMin: Double = 1.0,
    val mixChangeExtraTimeMin: Double = 1.0,
    val stopOnGasSwitch: Boolean = true
)

data class GasBreakSettings(
    val enabled: Boolean = false,
    val applyDepthMaxM: Double = 6.0,
    val breakAfterMin: Double = 20.0,
    val breakTimeMin: Double = 5.0
)

data class ConsumptionSettings(
    val bottomRmvLMin: Double = 20.0,
    val decoRmvLMin: Double = 15.0,
    val stressFactor: Double = 1.5,
    val bottomReserveRule: ReserveRuleBottom = ReserveRuleBottom.THIRDS,
    val decoReserveRule: ReserveRuleDeco = ReserveRuleDeco.MULTIPLIER_1_5
)

data class CcrSettings(
    val setpointDescent: Double = 0.7,
    val setpointBottom: Double = 1.3,
    val setpointDeco: Double = 1.3,
    val switchToHighDepthM: Double = 20.0,
    val switchToDecoDepthM: Double = 6.0
)

data class AlertSettings(
    val icdDeltaPpN2Max: Double = 0.5,
    val icdRuleOfFifths: Boolean = true,
    val considerLipidResponse: Boolean = false,
    val bottomMixMaxLitres: Double = 4800.0,
    val decoMixUpTo49MaxLitres: Double = 2400.0,
    val decoMix50To99MaxLitres: Double = 2400.0,
    val decoO2MaxLitres: Double = 1200.0,
    val cnsWarnPct: Double = 80.0,
    val cnsErrorPct: Double = 100.0,
    val otuWarnPerDive: Double = 300.0
)

data class MultiDiveEntry(
    val id: String,
    val label: String,
    val surfaceIntervalBeforeMin: Double = 0.0,   // 0 for Dive 1; > 0 for repetitive dives
    val levels: List<DiveLevel>
)

data class DivePlanInput(
    val general: GeneralSettings = GeneralSettings(),
    val dive: DiveSettings = DiveSettings(),
    val gasBreaks: GasBreakSettings = GasBreakSettings(),
    val consumption: ConsumptionSettings = ConsumptionSettings(),
    val ccr: CcrSettings = CcrSettings(),
    val alerts: AlertSettings = AlertSettings(),
    val gases: List<Gas> = defaultGases(),
    val levels: List<DiveLevel> = listOf(DiveLevel("lvl1", 40.0, 20.0)),
    val previousTissues: TissueState? = null,
    val previousCnsPct: Double = 0.0,
    val previousDailyOtu: Double = 0.0,
    val surfaceIntervalMin: Double = 0.0
) {
    companion object {
        fun defaultGases(): List<Gas> = listOf(
            Gas.fromPercent("g_bottom", 21, 35, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2)),
            Gas.fromPercent("g_ean50", 50, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1)),
            Gas.fromPercent("g_o2", 100, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
        )
    }
}

data class Tissue(
    val pN2: Double,   // bar
    val pHe: Double    // bar
) {
    val pInert: Double get() = pN2 + pHe
}

data class TissueState(
    val compartments: List<Tissue>
) {
    init {
        require(compartments.size == 16) { "TissueState must have exactly 16 compartments" }
    }
}

data class Environment(
    val pSurfaceBar: Double,
    val barPerMetre: Double,
    val salinityKgM3: Double,
    val elevationM: Int
)

data class GasMetrics(
    val gas: Gas,
    val depthM: Double,
    val ppO2: Double,
    val ppN2: Double,
    val ppHe: Double,
    val endM: Double,
    val eadM: Double,
    val densityGL: Double,
    val modM: Double,
    val modExactM: Double,
    val minDepthM: Double
)

enum class SegmentType(val label: String) {
    DESCENT("Descent"),
    LEVEL("Level"),
    ASCENT("Ascent"),
    SWITCH("Switch"),
    STOP("Stop"),
    BREAK("Air Break")
}

data class Segment(
    val type: SegmentType,
    val fromDepthM: Double,
    val toDepthM: Double,
    val durationMin: Double,
    val runtimeMin: Double,
    val gas: Gas,
    val ppO2Start: Double,
    val ppO2End: Double,
    val ppN2End: Double,
    val ppHeEnd: Double,
    val endM: Double,
    val densityGL: Double,
    val ceilingM: Double,
    val gf99Pct: Double,
    val surfaceGfPct: Double,
    val isCcr: Boolean = false,
    val setpoint: Double? = null
)

data class StopSummary(
    val depthM: Int,
    val durationMin: Double,
    val runtimeMin: Double,
    val gas: Gas,
    val isSwitch: Boolean = false
)

data class TissueSnapshot(
    val runtimeMin: Double,
    val depthM: Double,
    val pAmbBar: Double,
    val ceilingM: Double,
    val gas: Gas,
    val ppO2: Double,
    val ppN2: Double,
    val ppHe: Double,
    val tissues: TissueState,
    val gf99Pct: Double,
    val surfaceGfPct: Double,
    val leadingCompartmentIndex: Int,
    val compartmentSaturationPct: List<Double> // % of M-value gradient at current P_amb (or total loading %)
)

data class OxygenSummary(
    val diveCnsPct: Double,
    val startCnsPct: Double,
    val endCnsPct: Double,
    val diveOtu: Double,
    val dailyOtu: Double,
    val maxPpO2: Double
)

data class GasConsumptionResult(
    val gas: Gas,
    val usedLitres: Double,
    val reserveLitres: Double,
    val totalRequiredLitres: Double,
    val availableLitres: Double,
    val turnPressureBar: Double,
    val minPressureBar: Double,
    val remainingBar: Double,
    val isSufficient: Boolean
)

enum class AlertLevel(val label: String) {
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error")
}

enum class AlertCode {
    PPO2_HIGH_BOTTOM,
    PPO2_HIGH_DECO,
    HYPOXIC,
    END_HIGH,
    DENSITY_HIGH,
    ICD_N2,
    ICD_FIFTHS,
    CNS,
    OTU,
    GAS_VOLUME,
    GAS_ALERT_LIMITS,
    GF_EXTREME,
    ASCENT_FAST,
    MOD_EXCEEDED,
    LIPID,
    NO_BREATHABLE_GAS,
    DECO_OVERFLOW
}

data class PlanAlert(
    val level: AlertLevel,
    val code: AlertCode,
    val message: String,
    val segmentIndex: Int? = null
)

data class MixRecommendation(
    val bottomGas: Gas,
    val travelGas: Gas?,
    val travelGasDisplay: Gas,
    val travelRangeNote: String,
    val deco6mO2: Gas?,
    val deco21mEan50: Gas?,
    val deepDecoGas: Gas? = null,
    val decoGases: List<Gas>,
    val plannerGases: List<Gas>,
    val modM: Double,
    val minDepthM: Double,
    val endAtMaxDepthM: Double,
    val densityAtMaxDepthGL: Double,
    val ppO2AtMaxDepth: Double,
    val notes: List<String>,
    val isRecreational: Boolean = false
)

data class ContingencySummary(
    val title: String,
    val subtitle: String,
    val maxDepthM: Double,
    val totalRuntimeMin: Double,
    val totalDecoMin: Double,
    val ttsMin: Double,
    val cnsPct: Double,
    val otu: Double,
    val stops: List<StopSummary>,
    val hasErrors: Boolean
)

data class DivePlanResult(
    val input: DivePlanInput,
    val env: Environment,
    val segments: List<Segment>,
    val stops: List<StopSummary>,
    val maxDepthM: Double,
    val totalRuntimeMin: Double,
    val totalDecoMin: Double,
    val ttsMin: Double,
    val firstStopM: Int,
    val ndlMin: Double,
    val startTissues: TissueState,
    val endTissues: TissueState,
    val endSurfaceGfPct: Double,
    val tissueTimeline: List<TissueSnapshot>,
    val oxygen: OxygenSummary,
    val gasConsumption: List<GasConsumptionResult>,
    val alerts: List<PlanAlert>,
    val contingencies: List<ContingencySummary> = emptyList(),
    val ccrBailout: ContingencySummary? = null,
    val abortedReason: String? = null
) {
    val hasErrors: Boolean get() = abortedReason != null || alerts.any { it.level == AlertLevel.ERROR }
    val hasWarnings: Boolean get() = alerts.any { it.level == AlertLevel.WARNING }
}

data class MultiDiveDayResult(
    val diveResults: List<DivePlanResult>,
    val surfaceIntervalsMin: List<Double>, // length == diveResults.size (first is 0.0)
    val postSurfaceIntervalTissues: List<TissueState>, // tissues right at start of each dive
    val totalDailyOtu: Double,
    val peakEndCnsPct: Double
)
