import Foundation

public struct CompartmentCoeff: Hashable, Codable {
    public let number: Int
    public let label: String
    public let n2HalfTime: Double
    public let heHalfTime: Double
    public let aN2: Double
    public let bN2: Double
    public let aHe: Double
    public let bHe: Double
}

/// Bühlmann ZHL-16A/B/C coefficient tables (mirrors Coefficients.kt).
public enum Coefficients {
    public static let N2_HALF_TIMES: [Double] = [4.0, 8.0, 12.5, 18.5, 27.0, 38.3, 54.3, 77.0, 109.0, 146.0, 187.0, 239.0, 305.0, 390.0, 498.0, 635.0]
    public static let N2_HALF_TIME_1B = 5.0
    public static let HE_HALF_TIMES: [Double] = [1.51, 3.02, 4.72, 6.99, 10.21, 14.48, 20.53, 29.11, 41.20, 55.19, 70.69, 90.34, 115.29, 147.42, 188.24, 240.03]
    public static let HE_HALF_TIME_1B = 1.88
    public static let A_N2_16A: [Double] = [1.2599, 1.0000, 0.8618, 0.7562, 0.6667, 0.5933, 0.5282, 0.4701, 0.4187, 0.3798, 0.3497, 0.3223, 0.2971, 0.2737, 0.2523, 0.2327]
    public static let A_N2_16B: [Double] = [1.2599, 1.0000, 0.8618, 0.7562, 0.6667, 0.5600, 0.4947, 0.4500, 0.4187, 0.3798, 0.3497, 0.3223, 0.2850, 0.2737, 0.2523, 0.2327]
    public static let A_N2_16C: [Double] = [1.2599, 1.0000, 0.8618, 0.7562, 0.6200, 0.5043, 0.4410, 0.4000, 0.3750, 0.3500, 0.3295, 0.3065, 0.2835, 0.2610, 0.2480, 0.2327]
    public static let A_N2_1B = 1.1696
    public static let B_N2: [Double] = [0.5050, 0.6514, 0.7222, 0.7825, 0.8126, 0.8434, 0.8693, 0.8910, 0.9092, 0.9222, 0.9319, 0.9403, 0.9477, 0.9544, 0.9602, 0.9653]
    public static let B_N2_1B = 0.5578
    public static let A_HE: [Double] = [1.7424, 1.3830, 1.1919, 1.0458, 0.9220, 0.8205, 0.7305, 0.6502, 0.5950, 0.5545, 0.5333, 0.5189, 0.5181, 0.5176, 0.5172, 0.5119]
    public static let A_HE_1B = 1.6189
    public static let B_HE: [Double] = [0.4245, 0.5747, 0.6527, 0.7223, 0.7582, 0.7957, 0.8279, 0.8553, 0.8757, 0.8903, 0.8997, 0.9073, 0.9122, 0.9171, 0.9217, 0.9267]
    public static let B_HE_1B = 0.4770

    // VPM-B experimental parameters
    public static let VPM_CRITICAL_RADIUS_N2_UM = 0.55
    public static let VPM_CRITICAL_RADIUS_HE_UM = 0.45
    public static let VPM_SURFACE_TENSION = 0.0179
    public static let VPM_SKIN_COMPRESSION = 0.257
    public static let VPM_CRIT_VOLUME_LAMBDA = 7500.0
    public static let VPM_REGENERATION_MIN = 20160.0

    public static func tableFor(_ model: DecoModel, use1b: Bool = false) -> [CompartmentCoeff] {
        let aN2Arr: [Double]
        switch model {
        case .zhl16A: aN2Arr = A_N2_16A
        case .zhl16B: aN2Arr = A_N2_16B
        case .zhl16C, .vpmB: aN2Arr = A_N2_16C
        }
        return (0..<16).map { idx in
            if idx == 0 && use1b {
                return CompartmentCoeff(number: 1, label: "1b", n2HalfTime: N2_HALF_TIME_1B, heHalfTime: HE_HALF_TIME_1B,
                                        aN2: A_N2_1B, bN2: B_N2_1B, aHe: A_HE_1B, bHe: B_HE_1B)
            }
            return CompartmentCoeff(number: idx + 1, label: "C\(idx + 1)", n2HalfTime: N2_HALF_TIMES[idx], heHalfTime: HE_HALF_TIMES[idx],
                                    aN2: aN2Arr[idx], bN2: B_N2[idx], aHe: A_HE[idx], bHe: B_HE[idx])
        }
    }
}
