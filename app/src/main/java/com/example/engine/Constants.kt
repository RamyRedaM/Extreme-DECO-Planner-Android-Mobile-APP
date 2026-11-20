package com.example.engine

/**
 * Authoritative physical constants, gas densities, salinity presets, and NOAA CNS limits
 * per TEC DECO PLANNER specification (Sections 4 and 9).
 */
object Constants {
    const val P_SURFACE_SEA: Double = 1.01325        // bar
    const val G: Double = 9.80665                    // m/s^2
    const val P_WATER_VAPOUR: Double = 0.0627        // bar, Buhlmann value at 37 C
    const val FN2_AIR: Double = 0.7902               // surface saturation nitrogen fraction
    const val FO2_AIR: Double = 0.2095

    // Gas densities at 1 atm, 0 C (g/L)
    const val DENSITY_O2: Double = 1.429
    const val DENSITY_N2: Double = 1.2506
    const val DENSITY_HE: Double = 0.1786

    // Salinity densities (kg/m^3)
    const val SALINITY_FRESH: Double = 1000.0
    const val SALINITY_EN13319: Double = 1020.0
    const val SALINITY_SALT: Double = 1030.0
    const val SALINITY_RED_SEA: Double = 1030.0

    // CNS surface half-time decay (minutes)
    const val CNS_DECAY_HALF_TIME_MIN: Double = 90.0

    // Max total deco safeguard (minutes)
    const val MAX_DECO_MINUTES: Int = 3000

    // NOAA single-exposure CNS limits: (ppO2 bar -> limit minutes)
    val NOAA_CNS_TABLE: List<Pair<Double, Double>> = listOf(
        0.50 to Double.POSITIVE_INFINITY,
        0.60 to 720.0,
        0.70 to 570.0,
        0.80 to 450.0,
        0.90 to 360.0,
        1.00 to 300.0,
        1.10 to 240.0,
        1.20 to 210.0,
        1.30 to 180.0,
        1.40 to 150.0,
        1.50 to 120.0,
        1.60 to 45.0,
        1.65 to 15.0
    )

    // REPEX daily OTU guidance (day count -> max OTU/day)
    val REPEX_DAILY_LIMITS: List<Pair<Int, Int>> = listOf(
        1 to 850,
        2 to 700,
        3 to 620,
        4 to 525,
        5 to 460,
        6 to 420
    )

    // Standard technical gas library (O2%, He%)
    val STANDARD_BOTTOM_MIXES: List<Pair<Int, Int>> = listOf(
        21 to 0,   // Air
        32 to 0,   // EAN32
        21 to 35,  // TX 21/35
        18 to 45,  // TX 18/45
        15 to 55,  // TX 15/55
        12 to 65,  // TX 12/65
        10 to 70,  // TX 10/70
        8 to 80    // TX 8/80
    )
}
