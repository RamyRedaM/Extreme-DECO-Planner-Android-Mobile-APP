package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PlannerRepository
import com.example.data.SavedPlanSummary
import com.example.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class PlannerUiState(
    val input: DivePlanInput = DivePlanInput(),
    val dives: List<MultiDiveEntry> = listOf(
        MultiDiveEntry(
            id = "dive_1",
            label = "Dive 1",
            surfaceIntervalBeforeMin = 0.0,
            levels = listOf(DiveLevel("lvl_1", 40.0, 20.0))
        )
    ),
    val activeDiveIndex: Int = 0,
    val dayResult: MultiDiveDayResult? = null,
    val bestMixRec: MixRecommendation? = null,
    val isComputing: Boolean = false,
    val disclaimerAccepted: Boolean = false,
    val disclaimerLoaded: Boolean = false,
    val savedPlans: List<SavedPlanSummary> = emptyList(),
    val snackbarMessage: String? = null
) {
    val activeDive: MultiDiveEntry
        get() = dives.getOrElse(activeDiveIndex) { dives.first() }

    val activeResult: DivePlanResult?
        get() = dayResult?.diveResults?.getOrNull(activeDiveIndex)
}

class PlannerViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PlannerRepository(application.applicationContext)
    private val _uiState = MutableStateFlow(PlannerUiState())
    val uiState: StateFlow<PlannerUiState> = _uiState.asStateFlow()

    private var computeJob: Job? = null
    private var persistJob: Job? = null

    init {
        viewModelScope.launch {
            val accepted = repository.disclaimerAcceptedFlow.first()
            val lastJson = repository.lastStateJsonFlow.first()
            val restored = lastJson?.let { repository.deserializePlannerState(it) }
            _uiState.update { state ->
                val base = state.copy(
                    disclaimerAccepted = accepted,
                    disclaimerLoaded = true
                )
                if (restored != null && restored.second.isNotEmpty()) {
                    base.copy(input = restored.first, dives = restored.second, activeDiveIndex = 0)
                } else {
                    base
                }
            }
            recomputePlan(immediate = true)
        }

        viewModelScope.launch {
            repository.savedPlansFlow.collect { plans ->
                _uiState.update { it.copy(savedPlans = plans) }
            }
        }
    }

    fun acceptDisclaimer() {
        _uiState.update { it.copy(disclaimerAccepted = true) }
        viewModelScope.launch {
            repository.setDisclaimerAccepted(true)
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(msg: String) {
        _uiState.update { it.copy(snackbarMessage = msg) }
    }

    // ---- Multi-Dive Day Management ----

    fun selectDive(index: Int) {
        val count = _uiState.value.dives.size
        if (index in 0 until count && index != _uiState.value.activeDiveIndex) {
            _uiState.update { it.copy(activeDiveIndex = index) }
            recomputePlan(immediate = true)
        }
    }

    fun addRepetitiveDive() {
        _uiState.update { state ->
            if (state.dives.size >= 6) return@update state.copy(snackbarMessage = "Maximum 6 dives per day plan.")
            val nextNum = state.dives.size + 1
            val prevDepth = state.activeDive.levels.firstOrNull()?.depthM ?: 30.0
            val newDepth = (prevDepth - 6.0).coerceAtLeast(18.0)
            val newDive = MultiDiveEntry(
                id = "dive_${System.currentTimeMillis()}",
                label = "Dive $nextNum",
                surfaceIntervalBeforeMin = 90.0,
                levels = listOf(DiveLevel("lvl_${System.currentTimeMillis()}", newDepth, 25.0))
            )
            state.copy(
                dives = state.dives + newDive,
                activeDiveIndex = state.dives.size
            )
        }
        recomputePlan(immediate = true)
    }

    fun removeDive(index: Int) {
        _uiState.update { state ->
            if (state.dives.size <= 1 || index !in state.dives.indices) return@update state
            val updated = state.dives.filterIndexed { i, _ -> i != index }
                .mapIndexed { i, entry ->
                    entry.copy(
                        label = "Dive ${i + 1}",
                        surfaceIntervalBeforeMin = if (i == 0) 0.0 else entry.surfaceIntervalBeforeMin.coerceAtLeast(15.0)
                    )
                }
            state.copy(
                dives = updated,
                activeDiveIndex = state.activeDiveIndex.coerceAtMost(updated.lastIndex)
            )
        }
        recomputePlan(immediate = true)
    }

    fun updateSurfaceInterval(diveIndex: Int, minutes: Double) {
        _uiState.update { state ->
            val clamped = minutes.coerceIn(0.0, 1440.0)
            val updated = state.dives.mapIndexed { idx, entry ->
                if (idx == diveIndex && idx > 0) entry.copy(surfaceIntervalBeforeMin = clamped) else entry
            }
            state.copy(dives = updated)
        }
        recomputePlan()
    }

    // ---- Dive Levels (Multi-Level) ----

    fun addLevel() {
        _uiState.update { state ->
            val active = state.activeDive
            if (active.levels.size >= 6) return@update state
            val last = active.levels.lastOrNull()
            val nextDepth = ((last?.depthM ?: 30.0) - 9.0).coerceAtLeast(9.0)
            val newLevel = DiveLevel("lvl_${System.currentTimeMillis()}", nextDepth, 10.0, last?.gasId)
            val updatedDives = state.dives.mapIndexed { idx, d ->
                if (idx == state.activeDiveIndex) d.copy(levels = d.levels + newLevel) else d
            }
            state.copy(dives = updatedDives)
        }
        recomputePlan()
    }

    fun removeLevel(levelId: String) {
        _uiState.update { state ->
            val active = state.activeDive
            if (active.levels.size <= 1) return@update state
            val updatedDives = state.dives.mapIndexed { idx, d ->
                if (idx == state.activeDiveIndex) {
                    d.copy(levels = d.levels.filterNot { it.id == levelId })
                } else d
            }
            state.copy(dives = updatedDives)
        }
        recomputePlan()
    }

    fun updateLevel(levelId: String, depthM: Double? = null, timeMin: Double? = null, gasId: String? = null, updateGas: Boolean = false) {
        _uiState.update { state ->
            val updatedDives = state.dives.mapIndexed { idx, d ->
                if (idx == state.activeDiveIndex) {
                    d.copy(levels = d.levels.map { lvl ->
                        if (lvl.id == levelId) {
                            lvl.copy(
                                depthM = (depthM ?: lvl.depthM).coerceIn(3.0, 200.0),
                                timeMin = (timeMin ?: lvl.timeMin).coerceIn(1.0, 360.0),
                                gasId = if (updateGas) gasId else lvl.gasId
                            )
                        } else lvl
                    })
                } else d
            }
            state.copy(dives = updatedDives)
        }
        recomputePlan()
    }

    // ---- Gases & Best Mix ----

    fun addQuickGas(o2Pct: Int, hePct: Int, role: GasRole) {
        _uiState.update { state ->
            val sameGroupCount = state.input.gases.count {
                if (role == GasRole.DECO || role == GasRole.BAILOUT) {
                    it.role == GasRole.DECO || it.role == GasRole.BAILOUT
                } else {
                    it.role == GasRole.BOTTOM || it.role == GasRole.TRAVEL || it.role == GasRole.DILUENT
                }
            }
            val limit = if (role == GasRole.DECO || role == GasRole.BAILOUT) 6 else 10
            if (sameGroupCount >= limit) {
                return@update state.copy(snackbarMessage = "Maximum gas rows reached for this category.")
            }
            val newGas = Gas.fromPercent("g_${System.currentTimeMillis()}", o2Pct, hePct, role)
            val switchedToUser = state.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX
            state.copy(
                input = state.input.copy(
                    gases = state.input.gases + newGas,
                    general = state.input.general.copy(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS)
                ),
                snackbarMessage = if (switchedToUser) "Switched to User input GAS mode." else state.snackbarMessage
            )
        }
        recomputePlan()
    }

    fun updateGas(updatedGas: Gas) {
        _uiState.update { state ->
            val prev = state.input.gases.firstOrNull { it.id == updatedGas.id }
            val blendOrCountChanged = prev != null && (
                prev.o2Pct != updatedGas.o2Pct ||
                    prev.hePct != updatedGas.hePct ||
                    prev.enabled != updatedGas.enabled ||
                    prev.cylinder.count != updatedGas.cylinder.count
                )
            val newList = state.input.gases.map { if (it.id == updatedGas.id) updatedGas else it }
            val switchToUser = blendOrCountChanged && state.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX
            val nextMode = if (switchToUser) GasSelectionMode.USER_INPUT_GAS else state.input.general.gasSelectionMode
            state.copy(
                input = state.input.copy(
                    gases = newList,
                    general = state.input.general.copy(gasSelectionMode = nextMode)
                ),
                snackbarMessage = if (switchToUser) {
                    "Custom gas/tank count edited — switched to User input GAS mode."
                } else {
                    state.snackbarMessage
                }
            )
        }
        recomputePlan()
    }

    fun removeGas(gasId: String) {
        _uiState.update { state ->
            if (state.input.gases.size <= 1) return@update state
            val newList = state.input.gases.filterNot { it.id == gasId }
            val switchedToUser = state.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX
            state.copy(
                input = state.input.copy(
                    gases = newList,
                    general = state.input.general.copy(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS)
                ),
                snackbarMessage = if (switchedToUser) "Switched to User input GAS mode." else state.snackbarMessage
            )
        }
        recomputePlan()
    }

    fun setGasSelectionMode(mode: GasSelectionMode) {
        updateGeneral { it.copy(gasSelectionMode = mode) }
    }

    fun applyBestMixRecommendation() {
        val state = _uiState.value
        val maxDepth = state.activeDive.levels.maxOfOrNull { it.depthM } ?: 40.0
        val rec = GasCalculator.recommendBestMix(
            maxDepthM = maxDepth,
            general = state.input.general,
            dive = state.input.dive,
            existingGases = state.input.gases,
            alerts = state.input.alerts,
            levels = state.activeDive.levels,
            previousTissues = state.activeResult?.input?.previousTissues,
            surfaceIntervalMin = state.activeResult?.input?.surfaceIntervalMin ?: 0.0,
            consumption = state.input.consumption
        )
        val clearedLevels = state.dives.mapIndexed { idx, d ->
            if (idx == state.activeDiveIndex) {
                d.copy(levels = d.levels.map { it.copy(gasId = null) })
            } else d
        }
        val msg = if (rec.isRecreational) {
            "REC Dive (Within NDL): Best GAS Mix ${rec.bottomGas.displayName} (Air / Nitrox only)"
        } else {
            "Best GAS Mix active: 6m ${rec.deco6mO2?.displayName ?: "O2"} · 21m ${rec.deco21mEan50?.displayName ?: "EAN50"} · Travel ${rec.travelGasDisplay.displayName} · MOD ${rec.bottomGas.displayName}"
        }
        _uiState.update {
            it.copy(
                input = it.input.copy(
                    gases = rec.plannerGases,
                    general = it.input.general.copy(gasSelectionMode = GasSelectionMode.BEST_GAS_MIX)
                ),
                dives = clearedLevels,
                bestMixRec = rec,
                snackbarMessage = msg
            )
        }
        recomputePlan(immediate = true)
    }

    // ---- Settings Mutators ----

    fun updateGeneral(transform: (GeneralSettings) -> GeneralSettings) {
        _uiState.update { state ->
            var next = transform(state.input.general)
            // Enforce GF Low <= GF High
            val gfLow = next.gfLow.coerceIn(5, 100)
            val gfHigh = next.gfHigh.coerceIn(gfLow, 100)
            next = next.copy(gfLow = gfLow, gfHigh = gfHigh)
            state.copy(input = state.input.copy(general = next))
        }
        recomputePlan()
    }

    fun updateDiveSettings(transform: (DiveSettings) -> DiveSettings) {
        _uiState.update { state ->
            state.copy(input = state.input.copy(dive = transform(state.input.dive)))
        }
        recomputePlan()
    }

    fun updateGasBreaks(transform: (GasBreakSettings) -> GasBreakSettings) {
        _uiState.update { state ->
            state.copy(input = state.input.copy(gasBreaks = transform(state.input.gasBreaks)))
        }
        recomputePlan()
    }

    fun updateConsumption(transform: (ConsumptionSettings) -> ConsumptionSettings) {
        _uiState.update { state ->
            state.copy(input = state.input.copy(consumption = transform(state.input.consumption)))
        }
        recomputePlan()
    }

    fun updateCcr(transform: (CcrSettings) -> CcrSettings) {
        _uiState.update { state ->
            state.copy(input = state.input.copy(ccr = transform(state.input.ccr)))
        }
        recomputePlan()
    }

    fun updateAlerts(transform: (AlertSettings) -> AlertSettings) {
        _uiState.update { state ->
            state.copy(input = state.input.copy(alerts = transform(state.input.alerts)))
        }
        recomputePlan()
    }

    // ---- Saved Plans ----

    fun saveCurrentPlan(name: String) {
        val state = _uiState.value
        val activeRes = state.activeResult
        val maxD = activeRes?.maxDepthM?.roundToInt() ?: 0
        val rt = activeRes?.let { UnitsAndPressure.formatRuntimeMmSs(it.totalRuntimeMin) } ?: "0:00"
        val summary = "${state.dives.size} dive(s) · Max ${maxD}m · RT $rt · ${state.input.general.decoModel.shortLabel} GF ${state.input.general.gfLow}/${state.input.general.gfHigh}"
        viewModelScope.launch {
            repository.saveNamedPlan(name, summary, state.input, state.dives)
            _uiState.update { it.copy(snackbarMessage = "Saved plan \"$name\"") }
        }
    }

    fun loadSavedPlan(plan: SavedPlanSummary) {
        val restored = repository.deserializePlannerState(plan.payloadJson) ?: return
        _uiState.update {
            it.copy(
                input = restored.first,
                dives = restored.second,
                activeDiveIndex = 0,
                snackbarMessage = "Loaded \"${plan.name}\""
            )
        }
        recomputePlan(immediate = true)
    }

    fun deleteSavedPlan(id: String) {
        viewModelScope.launch {
            repository.deleteSavedPlan(id)
        }
    }

    // ---- Core Engine Recomputation (2-Phase: Instant Primary Plan + Background Contingencies) ----

    private fun recomputePlan(immediate: Boolean = false) {
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            try {
                if (!immediate) delay(160)
                _uiState.update { it.copy(isComputing = true) }
                val snapshot = _uiState.value

                // Phase 1: Compute primary multi-dive plan fast (without blocking on contingencies)
                val (dayRes, bestRec, syncedGases) = withContext(Dispatchers.Default) {
                    val isBestMode = snapshot.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX
                    val effectiveDives = if (isBestMode) {
                        snapshot.dives.map { d -> d.copy(levels = d.levels.map { it.copy(gasId = null) }) }
                    } else {
                        snapshot.dives
                    }
                    val res = DivePlannerEngine.planMultiDiveDay(
                        baseInput = snapshot.input,
                        dives = effectiveDives,
                        includeContingenciesForIndex = -1
                    )
                    val activeDiveRes = res.diveResults.getOrNull(snapshot.activeDiveIndex)
                    val maxDepth = snapshot.activeDive.levels.maxOfOrNull { it.depthM } ?: 40.0
                    val rec = GasCalculator.recommendBestMix(
                        maxDepthM = maxDepth,
                        general = snapshot.input.general,
                        dive = snapshot.input.dive,
                        existingGases = snapshot.input.gases,
                        alerts = snapshot.input.alerts,
                        levels = snapshot.activeDive.levels,
                        previousTissues = activeDiveRes?.input?.previousTissues,
                        surfaceIntervalMin = activeDiveRes?.input?.surfaceIntervalMin ?: 0.0,
                        consumption = snapshot.input.consumption
                    )
                    val finalGases = if (isBestMode && activeDiveRes != null) {
                        activeDiveRes.input.gases
                    } else if (isBestMode) {
                        rec.plannerGases
                    } else {
                        snapshot.input.gases
                    }
                    val finalRec = if (isBestMode && activeDiveRes != null) {
                        val byId = finalGases.associateBy { it.id }
                        fun syncGas(g: Gas): Gas = byId[g.id] ?: g
                        rec.copy(
                            bottomGas = syncGas(rec.bottomGas),
                            travelGas = rec.travelGas?.let { syncGas(it) },
                            travelGasDisplay = syncGas(rec.travelGasDisplay),
                            deco6mO2 = rec.deco6mO2?.let { syncGas(it) },
                            deco21mEan50 = rec.deco21mEan50?.let { syncGas(it) },
                            deepDecoGas = rec.deepDecoGas?.let { syncGas(it) },
                            decoGases = rec.decoGases.map { syncGas(it) },
                            plannerGases = finalGases
                        )
                    } else {
                        rec
                    }
                    Triple(res, finalRec, finalGases)
                }

                _uiState.update {
                    it.copy(
                        input = if (it.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX) {
                            it.input.copy(gases = syncedGases)
                        } else {
                            it.input
                        },
                        dayResult = dayRes,
                        bestMixRec = bestRec,
                        isComputing = false
                    )
                }

                val latest = _uiState.value
                schedulePersist(latest.input, latest.dives)

                // Phase 2: Compute contingencies asynchronously for active dive without blocking UI
                val activeIdx = snapshot.activeDiveIndex
                val activeRes = dayRes.diveResults.getOrNull(activeIdx)
                if (activeRes != null && activeRes.abortedReason == null) {
                    val (contingencies, ccrBailout) = withContext(Dispatchers.Default) {
                        val cList = DivePlannerEngine.generateContingencies(activeRes.input)
                        val bailout = if (activeRes.input.general.planningType == PlanningType.CCR) {
                            DivePlannerEngine.generateCcrBailout(activeRes.input)
                        } else null
                        cList to bailout
                    }
                    _uiState.update { cur ->
                        val curDay = cur.dayResult ?: return@update cur
                        if (activeIdx !in curDay.diveResults.indices) return@update cur
                        val updatedList = curDay.diveResults.mapIndexed { idx, dr ->
                            if (idx == activeIdx) dr.copy(contingencies = contingencies, ccrBailout = ccrBailout) else dr
                        }
                        cur.copy(dayResult = curDay.copy(diveResults = updatedList))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isComputing = false,
                        snackbarMessage = "Plan calculation adjusted: ${e.message ?: "check dive inputs"}"
                    )
                }
            }
        }
    }

    private fun schedulePersist(input: DivePlanInput, dives: List<MultiDiveEntry>) {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(600)
            repository.persistLastState(input, dives)
        }
    }
}
