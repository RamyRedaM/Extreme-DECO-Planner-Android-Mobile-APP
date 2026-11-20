package com.example.engine

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pressure, altitude, salinity, CCR/OC inspired gas pressure, and SI <-> Imperial unit conversions
 * per Section 4 & Section 8.6 of specification.
 */
object UnitsAndPressure {
    private const val M_TO_FT = 3.280839895
    private const val L_TO_CUFT = 0.0353146667
    private const val BAR_TO_PSI = 14.503773773

    fun surfacePressureBar(elevationM: Int): Double {
        val h = elevationM.coerceIn(0, 5000).toDouble()
        return Constants.P_SURFACE_SEA * (1.0 - 2.25577e-5 * h).pow(5.25588)
    }

    fun barPerMetre(salinityKgM3: Double): Double {
        return salinityKgM3 * Constants.G / 100000.0
    }

    fun buildEnvironment(general: GeneralSettings): Environment {
        val salinity = general.effectiveSalinity.coerceIn(950.0, 1250.0)
        return Environment(
            pSurfaceBar = surfacePressureBar(general.elevationM),
            barPerMetre = barPerMetre(salinity),
            salinityKgM3 = salinity,
            elevationM = general.elevationM
        )
    }

    fun ambientPressure(depthM: Double, env: Environment): Double {
        return env.pSurfaceBar + depthM.coerceAtLeast(0.0) * env.barPerMetre
    }

    fun depthFromPressure(pAmbBar: Double, env: Environment): Double {
        return (pAmbBar - env.pSurfaceBar) / env.barPerMetre
    }

    /**
     * Inspired alveolar partial pressures (N2, He, O2) at ambient pressure.
     * Supports Open Circuit and CCR diluent/setpoint model (Section 4.3 & Section 8.6).
     */
    data class InspiredPressures(
        val ppO2: Double,
        val pInspiredN2: Double,
        val pInspiredHe: Double
    )

    fun inspiredPressuresOC(pAmbBar: Double, gas: Gas): InspiredPressures {
        val dry = (pAmbBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0)
        return InspiredPressures(
            ppO2 = pAmbBar * gas.o2,
            pInspiredN2 = dry * gas.n2,
            pInspiredHe = dry * gas.he
        )
    }

    fun inspiredPressuresCCR(pAmbBar: Double, diluent: Gas, setpoint: Double): InspiredPressures {
        val maxPossible = (pAmbBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0)
        var ppO2 = setpoint.coerceAtMost(maxPossible)
        val dilPpO2 = pAmbBar * diluent.o2
        if (dilPpO2 > setpoint) {
            ppO2 = dilPpO2.coerceAtMost(maxPossible)
        }
        val pInertTotal = (pAmbBar - Constants.P_WATER_VAPOUR - ppO2).coerceAtLeast(0.0)
        val inertFracSum = diluent.n2 + diluent.he
        val pInN2 = if (inertFracSum > 1e-9) pInertTotal * (diluent.n2 / inertFracSum) else 0.0
        val pInHe = if (inertFracSum > 1e-9) pInertTotal * (diluent.he / inertFracSum) else 0.0
        return InspiredPressures(
            ppO2 = ppO2,
            pInspiredN2 = pInN2,
            pInspiredHe = pInHe
        )
    }

    // ---- Display unit conversions ----
    fun depthDisplay(metres: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) metres * M_TO_FT else metres

    fun depthFromDisplay(value: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) value / M_TO_FT else value

    fun volumeDisplay(litres: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) litres * L_TO_CUFT else litres

    fun pressureDisplay(bar: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) bar * BAR_TO_PSI else bar

    fun tempDisplay(celsius: Double, units: UnitSystem): Double =
        if (units == UnitSystem.IMPERIAL) celsius * 9.0 / 5.0 + 32.0 else celsius

    fun formatDepth(metres: Double, units: UnitSystem, decimals: Int = 0): String {
        val v = depthDisplay(metres, units)
        return if (decimals == 0) {
            "${v.roundToInt()} ${units.depthUnit}"
        } else {
            String.format(Locale.US, "%.${decimals}f %s", v, units.depthUnit)
        }
    }

    fun formatVolume(litres: Double, units: UnitSystem): String {
        val v = volumeDisplay(litres, units)
        return if (units == UnitSystem.IMPERIAL) {
            String.format(Locale.US, "%.1f %s", v, units.volUnit)
        } else {
            "${v.roundToInt()} ${units.volUnit}"
        }
    }

    fun formatPressure(bar: Double, units: UnitSystem): String {
        val v = pressureDisplay(bar, units)
        return "${v.roundToInt()} ${units.pressureUnit}"
    }

    fun formatRuntimeMmSs(minutes: Double): String {
        val totalSeconds = (minutes * 60.0).roundToInt().coerceAtLeast(0)
        val mm = totalSeconds / 60
        val ss = totalSeconds % 60
        return String.format(Locale.US, "%d:%02d", mm, ss)
    }

    fun ceilToIncrement(depthM: Double, incrementM: Int): Int {
        if (depthM <= 0.0) return 0
        val inc = incrementM.coerceAtLeast(1)
        return (ceil(depthM / inc) * inc).toInt()
    }

    fun floorToInt(value: Double): Int = floor(value).toInt()
}
