import Foundation

/// Physical constants, gas densities, salinity presets and NOAA CNS limits (mirrors Constants.kt).
public enum Constants {
    public static let P_SURFACE_SEA: Double = 1.01325        // bar
    public static let G: Double = 9.80665                    // m/s^2
    public static let P_WATER_VAPOUR: Double = 0.0627        // bar, Buhlmann value at 37 C
    public static let FN2_AIR: Double = 0.7902
    public static let FO2_AIR: Double = 0.2095

    public static let DENSITY_O2: Double = 1.429
    public static let DENSITY_N2: Double = 1.2506
    public static let DENSITY_HE: Double = 0.1786

    public static let SALINITY_FRESH: Double = 1000.0
    public static let SALINITY_EN13319: Double = 1020.0
    public static let SALINITY_SALT: Double = 1030.0
    public static let SALINITY_RED_SEA: Double = 1030.0

    public static let CNS_DECAY_HALF_TIME_MIN: Double = 90.0
    public static let MAX_DECO_MINUTES: Int = 3000

    public static let NOAA_CNS_TABLE: [(Double, Double)] = [
        (0.50, Double.infinity),
        (0.60, 720.0), (0.70, 570.0), (0.80, 450.0), (0.90, 360.0), (1.00, 300.0),
        (1.10, 240.0), (1.20, 210.0), (1.30, 180.0), (1.40, 150.0), (1.50, 120.0),
        (1.60, 45.0), (1.65, 15.0)
    ]

    public static let REPEX_DAILY_LIMITS: [(Int, Int)] = [(1, 850), (2, 700), (3, 620), (4, 525), (5, 460), (6, 420)]

    public static let STANDARD_BOTTOM_MIXES: [(Int, Int)] = [
        (21, 0), (32, 0), (21, 35), (18, 45), (15, 55), (12, 65), (10, 70), (8, 80)
    ]
}
