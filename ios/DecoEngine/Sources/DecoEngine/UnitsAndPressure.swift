import Foundation

/// Pressure, altitude, salinity, inspired gas and display unit helpers (mirrors UnitsAndPressure.kt).
public enum UnitsAndPressure {
    static let M_TO_FT = 3.280839895
    static let L_TO_CUFT = 0.0353146667
    static let BAR_TO_PSI = 14.503773773

    public static func surfacePressureBar(_ elevationM: Int) -> Double {
        let h = Double(elevationM.coerceIn(0, 5000))
        return Constants.P_SURFACE_SEA * pow(1.0 - 2.25577e-5 * h, 5.25588)
    }

    public static func barPerMetre(_ salinityKgM3: Double) -> Double { salinityKgM3 * Constants.G / 100000.0 }

    public static func buildEnvironment(_ general: GeneralSettings) -> Environment {
        let salinity = general.effectiveSalinity.coerceIn(950.0, 1250.0)
        return Environment(pSurfaceBar: surfacePressureBar(general.elevationM), barPerMetre: barPerMetre(salinity),
                           salinityKgM3: salinity, elevationM: general.elevationM)
    }

    public static func ambientPressure(_ depthM: Double, _ env: Environment) -> Double {
        env.pSurfaceBar + depthM.coerceAtLeast(0.0) * env.barPerMetre
    }

    public static func depthFromPressure(_ pAmbBar: Double, _ env: Environment) -> Double {
        (pAmbBar - env.pSurfaceBar) / env.barPerMetre
    }

    public struct InspiredPressures: Hashable {
        public let ppO2: Double
        public let pInspiredN2: Double
        public let pInspiredHe: Double
    }

    public static func inspiredPressuresOC(_ pAmbBar: Double, _ gas: Gas) -> InspiredPressures {
        let dry = (pAmbBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0)
        return InspiredPressures(ppO2: pAmbBar * gas.o2, pInspiredN2: dry * gas.n2, pInspiredHe: dry * gas.he)
    }

    public static func inspiredPressuresCCR(_ pAmbBar: Double, _ diluent: Gas, _ setpoint: Double) -> InspiredPressures {
        let maxPossible = (pAmbBar - Constants.P_WATER_VAPOUR).coerceAtLeast(0.0)
        var ppO2 = setpoint.coerceAtMost(maxPossible)
        let dilPpO2 = pAmbBar * diluent.o2
        if dilPpO2 > setpoint { ppO2 = dilPpO2.coerceAtMost(maxPossible) }
        let pInertTotal = (pAmbBar - Constants.P_WATER_VAPOUR - ppO2).coerceAtLeast(0.0)
        let inertFracSum = diluent.n2 + diluent.he
        let pInN2 = inertFracSum > 1e-9 ? pInertTotal * (diluent.n2 / inertFracSum) : 0.0
        let pInHe = inertFracSum > 1e-9 ? pInertTotal * (diluent.he / inertFracSum) : 0.0
        return InspiredPressures(ppO2: ppO2, pInspiredN2: pInN2, pInspiredHe: pInHe)
    }

    // ---- Display unit conversions ----
    public static func depthDisplay(_ metres: Double, _ units: UnitSystem) -> Double { units == .imperial ? metres * M_TO_FT : metres }
    public static func depthFromDisplay(_ value: Double, _ units: UnitSystem) -> Double { units == .imperial ? value / M_TO_FT : value }
    public static func volumeDisplay(_ litres: Double, _ units: UnitSystem) -> Double { units == .imperial ? litres * L_TO_CUFT : litres }
    public static func pressureDisplay(_ bar: Double, _ units: UnitSystem) -> Double { units == .imperial ? bar * BAR_TO_PSI : bar }
    public static func tempDisplay(_ celsius: Double, _ units: UnitSystem) -> Double { units == .imperial ? celsius * 9.0 / 5.0 + 32.0 : celsius }

    public static func formatDepth(_ metres: Double, _ units: UnitSystem, decimals: Int = 0) -> String {
        let v = depthDisplay(metres, units)
        return decimals == 0 ? "\(kRound(v)) \(units.depthUnit)" : "\(fmt(v, decimals)) \(units.depthUnit)"
    }

    public static func formatVolume(_ litres: Double, _ units: UnitSystem) -> String {
        let v = volumeDisplay(litres, units)
        return units == .imperial ? "\(fmt(v, 1)) \(units.volUnit)" : "\(kRound(v)) \(units.volUnit)"
    }

    public static func formatPressure(_ bar: Double, _ units: UnitSystem) -> String {
        "\(kRound(pressureDisplay(bar, units))) \(units.pressureUnit)"
    }

    public static func formatRuntimeMmSs(_ minutes: Double) -> String {
        let totalSeconds = kRound(minutes * 60.0).coerceAtLeast(0)
        return String(format: "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }

    public static func ceilToIncrement(_ depthM: Double, _ incrementM: Int) -> Int {
        if depthM <= 0.0 { return 0 }
        let inc = Double(incrementM.coerceAtLeast(1))
        return Int((depthM / inc).rounded(.up) * inc)
    }

    public static func floorToInt(_ value: Double) -> Int { Int(value.rounded(.down)) }
}
