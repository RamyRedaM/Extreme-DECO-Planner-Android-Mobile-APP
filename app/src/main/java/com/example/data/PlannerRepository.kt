package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.engine.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.plannerDataStore: DataStore<Preferences> by preferencesDataStore(name = "extreme_deco_prefs")

data class SavedPlanSummary(
    val id: String,
    val name: String,
    val timestampMs: Long,
    val summaryLine: String,
    val payloadJson: String
)

class PlannerRepository(private val context: Context) {

    private object Keys {
        val DISCLAIMER_ACCEPTED = booleanPreferencesKey("disclaimer_accepted")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val SAVED_PLANS_JSON = stringPreferencesKey("saved_plans_json")
        val LAST_STATE_JSON = stringPreferencesKey("last_state_json")
    }

    val disclaimerAcceptedFlow: Flow<Boolean> = context.plannerDataStore.data.map { prefs ->
        prefs[Keys.DISCLAIMER_ACCEPTED] ?: false
    }

    val darkThemeFlow: Flow<Boolean> = context.plannerDataStore.data.map { prefs ->
        prefs[Keys.DARK_THEME] ?: false
    }

    val savedPlansFlow: Flow<List<SavedPlanSummary>> = context.plannerDataStore.data.map { prefs ->
        parseSavedPlans(prefs[Keys.SAVED_PLANS_JSON])
    }

    val lastStateJsonFlow: Flow<String?> = context.plannerDataStore.data.map { prefs ->
        prefs[Keys.LAST_STATE_JSON]
    }

    suspend fun setDisclaimerAccepted(accepted: Boolean) {
        context.plannerDataStore.edit { it[Keys.DISCLAIMER_ACCEPTED] = accepted }
    }

    suspend fun setDarkTheme(dark: Boolean) {
        context.plannerDataStore.edit { it[Keys.DARK_THEME] = dark }
    }

    suspend fun persistLastState(input: DivePlanInput, dives: List<MultiDiveEntry>) {
        val json = serializePlannerState(input, dives)
        context.plannerDataStore.edit { it[Keys.LAST_STATE_JSON] = json }
    }

    suspend fun saveNamedPlan(name: String, summaryLine: String, input: DivePlanInput, dives: List<MultiDiveEntry>) {
        val payload = serializePlannerState(input, dives)
        val entry = SavedPlanSummary(
            id = "plan_${System.currentTimeMillis()}",
            name = name.trim().ifEmpty { "Unnamed Dive Plan" },
            timestampMs = System.currentTimeMillis(),
            summaryLine = summaryLine,
            payloadJson = payload
        )
        context.plannerDataStore.edit { prefs ->
            val current = parseSavedPlans(prefs[Keys.SAVED_PLANS_JSON]).toMutableList()
            current.add(0, entry)
            val trimmed = current.take(50)
            prefs[Keys.SAVED_PLANS_JSON] = encodeSavedPlans(trimmed)
        }
    }

    suspend fun deleteSavedPlan(id: String) {
        context.plannerDataStore.edit { prefs ->
            val filtered = parseSavedPlans(prefs[Keys.SAVED_PLANS_JSON]).filterNot { it.id == id }
            prefs[Keys.SAVED_PLANS_JSON] = encodeSavedPlans(filtered)
        }
    }

    private inline fun <reified T : Enum<T>> safeEnum(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }

