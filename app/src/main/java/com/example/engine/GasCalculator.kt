package com.example.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gas metrics (MOD, MinDepth, END, EAD, PPN2, Density), Best Mix recommender,
 * Travel/Deco gas recommendations, and ascent gas switching with ICD validation (Section 7).
 */
object GasCalculator {

    fun modExactM(gas: Gas, ppO2Max: Double, env: Environment): Double {
        if (gas.o2 <= 0.0) return 0.0
        val pMax = ppO2Max / gas.o2
        return UnitsAndPressure.depthFromPressure(pMax, env).coerceAtLeast(0.0)
    }

    fun modDisplayM(gas: Gas, ppO2Max: Double, env: Environment): Double {
        return floor(modExactM(gas, ppO2Max, env))
    }

    fun effectiveSwitchModM(gas: Gas, ppO2Deco: Double, env: Environment): Double {
        gas.modOverrideM?.let { return it }
        val exact = modExactM(gas, ppO2Deco, env)
        // Standard technical diving convention: 100% O2 is switched at 6 m stop when ppO2Deco >= 1.58
        if (gas.o2 >= 0.99 && ppO2Deco >= 1.58 && exact >= 5.5) {
            return 6.0
        }
        return floor(exact)
    }

    fun minDepthM(gas: Gas, ppO2Min: Double, env: Environment): Double {
        if (gas.o2 <= 0.0) return Double.POSITIVE_INFINITY
        val pMin = ppO2Min / gas.o2
        val d = UnitsAndPressure.depthFromPressure(pMin, env)
        return if (d <= 0.0) 0.0 else ceil(d)
    }

    fun endM(gas: Gas, depthM: Double, env: Environment, o2Narcotic: Boolean): Double {
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val pNarc = if (o2Narcotic) {
            (gas.n2 + gas.o2) * pAmb
        } else {
            gas.n2 * pAmb / Constants.FN2_AIR
        }
        return UnitsAndPressure.depthFromPressure(pNarc, env).coerceAtLeast(0.0)
    }

    fun eadM(gas: Gas, depthM: Double, env: Environment): Double {
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val pAirEq = gas.n2 * pAmb / Constants.FN2_AIR
        return UnitsAndPressure.depthFromPressure(pAirEq, env).coerceAtLeast(0.0)
    }

    fun densityGL(gas: Gas, depthM: Double, env: Environment): Double {
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val d1Atm = gas.o2 * Constants.DENSITY_O2 +
            gas.n2 * Constants.DENSITY_N2 +
            gas.he * Constants.DENSITY_HE
        return d1Atm * pAmb / Constants.P_SURFACE_SEA
    }

    fun gasMetrics(
        gas: Gas,
        depthM: Double,
        env: Environment,
        dive: DiveSettings
    ): GasMetrics {
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val ppO2Limit = if (gas.role == GasRole.DECO || gas.role == GasRole.BAILOUT) {
            dive.ppO2Deco
        } else {
            dive.ppO2Bottom
        }
        val exactMod = modExactM(gas, ppO2Limit, env)
        return GasMetrics(
            gas = gas,
            depthM = depthM,
            ppO2 = gas.o2 * pAmb,
            ppN2 = gas.n2 * pAmb,
            ppHe = gas.he * pAmb,
            endM = endM(gas, depthM, env, dive.o2Narcotic),
            eadM = eadM(gas, depthM, env),
            densityGL = densityGL(gas, depthM, env),
            modM = gas.modOverrideM ?: floor(exactMod),
            modExactM = exactMod,
            minDepthM = minDepthM(gas, dive.ppO2Min, env)
        )
    }

    private fun resolveCylinder(
        slotId: String,
        role: GasRole,
        defaultCount: Int,
        existingGases: List<Gas>
    ): CylinderConfig {
        val exact = existingGases.firstOrNull { it.id == slotId }?.cylinder
        if (exact != null) return exact
        val byRole = when (role) {
            GasRole.BOTTOM, GasRole.DILUENT -> existingGases.firstOrNull { it.role == GasRole.BOTTOM || it.role == GasRole.DILUENT }?.cylinder
            GasRole.TRAVEL -> existingGases.firstOrNull { it.role == GasRole.TRAVEL }?.cylinder
            else -> null
        }
        return byRole ?: CylinderConfig(waterVolumeL = 12.0, fillPressureBar = 200.0, count = defaultCount)
    }

