# Extreme DECO Planner for iPhone (iOS)

Native SwiftUI version of Extreme DECO Planner v1.27.2. It is a separate code base from the Android app, kept in step by parity tests.

| | Android | iOS |
|---|---|---|
| Language / UI | Kotlin, Jetpack Compose | Swift, SwiftUI, Swift Charts |
| Engine | `app/src/main/java/com/example/engine/*.kt` | `ios/DecoEngine/Sources/DecoEngine/*.swift` |
| Tests | `app/src/test/...` (JUnit, Robolectric) | `ios/DecoEngine/Tests/...` (XCTest) |
| Minimum OS | Android 7.0 | iOS 16 |
| Build | `.github/workflows/build-apk.yml` | `.github/workflows/build-ios.yml` (macOS runner) |

## Layout

```
ios/
  DecoEngine/                 Swift package: the calculation engine (no UI)
    Sources/DecoEngine/       line-by-line port of the Kotlin engine
    Tests/DecoEngineTests/
      EngineTests.swift       ports of the Android unit tests (golden schedules, audit fixes, SI calculator...)
      ParityTests.swift       must reproduce the Kotlin engine output for fixed scenarios
  ExtremeDecoPlanner/         the iPhone/iPad app
    project.yml               XcodeGen project definition (version, bundle id)
    Sources/                  SwiftUI screens: Settings, Planner, Gases, Charts, SI calculator, export
    Resources/Assets.xcassets app icon and banner images (same art as Android)
```

## Features (same as Android)

- Bühlmann ZHL-16A/B/C with Gradient Factors, VPM-B (experimental), compartment 1b option
- Open Circuit and CCR (setpoints, diluent, bailout plan)
- Multi-level dives and multi-dive days with residual tissue loading and CNS decay
- Best GAS Mix (including REC mode within NDL) or User input GAS
- Gas consumption with Thirds, Quarters, Half, Rock-Bottom and Lost-Gas reserves
- Safety alerts in four levels: Critical (red), High (orange), Medium (yellow), Low (gray)
- Contingencies (deeper, longer, lost deco gas) and CCR bailout
- Surface Interval Calculator (ZHL-16C) with next-dive NDL
- Charts: profile and ceiling, partial pressures, GF99 and Surface GF, compartment saturation, tissue tension, gas use
- Saved plans, PDF dive slate and CSV export through the iOS share sheet

## Keeping the two code bases in step

The engine is the part that must never drift. The rule: **every engine change is made in Kotlin and Swift in the same pull request.**

1. Change the Kotlin engine and its tests as usual.
2. Make the same change in the matching Swift file. File names and function names are the same on both sides (`GasCalculator.kt` ↔ `GasCalculator.swift`, `computeGasConsumption` ↔ `computeGasConsumption`).
3. Port any new Kotlin test into `EngineTests.swift`.
4. If the change is meant to alter results, rerun the Android build. `ParityFixturesTest` prints `DIAG PARITY|...` lines as notices on the run. Copy them into `kotlinReference` in `ParityTests.swift`.
5. Push. The iOS workflow runs `swift test` and fails if any scenario differs from the Kotlin output (runtime ±0.05 min, CNS ±0.1 %, OTU ±0.2, gas ±2 L, schedules and alerts must match exactly).

UI changes do not need parity tests, but should be mirrored so both apps offer the same inputs. Bump the version in both places: `versionName` in `app/build.gradle.kts` and `MARKETING_VERSION` in `ios/ExtremeDecoPlanner/project.yml`.

## Building

On a Mac with Xcode 15 or later:

```bash
brew install xcodegen
cd ios/DecoEngine && swift test            # engine and parity tests
cd ../ExtremeDecoPlanner && xcodegen generate
open ExtremeDecoPlanner.xcodeproj           # pick your team under Signing, then Run
```

Without a Mac, run **Actions → Build iOS → Run workflow**. It tests the engine, builds for the simulator and produces an unsigned `Extreme-DECO-Planner-v1.27.2-unsigned.ipa`. With `publish` ticked, the .ipa is attached to the v1.27.2 release.

## Installing on an iPhone

Apple only lets signed apps run on an iPhone. There are two routes:

| Route | Cost | How | Limits |
|---|---|---|---|
| Sideload the unsigned .ipa | Free | Install [Sideloadly](https://sideloadly.io) or [AltStore](https://altstore.io) on a PC or Mac, connect the iPhone, drop in the .ipa and sign with your own Apple ID. On the iPhone: Settings → General → VPN & Device Management → trust your Apple ID, and turn on Developer Mode (Settings → Privacy & Security). | Must be re-signed every 7 days with a free Apple ID, 3 apps max per device |
| TestFlight / App Store | Apple Developer Program, US$99 per year | Set your team in `project.yml`, archive in Xcode, upload to App Store Connect, invite testers by email or public link | Testers install from the TestFlight app; builds last 90 days |

For sharing with many friends, TestFlight is the practical route.

## Disclaimer

Planning aid only. Cross-check every plan with independent tools and dive within your training and certification limits.