    fun deserializePlannerState(raw: String): Pair<DivePlanInput, List<MultiDiveEntry>>? {
        return try {
            val root = JSONObject(raw)
            val gObj = root.getJSONObject("general")
            val general = GeneralSettings(
                planningType = safeEnum(gObj.optString("planningType"), PlanningType.OPEN_CIRCUIT),
                units = safeEnum(gObj.optString("units"), UnitSystem.METRIC),
                decoModel = safeEnum(gObj.optString("decoModel"), DecoModel.ZHL16C),
                gfLow = gObj.optInt("gfLow", 30).coerceIn(5, 100),
                gfHigh = gObj.optInt("gfHigh", 70).coerceIn(5, 100),
                vpmConservatism = gObj.optInt("vpmConservatism", 2).coerceIn(0, 5),
                useCompartment1b = gObj.optBoolean("use1b", false),
                salinityPreset = safeEnum(gObj.optString("salinityPreset"), SalinityPreset.SALT),
                customSalinityKgM3 = gObj.optDouble("customSalinity", 1030.0).coerceIn(950.0, 1250.0),
                elevationM = gObj.optInt("elevationM", 0).coerceIn(0, 5000),
                arrivedUnder12hAtAltitude = gObj.optBoolean("arrivedUnder12h", false),
                surfaceTempC = gObj.optInt("surfaceTempC", 22).coerceIn(-10, 45),
                darkTheme = if (gObj.optInt("themeSchema", 1) >= 2) gObj.optBoolean("darkTheme", false) else false,
                planStyle = safeEnum(gObj.optString("planStyle"), PlanStyle.DETAILED),
                btIncludesDescent = gObj.optBoolean("btIncludesDescent", true),
                standardMixesOnly = gObj.optBoolean("standardMixesOnly", false),
                gasSelectionMode = safeEnum(gObj.optString("gasSelectionMode"), GasSelectionMode.BEST_GAS_MIX),
                gasAgency = safeEnum(gObj.optString("gasAgency"), GasAgency.OPTIMIZED)
            )
            val dObj = root.getJSONObject("dive")
            val dive = DiveSettings(
                ppO2Bottom = dObj.optDouble("ppO2Bottom", 1.40),
                ppO2Deco = dObj.optDouble("ppO2Deco", 1.60),
                ppO2Min = dObj.optDouble("ppO2Min", 0.18),
                ppN2Max = dObj.optDouble("ppN2Max", 3.95),
                ppN2MaxDeco = dObj.optDouble("ppN2MaxDeco", 3.16),
                maxEndM = dObj.optDouble("maxEndM", 30.0),
                o2Narcotic = dObj.optBoolean("o2Narcotic", true),
                maxGasDensityGL = dObj.optDouble("maxGasDensityGL", 5.2),
                descentRateMMin = dObj.optDouble("descentRate", 20.0),
                ascentRateToFirstStopMMin = dObj.optDouble("ascentRate", 9.0),
                ascentDecoRateMMin = dObj.optDouble("decoAscentRate", 9.0),
                ascentRateToSurfaceMMin = dObj.optDouble("surfaceRate", 3.0),
                stopIncrementM = dObj.optInt("stopInc", 3),
                lastStopM = dObj.optInt("lastStop", 3),
                minStopTimeMin = dObj.optDouble("minStop", 1.0),
                mixChangeExtraTimeMin = dObj.optDouble("mixChangeTime", 1.0),
                stopOnGasSwitch = dObj.optBoolean("stopOnSwitch", true)
            )
            val bObj = root.optJSONObject("breaks")
            val breaks = if (bObj != null) GasBreakSettings(
                enabled = bObj.optBoolean("enabled", false),
                applyDepthMaxM = bObj.optDouble("depth", 6.0),
                breakAfterMin = bObj.optDouble("after", 20.0),
                breakTimeMin = bObj.optDouble("time", 5.0)
            ) else GasBreakSettings()

            val cObj = root.optJSONObject("consumption")
            val consumption = if (cObj != null) ConsumptionSettings(
                bottomRmvLMin = cObj.optDouble("bottomRmv", 20.0).coerceIn(5.0, 60.0),
                decoRmvLMin = cObj.optDouble("decoRmv", 15.0).coerceIn(5.0, 50.0),
                stressFactor = cObj.optDouble("stress", 1.5).coerceIn(1.0, 3.0),
                bottomReserveRule = safeEnum(cObj.optString("bottomRule"), ReserveRuleBottom.THIRDS),
                decoReserveRule = safeEnum(cObj.optString("decoRule"), ReserveRuleDeco.MULTIPLIER_1_5)
            ) else ConsumptionSettings()

            val ccrObj = root.optJSONObject("ccr")
            val ccr = if (ccrObj != null) CcrSettings(
                setpointDescent = ccrObj.optDouble("spDesc", 0.7).coerceIn(0.4, 1.4),
                setpointBottom = ccrObj.optDouble("spBot", 1.3).coerceIn(0.5, 1.5),
                setpointDeco = ccrObj.optDouble("spDeco", 1.3).coerceIn(0.5, 1.6),
                switchToHighDepthM = ccrObj.optDouble("swHigh", 20.0).coerceIn(3.0, 60.0),
                switchToDecoDepthM = ccrObj.optDouble("swDeco", 6.0).coerceIn(3.0, 30.0)
            ) else CcrSettings()

            val aObj = root.optJSONObject("alerts")
            val alerts = if (aObj != null) AlertSettings(
                icdDeltaPpN2Max = aObj.optDouble("icdN2", 0.5).coerceIn(0.1, 2.0),
                icdRuleOfFifths = aObj.optBoolean("icdFifths", true),
                considerLipidResponse = aObj.optBoolean("lipid", false),
                bottomMixMaxLitres = aObj.optDouble("botMaxL", 4800.0).coerceIn(500.0, 25000.0),
                decoMixUpTo49MaxLitres = aObj.optDouble("deco49L", 2400.0).coerceIn(300.0, 15000.0),
                decoMix50To99MaxLitres = aObj.optDouble("deco99L", 2400.0).coerceIn(300.0, 15000.0),
                decoO2MaxLitres = aObj.optDouble("decoO2L", 1200.0).coerceIn(200.0, 10000.0),
                cnsWarnPct = aObj.optDouble("cnsWarn", 80.0).coerceIn(20.0, 150.0),
                cnsErrorPct = aObj.optDouble("cnsErr", 100.0).coerceIn(40.0, 200.0),
                otuWarnPerDive = aObj.optDouble("otuWarn", 300.0).coerceIn(50.0, 1000.0)
            ) else AlertSettings()

            val cylinderSchema = gObj.optInt("cylinderSchema", 1)
            val gasesArr = root.optJSONArray("gases")
            val gases = if (gasesArr != null && gasesArr.length() > 0) {
                List(gasesArr.length()) { idx ->
                    val go = gasesArr.getJSONObject(idx)
                    val o2 = go.optDouble("o2", 0.21).coerceIn(0.05, 1.0)
                    val he = go.optDouble("he", 0.0).coerceIn(0.0, (1.0 - o2).coerceAtLeast(0.0))
                    val rawVol = go.optDouble("cylVol", 12.0).coerceIn(1.0, 40.0)
                    // Upgrade legacy 7L/11L factory defaults to 12L once; preserve all edits in schema >= 2
                    val migratedVol = if (cylinderSchema < 2 && (rawVol == 7.0 || rawVol == 11.0)) 12.0 else rawVol
                    Gas(
                        id = go.optString("id", "gas_$idx"),
                        o2 = o2,
                        he = he,
                        role = safeEnum(go.optString("role"), GasRole.BOTTOM),
                        enabled = go.optBoolean("enabled", true),
                        modOverrideM = if (go.has("modOverride") && !go.isNull("modOverride")) go.optDouble("modOverride").takeIf { it.isFinite() } else null,
                        cylinder = CylinderConfig(
                            waterVolumeL = migratedVol,
                            fillPressureBar = go.optDouble("cylBar", 200.0).coerceIn(50.0, 350.0),
                            count = go.optInt("cylCount", 1).coerceIn(1, 20)
                        )
                    )
                }
            } else {
                listOf(Gas("g_air", 0.21, 0.0, GasRole.BOTTOM, cylinder = CylinderConfig(12.0, 200.0, 2)))
            }

            val divesArr = root.optJSONArray("dives")
            val dives = if (divesArr != null && divesArr.length() > 0) {
                List(divesArr.length()) { idx ->
                    val doj = divesArr.getJSONObject(idx)
                    val lvlsArr = doj.optJSONArray("levels")
                    val lvls = if (lvlsArr != null && lvlsArr.length() > 0) {
                        List(lvlsArr.length()) { lIdx ->
                            val lo = lvlsArr.getJSONObject(lIdx)
                            DiveLevel(
                                id = lo.optString("id", "lvl_${idx}_$lIdx"),
                                depthM = lo.optDouble("depth", 40.0).coerceIn(3.0, 180.0),
                                timeMin = lo.optDouble("time", 20.0).coerceIn(1.0, 360.0),
                                gasId = if (lo.has("gasId") && !lo.isNull("gasId")) lo.optString("gasId") else null
                            )
                        }
                    } else {
                        listOf(DiveLevel("lvl_${idx}_0", 40.0, 20.0))
                    }
                    MultiDiveEntry(
                        id = doj.optString("id", "dive_${idx + 1}"),
                        label = doj.optString("label", "Dive ${idx + 1}"),
                        surfaceIntervalBeforeMin = doj.optDouble("si", 0.0).coerceIn(0.0, 1440.0),
                        levels = lvls
                    )
                }
            } else {
                listOf(MultiDiveEntry("dive_1", "Dive 1", 0.0, listOf(DiveLevel("lvl1", 40.0, 20.0))))
            }

            val input = DivePlanInput(
                general = general,
                dive = dive,
                gasBreaks = breaks,
                consumption = consumption,
                ccr = ccr,
                alerts = alerts,
                gases = gases,
                levels = dives.firstOrNull()?.levels ?: listOf(DiveLevel("lvl1", 40.0, 20.0))
            )
            input to dives
        } catch (_: Exception) {
            null
        }
    }