    /**
     * Computes the mathematically optimal breathing gas at a given target depth & ppO2 limit,
     * simultaneously satisfying ppO2, END, ppN2, and Gas Density constraints.
     */
    private fun computeOptimalMixAtDepth(
        id: String,
        targetDepthM: Double,
        ppO2Limit: Double,
        ppN2Limit: Double,
        role: GasRole,
        general: GeneralSettings,
        dive: DiveSettings,
        env: Environment,
        minO2: Double,
        maxO2: Double,
        cylinder: CylinderConfig,
        modOverrideM: Double? = null
    ): Gas {
        val depth = targetDepthM.coerceAtLeast(3.0)
        val pAmb = UnitsAndPressure.ambientPressure(depth, env)

        var fO2 = floor((ppO2Limit / pAmb) * 100.0) / 100.0
        fO2 = fO2.coerceIn(minO2, maxO2)

        val pEndMax = UnitsAndPressure.ambientPressure(dive.maxEndM, env)
        val fHeForEnd = if (dive.o2Narcotic) {
            val fNarcMax = pEndMax / pAmb
            max(0.0, 1.0 - fNarcMax)
        } else {
            val fN2Max = Constants.FN2_AIR * pEndMax / pAmb
            max(0.0, 1.0 - fO2 - fN2Max)
        }
        val fN2MaxPp = ppN2Limit / pAmb
        val fHeForPpN2 = max(0.0, 1.0 - fO2 - fN2MaxPp)

        var fHe = max(fHeForEnd, fHeForPpN2)
        val step = if (general.standardMixesOnly) 5.0 else 1.0
        fHe = ceil((fHe * 100.0) / step) * step / 100.0
        if (fO2 + fHe > 1.0) {
            fHe = (1.0 - fO2).coerceAtLeast(0.0)
        }

        var candidate = Gas(
            id = id,
            o2 = (fO2 * 100.0).roundToInt() / 100.0,
            he = (fHe * 100.0).roundToInt() / 100.0,
            role = role,
            modOverrideM = modOverrideM,
            cylinder = cylinder
        )
        while (densityGL(candidate, depth, env) > dive.maxGasDensityGL && candidate.n2 >= 0.01) {
            val nextHe = (candidate.he + 0.01).coerceAtMost(1.0 - candidate.o2)
            candidate = candidate.copy(he = (nextHe * 100.0).roundToInt() / 100.0)
        }
        return practicalHelium(candidate, depth, ppN2Limit, dive, env)
    }

    /**
     * Depth at which a gas card's END, density and ppO2 are shown:
     * deco/bailout gases at their switch depth, travel gases at the deepest depth they are actually
     * breathed (their MOD for PPO2 Bottom, capped by the dive's max depth), bottom/diluent gases at max depth.
     */
    fun displayEvalDepthM(gas: Gas, maxDepthM: Double, dive: DiveSettings, env: Environment): Double = when (gas.role) {
        GasRole.DECO, GasRole.BAILOUT -> effectiveSwitchModM(gas, dive.ppO2Deco, env)
        GasRole.TRAVEL -> min(maxDepthM, gas.modOverrideM ?: modDisplayM(gas, dive.ppO2Bottom, env)).coerceAtLeast(0.0)
        else -> maxDepthM
    }

    /**
     * Gas used for the "next dive" NDL in the Surface Interval Calculator: the next dive's own bottom gas
     * when it is planned, otherwise EAN32 if it is within PPO2 Bottom at that depth, else Air.
     */
    fun defaultNextDiveGas(plannedNextDiveGases: List<Gas>?, nextDepthM: Double, dive: DiveSettings, env: Environment): Gas {
        plannedNextDiveGases
            ?.firstOrNull { it.enabled && (it.role == GasRole.BOTTOM || it.role == GasRole.DILUENT) }
            ?.let { return it }
        val ean32 = Gas.fromPercent("si_ean32", 32, 0, GasRole.BOTTOM)
        return if (modExactM(ean32, dive.ppO2Bottom, env) >= nextDepthM) ean32
        else Gas.fromPercent("si_air", 21, 0, GasRole.BOTTOM)
    }

