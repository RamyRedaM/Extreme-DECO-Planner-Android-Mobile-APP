import SwiftUI
import DecoEngine

@main
struct ExtremeDecoPlannerApp: App {
    @StateObject private var store = PlannerStore()
    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .tint(Theme.accent)
                .preferredColorScheme(store.input.general.darkTheme ? .dark : nil)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store: PlannerStore
    @State private var tab = 1

    var body: some View {
        TabView(selection: $tab) {
            SettingsView().tabItem { Label("Settings", systemImage: "slider.horizontal.3") }.tag(0)
            PlannerView().tabItem { Label("Planner", systemImage: "list.bullet.rectangle") }.tag(1)
            GasesView().tabItem { Label("Gases", systemImage: "drop.halffull") }.tag(2)
            ChartsView().tabItem { Label("Charts", systemImage: "chart.xyaxis.line") }.tag(3)
        }
        .overlay(alignment: .top) {
            if let m = store.message {
                Text(m).font(.footnote).padding(10)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                    .padding(.top, 8).padding(.horizontal)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .task(id: m) {
                        try? await Task.sleep(nanoseconds: 2_800_000_000)
                        withAnimation { store.message = nil }
                    }
            }
        }
        .animation(.easeInOut, value: store.message)
        .fullScreenCover(isPresented: Binding(get: { !store.disclaimerAccepted }, set: { _ in })) {
            DisclaimerView { store.acceptDisclaimer() }
        }
    }
}

struct DisclaimerView: View {
    let onAccept: () -> Void
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Image("img_hero_planner").resizable().scaledToFill().frame(height: 180).clipped().clipShape(RoundedRectangle(cornerRadius: 16))
                Text("Extreme DECO Planner").font(.largeTitle.bold())
                Text("Safety disclaimer").font(.title3.bold()).foregroundStyle(Theme.color(.critical))
                Text("""
                This app is a planning aid for trained technical divers. Decompression models are mathematical approximations of human physiology and cannot guarantee freedom from decompression sickness, oxygen toxicity or other injury.

                • Never dive a plan you do not understand or have not checked against a second, independent tool.
                • Always follow your training agency limits and carry a dive computer and backup tables.
                • VPM-B here is an approximation and is marked experimental.
                • The authors accept no liability for the use of this software.
                """).font(.callout)
                Button(action: onAccept) {
                    Text("I understand and accept").bold().frame(maxWidth: .infinity).padding()
                }
                .buttonStyle(.borderedProminent)
            }.padding(20)
        }
    }
}
