import SwiftUI
import DecoEngine

enum Theme {
    static let accent = Color(red: 0.008, green: 0.533, blue: 0.820)
    static let navy = Color(red: 0.04, green: 0.11, blue: 0.20)
    static let cyan = Color(red: 0.0, green: 0.80, blue: 0.90)
    static let ok = Color(red: 0.16, green: 0.70, blue: 0.38)

    static func color(_ level: AlertLevel) -> Color {
        switch level {
        case .critical: return Color(red: 0.86, green: 0.15, blue: 0.15)
        case .high: return Color(red: 0.96, green: 0.50, blue: 0.08)
        case .medium: return Color(red: 0.95, green: 0.77, blue: 0.06)
        case .low: return Color(red: 0.55, green: 0.58, blue: 0.62)
        }
    }

    static func gasColor(_ gas: Gas) -> Color {
        if gas.o2Pct >= 99 { return Color(red: 0.13, green: 0.70, blue: 0.30) }
        if gas.hePct > 0 { return Color(red: 0.62, green: 0.35, blue: 0.85) }
        if gas.o2Pct > 21 { return Color(red: 0.98, green: 0.65, blue: 0.10) }
        return Color(red: 0.20, green: 0.55, blue: 0.95)
    }
}

extension Double {
    func f(_ d: Int) -> String { fmt(self, d) }
}