    /** Smallest helium fraction worth blending; below this a trimix becomes plain nitrox. */
    const val MIN_PRACTICAL_HE = 0.10

    /**
     * Avoids impractical fills such as "TX 34/1": helium between 0% and 10% is dropped (plain nitrox)
     * when the nitrox still meets the END, ppN2 and density limits at this depth; otherwise it is
     * raised to 10%, which only lowers END and density.
     */
    fun practicalHelium(gas: Gas, depthM: Double, ppN2Limit: Double, dive: DiveSettings, env: Environment): Gas {
        if (gas.he <= 0.0 || gas.he >= MIN_PRACTICAL_HE - 1e-9) return gas
        val nitrox = gas.copy(he = 0.0)
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val nitroxOk = endM(nitrox, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 1e-6 &&
            nitrox.n2 * pAmb <= ppN2Limit + 1e-6 &&
            densityGL(nitrox, depthM, env) <= dive.maxGasDensityGL + 1e-6
        if (nitroxOk) return nitrox
        val he = MIN_PRACTICAL_HE.coerceAtMost(1.0 - gas.o2)
        return gas.copy(he = (he * 100.0).roundToInt() / 100.0)
    }

    /**
     * Computes the best Recreational (REC) breathing gas (Air or Nitrox EAN only — never Trimix or DECO).
     * Defaults to EAN32 (32% O2) whenever within the bottom ppO2 limit; for deeper REC depths (< 40m)
     * exceeding EAN32's MOD, automatically steps down to the richest safe Nitrox (EAN) or Air (21% O2).
     */
    private fun computeRecreationalMix(
        maxDepthM: Double,
        general: GeneralSettings,
        dive: DiveSettings,
        env: Environment,
        existingGases: List<Gas>
    ): Gas {
        val depth = maxDepthM.coerceAtLeast(3.0)
        val pAmb = UnitsAndPressure.ambientPressure(depth, env)
        val recCyl = resolveCylinder("best_bottom", GasRole.BOTTOM, 1, existingGases)

        // Default REC Best Gas Mix: EAN32 if within bottom ppO2 limit
        val maxSafeO2Pct = floor((dive.ppO2Bottom / pAmb) * 100.0).toInt().coerceIn(21, 32)
        val chosenO2Pct = if (maxSafeO2Pct >= 32) {
            32
        } else if (general.standardMixesOnly) {
            listOf(32, 28, 21).firstOrNull { it <= maxSafeO2Pct } ?: 21
        } else {
            maxSafeO2Pct
        }
        return Gas.fromPercent(
            id = "best_bottom",
            o2Pct = chosenO2Pct,
            hePct = 0,
            role = GasRole.BOTTOM,
            cylinder = recCyl
        )
    }

    /**
     * Algorithmically computes the optimal gas mix for EVERY stage of the dive:
     * - If Open-Circuit, maxDepth < 40m, and the dive profile is within NDL -> REC Recreational Dive:
     *   proposes ONLY Air / Nitrox (EAN32 default) with NO DECO gases and NO Trimix.
     * - Otherwise -> Technical (TEC) Dive Kit:
     *   1) MOD Bottom Gas Mix (for maxDepthM, enforcing ppO2, END, ppN2, and Gas Density)
     *   2) Travel Gas Mix (normoxic descent & mid-depth bridge computed for the dive's depth & bottom gas)
     *   3) Deep DECO Stage (when depth > 54m with Helium, enforcing ICD + END + Density)
     *   4) Mid DECO Bottle @ 21m (EAN50 at 1.60 bar, dynamically adapted if ppO2Deco < 1.56)
     *   5) Shallow DECO Bottle @ 6m (100% O2 at 1.60 bar, dynamically adapted if ppO2Deco < 1.58)
     * All cylinders default to 12L unless customized by the user.
     */
    fun recommendBestMix(
        maxDepthM: Double,
        general: GeneralSettings,
        dive: DiveSettings,
        existingGases: List<Gas> = emptyList(),
        alerts: AlertSettings = AlertSettings(),
        levels: List<DiveLevel> = emptyList(),
        previousTissues: TissueState? = null,
        surfaceIntervalMin: Double = 0.0,
        consumption: ConsumptionSettings = ConsumptionSettings()
    ): MixRecommendation {
        val env = UnitsAndPressure.buildEnvironment(general)
        val notes = mutableListOf<String>()
        val validLevels = levels.filter { it.depthM > 0.0 && it.timeMin > 0.0 }
        val depth = (validLevels.maxOfOrNull { it.depthM } ?: maxDepthM).coerceAtLeast(3.0)

        // REC Recreational Dive Check: under 40m AND within NDL (no decompression stops)
        if (general.planningType == PlanningType.OPEN_CIRCUIT && depth < 40.0 && validLevels.isNotEmpty()) {
            val recGas = computeRecreationalMix(depth, general, dive, env, existingGases)
            val probeInput = DivePlanInput(
                general = general.copy(gasSelectionMode = GasSelectionMode.USER_INPUT_GAS),
                dive = dive,
                consumption = consumption,
                alerts = alerts,
                gases = listOf(recGas),
                levels = validLevels.map { it.copy(gasId = null) },
                previousTissues = previousTissues,
                surfaceIntervalMin = surfaceIntervalMin
            )
            val probe = DivePlannerEngine.planDive(
                input = probeInput,
                includeContingencies = false,
                recordTimeline = false
            )
            val isWithinNdl = probe.abortedReason == null &&
                probe.firstStopM == 0 &&
                probe.totalDecoMin <= 1e-6

            if (isWithinNdl) {
                val recMetrics = gasMetrics(recGas, depth, env, dive)
                val recNotes = listOf(
                    "REC Recreational Dive (< 40m, within NDL): ${recGas.displayName} selected (Air / Nitrox only — no DECO bottles or Trimix)."
                )
                return MixRecommendation(
                    bottomGas = recGas,
                    travelGas = null,
                    travelGasDisplay = recGas,
                    travelRangeNote = "0 m → ${depth.roundToInt()} m (single REC tank)",
                    deco6mO2 = null,
                    deco21mEan50 = null,
                    deepDecoGas = null,
                    decoGases = emptyList(),
                    plannerGases = listOf(recGas),
                    modM = recMetrics.modM,
                    minDepthM = recMetrics.minDepthM,
                    endAtMaxDepthM = recMetrics.endM,
                    densityAtMaxDepthGL = recMetrics.densityGL,
                    ppO2AtMaxDepth = recMetrics.ppO2,
                    notes = recNotes,
                    isRecreational = true
                )
            }
        }

        val bottomRole = if (general.planningType == PlanningType.CCR) GasRole.DILUENT else GasRole.BOTTOM
        val bottomCyl = resolveCylinder("best_bottom", bottomRole, 2, existingGases)

        // 1. Algorithmic Bottom Gas (MOD Mix)
        var bottomGas = computeOptimalMixAtDepth(
            id = "best_bottom",
            targetDepthM = depth,
            ppO2Limit = dive.ppO2Bottom,
            ppN2Limit = dive.ppN2Max,
            role = bottomRole,
            general = general,
            dive = dive,
            env = env,
            minO2 = 0.08,
            maxO2 = 0.40,
            cylinder = bottomCyl
        )
        if (densityGL(bottomGas, depth, env) > dive.maxGasDensityGL) {
            notes += "Gas density exceeds ${dive.maxGasDensityGL} g/L at ${depth.roundToInt()} m; reduce depth or increase helium."
        }
        if (general.standardMixesOnly) {
            val snapped = Constants.STANDARD_BOTTOM_MIXES
                .map { (o2p, hep) ->
                    Gas.fromPercent("best_bottom", o2p, hep, bottomRole, cylinder = bottomCyl)
                }
                .filter { g ->
                    g.o2 <= bottomGas.o2 + 0.005 &&
                        endM(g, depth, env, dive.o2Narcotic) <= dive.maxEndM + 1.0 &&
                        densityGL(g, depth, env) <= dive.maxGasDensityGL + 0.1
                }
                .maxByOrNull { it.o2 }
            if (snapped != null) {
                bottomGas = snapped
                notes += "Snapped bottom MOD mix to standard fill ${bottomGas.displayName}."
            }
        }

        // 2. Algorithmic DECO Bottle @ 6m (100% O2 when ppO2Deco >= 1.58, else optimal O2% at 6m)
        val deco6mCyl = resolveCylinder("best_deco_6m_o2", GasRole.DECO, 1, existingGases)
        val deco6mO2 = if (dive.ppO2Deco >= 1.58) {
            Gas.fromPercent(
                id = "best_deco_6m_o2",
                o2Pct = 100,
                hePct = 0,
                role = GasRole.DECO,
                modOverrideM = 6.0,
                cylinder = deco6mCyl
            )
        } else {
            val p6 = UnitsAndPressure.ambientPressure(6.0, env)
            val optO2 = floor((dive.ppO2Deco / p6) * 100.0).toInt().coerceIn(50, 100)
            Gas.fromPercent(
                id = "best_deco_6m_o2",
                o2Pct = optO2,
                hePct = 0,
                role = GasRole.DECO,
                modOverrideM = 6.0,
                cylinder = deco6mCyl
            )
        }

        // 3. Algorithmic DECO Bottle @ 21m (EAN50 when ppO2Deco >= 1.56, else optimal O2% at 21m)
        val deco21mCyl = resolveCylinder("best_deco_21m_ean50", GasRole.DECO, 1, existingGases)
        val deco21mEan50 = if (dive.ppO2Deco >= 1.56) {
            Gas.fromPercent(
                id = "best_deco_21m_ean50",
                o2Pct = 50,
                hePct = 0,
                role = GasRole.DECO,
                modOverrideM = 21.0,
                cylinder = deco21mCyl
            )
        } else {
            val p21 = UnitsAndPressure.ambientPressure(21.0, env)
            val optO2 = floor((dive.ppO2Deco / p21) * 100.0).toInt().coerceIn(32, 50)
            Gas.fromPercent(
                id = "best_deco_21m_ean50",
                o2Pct = optO2,
                hePct = 0,
                role = GasRole.DECO,
                modOverrideM = 21.0,
                cylinder = deco21mCyl
            )
        }

        // 4. Algorithmic Deep DECO Stage (> 54m with Helium bottom mix, with ICD protection)
        val deepDecoCyl = resolveCylinder("best_deco_deep", GasRole.DECO, 1, existingGases)
        val deepDecoGas: Gas? = if (depth > 54.0 && bottomGas.hePct >= 20) {
            val deepSwitchM = (floor(minOf(39.0, depth - 15.0) / 3.0) * 3.0).coerceIn(27.0, 39.0)
            var deepCand = computeOptimalMixAtDepth(
                id = "best_deco_deep",
                targetDepthM = deepSwitchM,
                ppO2Limit = dive.ppO2Deco,
                ppN2Limit = dive.ppN2MaxDeco,
                role = GasRole.DECO,
                general = general,
                dive = dive,
                env = env,
                minO2 = (bottomGas.o2 + 0.08).coerceAtLeast(0.30),
                maxO2 = 0.45,
                cylinder = deepDecoCyl,
                modOverrideM = deepSwitchM
            )
            // Enforce ICD safety against bottomGas at deepSwitchM
            var icdGuard = 0
            while (icdGuard++ < 40 && deepCand.n2 >= 0.02) {
                val icd = evaluateIcd(bottomGas, deepCand, deepSwitchM, env, alerts)
                if (!icd.violatesDeltaN2 && !icd.violatesRuleOfFifths) break
                val nextHe = (deepCand.he + 0.01).coerceAtMost(1.0 - deepCand.o2)
                deepCand = deepCand.copy(he = (nextHe * 100.0).roundToInt() / 100.0)
            }
            deepCand
        } else {
            null
        }

        // 5. Algorithmic Travel Gas Mix (computed dynamically for descent & mid-depth bridge)
        val travelCyl = resolveCylinder("best_travel", GasRole.TRAVEL, 1, existingGases)
        val minDepth = minDepthM(bottomGas, dive.ppO2Min, env)
        val hypoxicTravelGas: Gas?
        val travelGasForDepth: Gas
        val travelRangeNote: String

        if (minDepth > 0.0) {
            // Hypoxic bottom mix -> compute normoxic travel gas covering 0m -> switchDepth with overlap
            val targetSwitchM = (floor(max(minDepth + 9.0, minOf(54.0, depth * 0.62)) / 3.0) * 3.0)
                .coerceIn(max(18.0, minDepth + 3.0), (depth - 6.0).coerceAtLeast(18.0))
            var computedTravel = computeOptimalMixAtDepth(
                id = "best_travel",
                targetDepthM = targetSwitchM,
                ppO2Limit = dive.ppO2Bottom,
                ppN2Limit = dive.ppN2Max,
                role = GasRole.TRAVEL,
                general = general,
                dive = dive,
                env = env,
                minO2 = 0.19, // strictly normoxic at surface
                maxO2 = 0.36,
                cylinder = travelCyl
            )
            // Keep travel gas ICD-friendly when switching on ascent/descent
            if (bottomGas.hePct >= 30 && computedTravel.hePct < 15) {
                var guard = 0
                while (guard++ < 35 && computedTravel.n2 >= 0.05) {
                    val icd = evaluateIcd(bottomGas, computedTravel, targetSwitchM, env, alerts)
                    if (!icd.violatesDeltaN2) break
                    val nextHe = (computedTravel.he + 0.01).coerceAtMost(1.0 - computedTravel.o2)
                    computedTravel = computedTravel.copy(he = (nextHe * 100.0).roundToInt() / 100.0)
                }
            }
            hypoxicTravelGas = computedTravel
            travelGasForDepth = computedTravel
            val swDepth = minOf(depth - 3.0, modDisplayM(computedTravel, dive.ppO2Bottom, env)).roundToInt()
            travelRangeNote = "0 m → ${swDepth} m (hypoxic bottom switch)"
            notes += "Bottom MOD mix ${bottomGas.displayName} is hypoxic above ${minDepth.roundToInt()} m — Travel ${computedTravel.displayName} active 0→${swDepth} m."
        } else if (depth > 33.0) {
            // Normoxic technical dive > 33m -> compute optimal descent/travel stage gas
            val targetSwitchM = if (depth > 54.0) {
                (floor((depth * 0.65).coerceIn(33.0, 48.0) / 3.0) * 3.0)
            } else {
                (floor(minOf(30.0, depth - 9.0).coerceAtLeast(21.0) / 3.0) * 3.0)
            }
            val computedTravel = computeOptimalMixAtDepth(
                id = "best_travel",
                targetDepthM = targetSwitchM,
                ppO2Limit = dive.ppO2Bottom,
                ppN2Limit = dive.ppN2Max,
                role = GasRole.TRAVEL,
                general = general,
                dive = dive,
                env = env,
                minO2 = max(0.21, bottomGas.o2 + 0.04).coerceAtMost(0.36),
                maxO2 = 0.36,
                cylinder = travelCyl
            )
            hypoxicTravelGas = null
            travelGasForDepth = computedTravel
            val swDepth = minOf(depth - 3.0, modDisplayM(computedTravel, dive.ppO2Bottom, env)).roundToInt()
            travelRangeNote = "0 m → ${swDepth} m (optimal descent travel)"
        } else {
            // Shallow normoxic dive <= 33m: Bottom MOD mix is already optimal & breathable from surface
            hypoxicTravelGas = null
            travelGasForDepth = bottomGas.copy(id = "best_travel_same", role = GasRole.TRAVEL, cylinder = travelCyl)
            travelRangeNote = "0 m → ${depth.roundToInt()} m (bottom mix breathable from surface)"
        }

        val decoGases = buildList {
            deepDecoGas?.let { add(it) }
            if (depth > 21.0 || bottomGas.o2Pct < deco21mEan50.o2Pct - 5) add(deco21mEan50)
            if (depth > 6.0) add(deco6mO2)
        }

        val deepNote = deepDecoGas?.let { " · Deep Deco ${it.displayName} @ ${it.modOverrideM?.roundToInt() ?: 36}m" } ?: ""
        notes += "Best GAS Kit (12L default): 6m ${deco6mO2.displayName} · 21m ${deco21mEan50.displayName}$deepNote · Travel ${travelGasForDepth.displayName} · MOD ${bottomGas.displayName}."

        val plannerGases = buildList {
            if (hypoxicTravelGas != null || (depth > 33.0 && travelGasForDepth.o2 > bottomGas.o2 + 0.02)) {
                add(travelGasForDepth)
            }
            add(bottomGas)
            addAll(decoGases)
        }

        val metrics = gasMetrics(bottomGas, depth, env, dive)
        return MixRecommendation(
            bottomGas = bottomGas,
            travelGas = hypoxicTravelGas,
            travelGasDisplay = travelGasForDepth,
            travelRangeNote = travelRangeNote,
            deco6mO2 = deco6mO2,
            deco21mEan50 = deco21mEan50,
            deepDecoGas = deepDecoGas,
            decoGases = decoGases,
            plannerGases = plannerGases,
            modM = metrics.modM,
            minDepthM = metrics.minDepthM,
            endAtMaxDepthM = metrics.endM,
            densityAtMaxDepthGL = metrics.densityGL,
            ppO2AtMaxDepth = metrics.ppO2,
            notes = notes
        )
    }

    fun recommendTravelGas(bottomGas: Gas, env: Environment, dive: DiveSettings): Gas? {
        val bottomMin = minDepthM(bottomGas, dive.ppO2Min, env)
        if (bottomMin <= 0.0) return null
        val candidates = listOf(
            Gas.fromPercent("rec_travel_ean32", 32, 0, GasRole.TRAVEL, cylinder = CylinderConfig(12.0, 200.0, 1)),
            Gas.fromPercent("rec_travel_air", 21, 0, GasRole.TRAVEL, cylinder = CylinderConfig(12.0, 200.0, 1)),
            Gas.fromPercent("rec_travel_2135", 21, 35, GasRole.TRAVEL, cylinder = CylinderConfig(12.0, 200.0, 1)),
            Gas.fromPercent("rec_travel_1845", 18, 45, GasRole.TRAVEL, cylinder = CylinderConfig(12.0, 200.0, 1))
        )
        return candidates
            .filter { g ->
                minDepthM(g, dive.ppO2Min, env) == 0.0 &&
                    modDisplayM(g, dive.ppO2Bottom, env) >= bottomMin + 3.0 &&
                    endM(g, bottomMin + 3.0, env, dive.o2Narcotic) <= dive.maxEndM
            }
            .maxByOrNull { it.o2 }
            ?: Gas.fromPercent("rec_travel_2135", 21, 35, GasRole.TRAVEL, cylinder = CylinderConfig(12.0, 200.0, 1))
    }

    fun recommendDecoGases(
        maxDepthM: Double,
        bottomGas: Gas,
        env: Environment,
        dive: DiveSettings
    ): List<Gas> {
        val list = mutableListOf<Gas>()
        if (maxDepthM > 90.0) {
            list += Gas.fromPercent("rec_deco_2135", 21, 35, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
        }
        if (maxDepthM > 60.0) {
            val mid = if (bottomGas.hePct >= 30) {
                Gas.fromPercent("rec_deco_3525", 35, 25, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
            } else {
                Gas.fromPercent("rec_deco_32", 32, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
            }
            list += mid
        }
        if (maxDepthM >= 30.0) {
            list += Gas.fromPercent("rec_deco_ean50", 50, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
        }
        if (maxDepthM >= 20.0) {
            list += Gas.fromPercent("rec_deco_o2", 100, 0, GasRole.DECO, cylinder = CylinderConfig(12.0, 200.0, 1))
        }
        return list.filter { it.o2 > bottomGas.o2 + 0.05 }.take(4)
    }

    /**
     * Checks Isobaric Counter-Diffusion between gas A and gas B at depth (Section 9.3).
     */
    data class IcdEvaluation(
        val deltaPpN2: Double,
        val deltaPpHe: Double,
        val violatesDeltaN2: Boolean,
        val violatesRuleOfFifths: Boolean
    )

    fun evaluateIcd(
        fromGas: Gas,
        toGas: Gas,
        depthM: Double,
        env: Environment,
        alerts: AlertSettings
    ): IcdEvaluation {
        val pAmb = UnitsAndPressure.ambientPressure(depthM, env)
        val deltaPpN2 = (toGas.n2 - fromGas.n2) * pAmb
        val deltaPpHe = (fromGas.he - toGas.he) * pAmb
        val deltaFN2 = toGas.n2 - fromGas.n2
        val deltaFHe = fromGas.he - toGas.he
        val violatesDeltaN2 = deltaPpN2 > alerts.icdDeltaPpN2Max + 1e-6
        val violatesFifths = alerts.icdRuleOfFifths &&
            deltaFHe > 0.005 &&
            deltaFN2 > (deltaFHe / 5.0) + 1e-6
        return IcdEvaluation(
            deltaPpN2 = deltaPpN2,
            deltaPpHe = deltaPpHe,
            violatesDeltaN2 = violatesDeltaN2,
            violatesRuleOfFifths = violatesFifths
        )
    }

    /**
     * Selects the best working/bottom/travel gas at a given multi-level depth (respecting ppO2Bottom & END).
     */
    fun bestWorkingGasAt(
        depthM: Double,
        fallbackGas: Gas,
        availableGases: List<Gas>,
        env: Environment,
        dive: DiveSettings
    ): Gas {
        val working = availableGases.filter {
            it.enabled && (it.role == GasRole.BOTTOM || it.role == GasRole.TRAVEL || it.role == GasRole.DILUENT)
        }
        val safe = working.filter { g ->
            val minD = minDepthM(g, dive.ppO2Min, env)
            val maxD = modDisplayM(g, dive.ppO2Bottom, env)
            depthM >= minD - 1e-6 &&
                depthM <= maxD + 1e-6 &&
                endM(g, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 3.0
        }
        return safe.maxWithOrNull(compareBy<Gas> { it.o2 }.thenBy { it.he }) ?: fallbackGas
    }

    /**
     * Selects the richest safe breathable gas available at depth during ascent & decompression (Section 7.5).
     * Prefers DECO bottles at their optimal switch depths and respects END/MOD limits for travel gases.
     */
    fun bestGasAt(
        depthM: Double,
        currentGas: Gas,
        availableGases: List<Gas>,
        env: Environment,
        dive: DiveSettings
    ): Gas {
        val enabled = availableGases.filter { it.enabled }
        val currentMin = minDepthM(currentGas, dive.ppO2Min, env)
        val currentSafe = depthM >= currentMin - 1e-6

        val candidates = enabled.filter { g ->
            val minD = minDepthM(g, dive.ppO2Min, env)
            val maxD = if (g.role == GasRole.DECO || g.role == GasRole.BAILOUT) {
                effectiveSwitchModM(g, dive.ppO2Deco, env)
            } else {
                // Travel/Bottom gas used on ascent must respect its working MOD (or deco MOD if escaping hypoxia)
                effectiveSwitchModM(g, if (!currentSafe) dive.ppO2Deco else dive.ppO2Bottom, env)
            }
            val depthOk = depthM >= minD - 1e-6 && depthM <= maxD + 1e-6
            val endOk = g.role == GasRole.DECO || g.role == GasRole.BAILOUT || !currentSafe ||
                endM(g, depthM, env, dive.o2Narcotic) <= dive.maxEndM + 3.0
            depthOk && endOk
        }
        if (candidates.isEmpty()) return currentGas

        val best = candidates.maxWithOrNull(
            compareBy<Gas> { it.o2 }
                .thenBy { if (it.role == GasRole.DECO || it.role == GasRole.BAILOUT) 1 else 0 }
                .thenBy { it.he }
        ) ?: currentGas

        return if (!currentSafe || best.o2 > currentGas.o2 + 1e-6) best else currentGas
    }
}