    private fun serializePlannerState(input: DivePlanInput, dives: List<MultiDiveEntry>): String {
        val root = JSONObject()
        root.put("general", JSONObject().apply {
            put("planningType", input.general.planningType.name)
            put("units", input.general.units.name)
            put("decoModel", input.general.decoModel.name)
            put("gfLow", input.general.gfLow)
            put("gfHigh", input.general.gfHigh)
            put("vpmConservatism", input.general.vpmConservatism)
            put("use1b", input.general.useCompartment1b)
            put("salinityPreset", input.general.salinityPreset.name)
            put("customSalinity", input.general.customSalinityKgM3)
            put("elevationM", input.general.elevationM)
            put("arrivedUnder12h", input.general.arrivedUnder12hAtAltitude)
            put("surfaceTempC", input.general.surfaceTempC)
            put("themeSchema", 2)
            put("cylinderSchema", 2)
            put("darkTheme", input.general.darkTheme)
            put("planStyle", input.general.planStyle.name)
            put("btIncludesDescent", input.general.btIncludesDescent)
            put("standardMixesOnly", input.general.standardMixesOnly)
            put("gasSelectionMode", input.general.gasSelectionMode.name)
            put("gasAgency", input.general.gasAgency.name)
        })
        root.put("dive", JSONObject().apply {
            put("ppO2Bottom", input.dive.ppO2Bottom)
            put("ppO2Deco", input.dive.ppO2Deco)
            put("ppO2Min", input.dive.ppO2Min)
            put("ppN2Max", input.dive.ppN2Max)
            put("ppN2MaxDeco", input.dive.ppN2MaxDeco)
            put("maxEndM", input.dive.maxEndM)
            put("o2Narcotic", input.dive.o2Narcotic)
            put("maxGasDensityGL", input.dive.maxGasDensityGL)
            put("descentRate", input.dive.descentRateMMin)
            put("ascentRate", input.dive.ascentRateToFirstStopMMin)
            put("decoAscentRate", input.dive.ascentDecoRateMMin)
            put("surfaceRate", input.dive.ascentRateToSurfaceMMin)
            put("stopInc", input.dive.stopIncrementM)
            put("lastStop", input.dive.lastStopM)
            put("minStop", input.dive.minStopTimeMin)
            put("mixChangeTime", input.dive.mixChangeExtraTimeMin)
            put("stopOnSwitch", input.dive.stopOnGasSwitch)
        })
        root.put("breaks", JSONObject().apply {
            put("enabled", input.gasBreaks.enabled)
            put("depth", input.gasBreaks.applyDepthMaxM)
            put("after", input.gasBreaks.breakAfterMin)
            put("time", input.gasBreaks.breakTimeMin)
        })
        root.put("consumption", JSONObject().apply {
            put("bottomRmv", input.consumption.bottomRmvLMin)
            put("decoRmv", input.consumption.decoRmvLMin)
            put("stress", input.consumption.stressFactor)
            put("bottomRule", input.consumption.bottomReserveRule.name)
            put("decoRule", input.consumption.decoReserveRule.name)
        })
        root.put("ccr", JSONObject().apply {
            put("spDesc", input.ccr.setpointDescent)
            put("spBot", input.ccr.setpointBottom)
            put("spDeco", input.ccr.setpointDeco)
            put("swHigh", input.ccr.switchToHighDepthM)
            put("swDeco", input.ccr.switchToDecoDepthM)
        })
        root.put("alerts", JSONObject().apply {
            put("icdN2", input.alerts.icdDeltaPpN2Max)
            put("icdFifths", input.alerts.icdRuleOfFifths)
            put("lipid", input.alerts.considerLipidResponse)
            put("botMaxL", input.alerts.bottomMixMaxLitres)
            put("deco49L", input.alerts.decoMixUpTo49MaxLitres)
            put("deco99L", input.alerts.decoMix50To99MaxLitres)
            put("decoO2L", input.alerts.decoO2MaxLitres)
            put("cnsWarn", input.alerts.cnsWarnPct)
            put("cnsErr", input.alerts.cnsErrorPct)
            put("otuWarn", input.alerts.otuWarnPerDive)
        })
        root.put("gases", JSONArray().apply {
            for (g in input.gases) {
                put(JSONObject().apply {
                    put("id", g.id)
                    put("o2", g.o2)
                    put("he", g.he)
                    put("role", g.role.name)
                    put("enabled", g.enabled)
                    put("modOverride", g.modOverrideM)
                    put("cylVol", g.cylinder.waterVolumeL)
                    put("cylBar", g.cylinder.fillPressureBar)
                    put("cylCount", g.cylinder.count)
                })
            }
        })
        root.put("dives", JSONArray().apply {
            for (d in dives) {
                put(JSONObject().apply {
                    put("id", d.id)
                    put("label", d.label)
                    put("si", d.surfaceIntervalBeforeMin)
                    put("levels", JSONArray().apply {
                        for (l in d.levels) {
                            put(JSONObject().apply {
                                put("id", l.id)
                                put("depth", l.depthM)
                                put("time", l.timeMin)
                                put("gasId", l.gasId)
                            })
                        }
                    })
                })
            }
        })
        return root.toString()
    }

    private fun parseSavedPlans(raw: String?): List<SavedPlanSummary> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            List(arr.length()) { idx ->
                val o = arr.getJSONObject(idx)
                SavedPlanSummary(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    timestampMs = o.getLong("ts"),
                    summaryLine = o.getString("summary"),
                    payloadJson = o.getString("payload")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeSavedPlans(plans: List<SavedPlanSummary>): String {
        val arr = JSONArray()
        for (p in plans) {
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("ts", p.timestampMs)
                put("summary", p.summaryLine)
                put("payload", p.payloadJson)
            })
        }
        return arr.toString()
    }
}
