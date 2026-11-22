import SwiftUI
import DecoEngine

/// A titled card section used on every tab.
struct Card<Content: View>: View {
    let title: String
    var systemImage: String? = nil
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 6) {
                if let s = systemImage { Image(systemName: s).foregroundStyle(Theme.accent) }
                Text(title).font(.headline)
            }
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14).fill(Color(.secondarySystemGroupedBackground)))
    }
}

/// Hero banner with one of the bundled images.
struct HeroBanner: View {
    let image: String
    let title: String
    let subtitle: String
    var body: some View {
        ZStack(alignment: .bottomLeading) {
            Image(image).resizable().scaledToFill().frame(height: 120).clipped()
            LinearGradient(colors: [.clear, .black.opacity(0.75)], startPoint: .top, endPoint: .bottom)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title3.bold()).foregroundStyle(.white)
                Text(subtitle).font(.caption).foregroundStyle(.white.opacity(0.85))
            }.padding(12)
        }
        .frame(height: 120)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// Numeric field with a label and unit, committing on change.
struct NumberField: View {
    let label: String
    let value: Double
    var unit: String = ""
    var decimals: Int = 0
    let onCommit: (Double) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool

    var body: some View {
        HStack {
            Text(label).font(.subheadline)
            Spacer()
            TextField("", text: $text)
                .keyboardType(.decimalPad)
                .multilineTextAlignment(.trailing)
                .frame(width: 80)
                .textFieldStyle(.roundedBorder)
                .focused($focused)
                .onSubmit(commit)
                .onChange(of: focused) { f in if !f { commit() } }
            if !unit.isEmpty { Text(unit).font(.caption).foregroundStyle(.secondary).frame(minWidth: 28, alignment: .leading) }
        }
        .onAppear { text = value.f(decimals) }
        .onChange(of: value) { v in if !focused { text = v.f(decimals) } }
    }

    private func commit() {
        let cleaned = text.replacingOccurrences(of: ",", with: ".")
        if let v = Double(cleaned) { onCommit(v) } else { text = value.f(decimals) }
    }
}

/// Integer stepper row.
struct StepperRow: View {
    let label: String
    let value: Int
    let range: ClosedRange<Int>
    var step: Int = 1
    var suffix: String = ""
    let onChange: (Int) -> Void
    var body: some View {
        Stepper(value: Binding(get: { value }, set: onChange), in: range, step: step) {
            HStack { Text(label).font(.subheadline); Spacer(); Text("\(value)\(suffix)").monospacedDigit().foregroundStyle(.secondary) }
        }
    }
}

/// Coloured status pill (green when there are no alerts, otherwise the highest alert colour).
struct StatusPill: View {
    let result: DivePlanResult?
    var body: some View {
        let level = result?.highestAlertLevel
        let color = level.map(Theme.color) ?? Theme.ok
        let text: String = {
            guard let r = result else { return "Computing…" }
            if r.abortedReason != nil { return "ABORTED" }
            guard let l = level else { return "PLAN OK" }
            let n = r.alerts.filter { $0.level == l }.count
            return "\(n) \(l.label)"
        }()
        Text(text)
            .font(.caption.bold())
            .padding(.horizontal, 10).padding(.vertical, 4)
            .background(Capsule().fill(color.opacity(0.18)))
            .overlay(Capsule().stroke(color, lineWidth: 1))
            .foregroundStyle(color)
    }
}

struct StatTile: View {
    let label: String
    let value: String
    var color: Color = .primary
    var body: some View {
        VStack(spacing: 2) {
            Text(value).font(.headline.monospacedDigit()).foregroundStyle(color).lineLimit(1).minimumScaleFactor(0.6)
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color(.tertiarySystemGroupedBackground)))
    }
}

struct AlertRow: View {
    let alert: PlanAlert
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            RoundedRectangle(cornerRadius: 2).fill(Theme.color(alert.level)).frame(width: 4)
            VStack(alignment: .leading, spacing: 3) {
                HStack {
                    Text(alert.level.label).font(.caption2.bold())
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Capsule().fill(Theme.color(alert.level)))
                        .foregroundStyle(alert.level == .medium ? .black : .white)
                    Text(alert.code.title).font(.subheadline.bold())
                }
                Text(alert.message).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(.vertical, 4)
    }
}
