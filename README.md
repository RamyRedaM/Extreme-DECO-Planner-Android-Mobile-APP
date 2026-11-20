<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="Extreme DECO Planner icon" width="120">
</p>

<h1 align="center">Extreme DECO Planner</h1>

<p align="center">
  <strong>A technical scuba diving decompression planner for Android.</strong><br>
  Bühlmann ZHL-16A / B / C with Gradient Factors, Best Gas Mix selection, CCR with bailout, multi-dive days, interactive charts and PDF / CSV export. Everything runs on the phone, offline.
</p>

<p align="center">
  <a href="https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP/releases/download/v1.27.2/Extreme-DECO-Planner-v1.27.2.apk"><strong>⬇ Download the APK (v1.27.2)</strong></a>
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%207.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white">
  <img alt="Version" src="https://img.shields.io/badge/version-1.27.2-0891B2">
  <img alt="Status" src="https://img.shields.io/badge/status-beta-FACC15">
  <a href="https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP/actions/workflows/build-apk.yml"><img alt="Build APK" src="https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP/actions/workflows/build-apk.yml/badge.svg"></a>
</p>

---

> [!WARNING]
> **There is always a risk of decompression sickness (DCS), oxygen toxicity and other injury on any dive, even when you follow a plan produced by this app.** Every Experienced TEC diver knows very well that all the dive algos are trial/error algos and there is Mathematical model on earth that can truely and 100% model the human body and how it will react to different depths with different gases , so Extreme DECO Planner is for training and planning only. It does not replace technical diver training, certification, a dive computer or your own judgement. Dive within your training limits, cross-check every plan with a second independent planner, and carry backup tables. You use this software entirely at your own risk.

---

## Table of Contents

- [Overview](#overview)
- [Download](#download)
- [Features](#features)
- [App Screens](#app-screens)
- [How to Plan a Dive](#how-to-plan-a-dive)
- [Decompression Models](#decompression-models)
- [Best Gas Mix](#best-gas-mix)
- [Safety Checks](#safety-checks)
- [Build from Source](#build-from-source)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Testing and Validation](#testing-and-validation)
- [Default Settings](#default-settings)
- [Known Limitations](#known-limitations)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [References](#references)
- [Contact](#contact)
- [License](#license)

---

## Overview

Extreme DECO Planner helps technical divers plan open circuit and closed circuit (CCR) decompression dives on an Android phone. You set the depth, bottom time, gases and your limits, and the app:

1. Recommends the **best bottom, travel and deco gases** for the depth, with cylinder sizes.
2. Builds a full **decompression schedule** with the model you choose.
3. Reports **runtime, stops, CNS %, OTU, END, gas density and gas volumes**.
4. Draws **charts** of the profile, partial pressures, tissue loading and gas use.
5. Plans **multi-dive days**, carrying residual nitrogen and helium from dive to dive.
6. Generates **contingency plans** and a **CCR bailout** plan.
7. Exports the plan as a **PDF dive slate** or **CSV**, ready to share.

No account, no server and no internet connection are needed. Your settings and saved plans stay on your device.

---

## Download

**Latest test build: [Extreme-DECO-Planner-v1.27.2.apk](https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP/releases/download/v1.27.2/Extreme-DECO-Planner-v1.27.2.apk)** (about 18 MB, Android 7.0 or later)

All versions and release notes are on the [Releases page](https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP/releases). A Google Play release will follow later.

### How to install

1. Open the download link on your Android phone and download the APK.
2. Tap the downloaded file. When Android asks, allow **Install unknown apps** for your browser or file manager, then tap **Install**.
3. If Google Play Protect warns that the app is unknown, tap **Install anyway**. This is normal for apps installed outside Google Play.

> [!NOTE]
> This is a **test build** signed with a temporary debug key. A later build signed with a different key cannot install over it as an update: uninstall the old version first. Your saved plans are removed when you uninstall.

You can also [build the app from source](#build-from-source).

---

## Features

### Planning
- **Open Circuit** and **CCR** planning (setpoint-based), with automatic CCR bailout to open circuit
- **Multi-level dives** (add levels) and **multi-dive days** (add dives with surface intervals), with residual N2 / He carried across dives and a daily OTU total
- Two gas modes: **Best GAS Mix** (the app picks the gases) or **User input GAS** (you enter them)
- Travel, bottom, diluent, deco and bailout gas roles
- Automatic gas switching during the ascent
- Configurable descent, ascent, deco ascent and final ascent rates
- Last stop at 3 m or 6 m, detailed or compact (stops only) plan style
- Gas breaks on high-oxygen deco
- Water salinity: Fresh, EN13319, Salt, Red Sea or custom density
- Dive site altitude, with an option for divers who arrived at altitude less than 12 hours ago
- Metric (m / L / bar / °C) or Imperial (ft / cuft / psi / °F) units

### Decompression
- **Bühlmann ZHL-16A, ZHL-16B and ZHL-16C** (ZHL-16C recommended)
- **Gradient Factors** (GF Low / GF High)
- Haldane loading at constant depth, Schreiner loading during ascents and descents
- **GF99** and **Surface GF** read-outs, no-decompression limit (NDL) and time to surface
- **VPM-B (experimental)** with conservatism 0 to 5. See [Known Limitations](#known-limitations).

### Gas management
- MOD, minimum (hypoxic) depth, END, EAD, PPN2 and gas density for every gas
- Gas consumption per segment from bottom and deco RMV
- Bottom gas reserve rules: Thirds, Quarters, Half, or Rock-Bottom (2× stress)
- Deco gas reserve rules: 1.5× required, or lost-gas scenario
- Cylinder sizes and counts, auto-sized from your SAC rate in Best GAS Mix mode

### Oxygen and safety
- CNS % from the NOAA oxygen limits, with surface decay between dives
- Pulmonary OTU per dive and per day
- Isobaric counter-diffusion (ICD) checked when the app chooses deco gases
- A clear **PLAN VALID / PLAN ERRORS** status, with the reason for every error

### Output
- Runtime table with depth, time, runtime, mix and ppO2
- Six charts: dive profile, gas partial pressures, tissue compartment bars, tissue tension timeline, gas consumption, and an M-value diagram
- **Export PDF Slate** (two-page dive slate and technical report) and **Export CSV Data**, saved to your phone or shared from the app
- Save named plans, and the app reopens with your last plan
- Dark and light themes
- Safety disclaimer that must be accepted on first launch

---

## App Screens

The app has four tabs:

| Tab | What you do there |
|---|---|
| **Settings** | Planning type, units, deco model, gradient factors, salinity, altitude, PPO2 and narcosis limits, ascent and descent rates, RMV, reserve rules, CCR setpoints, theme |
| **Planner** | Dive levels and dives in the day, Build Dive Profile, runtime table, summary (runtime, CNS, OTU, gas), errors, contingency plans, PDF / CSV export, saved plans |
| **Gases** | Recommend Best Mix, or add and edit your own travel, bottom, diluent, deco and bailout gases with cylinders |
| **Charts** | Profile, partial pressures, tissue loading, gas consumption and M-value charts |

> Screenshots will be added in `docs/screenshots/`.

---

## How to Plan a Dive

1. **Settings:** choose Open Circuit or CCR, your units, the deco model (ZHL-16C recommended) and your gradient factors. Set the salinity and altitude of the dive site.
2. **Planner:** enter the maximum depth and bottom time. Add levels for a multi-level dive, or add dives for a dive day.
3. **Gases:** tap **Recommend Best Mix**, or enter your own gases and cylinders.
4. **Planner:** tap **Build Dive Profile**. Read the runtime table and summary, and fix anything shown under **PLAN ERRORS**.
5. **Charts:** check the profile, ppO2 and tissue loading.
6. **Export** the PDF slate, and cross-check the plan with a second planner before you dive.

### Example

40 m for 20 min on air, ZHL-16C, GF 20/80, salt water, deco on EAN50 and oxygen:

| Depth (m) | Stop (min) | Gas |
|---|---|---|
| 18 | 1 | EAN50 |
| 12 | 1 | EAN50 |
| 9 | 1 | EAN50 |
| 6 | 3 | Oxygen |
| 3 | 4 | Oxygen |

Total runtime about 35 minutes. Planners round stops and handle gas switches differently, so expect differences of about one minute per stop from other planners.

---

## Decompression Models

### Bühlmann ZHL-16

The model tracks nitrogen and helium in 16 tissue compartments, with half-times from 4 to 635 minutes for nitrogen and 1.51 to 240.03 minutes for helium.

| Variant | Use |
|---|---|
| ZHL-16A | Original experimental coefficients |
| ZHL-16B | More conservative middle compartments, designed for printed tables |
| ZHL-16C | Most conservative middle compartments, the standard for dive computers (recommended) |

The three variants differ only in the nitrogen `a` coefficients.

Tissue loading at constant depth (Haldane):

```
P(t) = P0 + (Pi − P0) × (1 − e^(−k·t)),   k = ln 2 / half-time
```

Tissue loading while changing depth (Schreiner):

```
P(t) = Pi0 + R·(t − 1/k) − (Pi0 − P0 − R/k) × e^(−k·t)
```

### Gradient Factors

Gradient Factors set how close each tissue may come to its Bühlmann M-value:

```
Tolerated ambient pressure = (P_tissue − a × GF) / (GF / b + 1 − GF)
```

- **GF Low** controls the first stop. Lower values give a deeper first stop.
- **GF High** controls surfacing. Lower values give longer shallow stops.
- GF moves in a straight line from GF Low at the first stop to GF High at the surface.
- GF 100/100 is pure Bühlmann.

The full formulas and coefficient tables are in the build specification: [`public/assets/aistudio/TEC_DECO_PLANNER_SPEC_850e4700.md`](public/assets/aistudio/TEC_DECO_PLANNER_SPEC_850e4700.md).

---

## Best Gas Mix

In **Best GAS Mix** mode, for the planned depth the app:

1. Sets oxygen so ppO2 at maximum depth stays within your **PPO2 Bottom** limit.
2. Adds helium until the **END** is within your narcosis limit.
3. Adds more helium if the **gas density** is too high.
4. Adds a **travel gas** when the bottom mix is hypoxic at the surface.
5. Chooses **deco gases** for the depth (for example EAN50 and oxygen), with helium-based intermediate deco gases on deep dives, checked for ICD.
6. Sizes the **cylinders** from your SAC rate.

| Metric | Meaning |
|---|---|
| MOD | Deepest depth where ppO2 stays within the limit |
| Minimum depth | Shallowest depth where the gas is not hypoxic |
| END | Equivalent narcotic depth |
| Density | Gas density at depth, in g/L |

---

## Safety Checks

The app shows **critical errors only**, so a valid plan is not cluttered with minor notes. A plan with an error is still shown, marked **PLAN ERRORS**, with the reason.

| Error | When it is raised |
|---|---|
| Hypoxic gas | ppO2 below your PPO2 Min at any point |
| ppO2 too high (bottom or deco) | ppO2 above 1.60 bar (or your limit, if higher) |
| MOD exceeded | A gas used deeper than its MOD |
| Gas density | Above 6.5 g/L |
| CNS | Above your CNS error limit |
| Gas volume | Not enough gas for the plan plus reserve |
| Gradient factors | GF Low set higher than GF High |
| No breathable gas | No gas is safe to breathe at some depth |
| Deco overflow | Decompression obligation too large to plan |

---

## Build from Source

### Requirements
- Android Studio (latest stable)
- JDK 17 or later (the project compiles to Java 11 bytecode)
- Android SDK 36 (min SDK 24, Android 7.0)

### Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/RamyRedaM/Extreme-DECO-Planner-Android-Mobile-APP.git
   ```
2. Open the folder in Android Studio and let Gradle sync.
3. Select the **app** run configuration and run it on a device or emulator.

### Release builds

Release signing reads the keystore from environment variables. Set these before building a release:

| Variable | Purpose |
|---|---|
| `KEYSTORE_PATH` | Path to your upload keystore (default `my-upload-key.jks` in the project root) |
| `STORE_PASSWORD` | Keystore password |
| `KEY_PASSWORD` | Password of the `upload` key |

Never commit keystores or passwords to the repository.

### Automatic APK builds (GitHub Actions)

The workflow [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml) builds the APK on GitHub's servers, with no local setup needed:

1. On GitHub, open **Actions › Build APK › Run workflow** (on the `main` branch).
2. The workflow builds a debug-signed APK with JDK 17 and Gradle 9.3.1.
3. It publishes the APK as a pre-release named after `versionName`, for example `v1.27.2`, and also keeps it as a downloadable build artifact.

Before each new public build, raise `versionCode` and `versionName` in `app/build.gradle.kts`. Otherwise the new APK replaces the file in the existing release, and Android will not see it as an update.

---

## Tech Stack

| Layer | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, Material 3 |
| Architecture | MVVM (ViewModel + StateFlow) |
| Storage | Jetpack DataStore (settings, last plan, saved plans) |
| Charts | Custom Compose Canvas charts |
| Export | Android `PdfDocument` for PDF, CSV text, FileProvider for sharing |
| Tests | JUnit 4, Robolectric |
| Build | Gradle (Kotlin DSL), version catalog |

The decompression engine in `engine/` is plain Kotlin with no Android or UI code, so it can be tested on its own.

---

## Project Structure

```
Extreme-DECO-Planner-Android-Mobile-APP/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/example/
│       │   │   ├── MainActivity.kt              App entry, tabs, disclaimer
│       │   │   ├── engine/                      Decompression and gas engine (pure Kotlin)
│       │   │   │   ├── Constants.kt             Physical constants, gas densities, NOAA limits
│       │   │   │   ├── Coefficients.kt          ZHL-16A / B / C tables, VPM-B parameters
│       │   │   │   ├── UnitsAndPressure.kt      Pressure, altitude, salinity, unit conversion
│       │   │   │   ├── TissuesAndBuhlmann.kt    Haldane, Schreiner, M-values, ceilings, GF, NDL
│       │   │   │   ├── GasCalculator.kt         MOD, END, EAD, density, Best Mix, ICD
│       │   │   │   ├── OxygenAndConsumption.kt  CNS, OTU, gas use, safety checks
│       │   │   │   ├── DivePlannerEngine.kt     Schedule builder, multi-dive day, contingencies, CCR bailout
│       │   │   │   └── Models.kt                Data classes and enums
│       │   │   ├── data/PlannerRepository.kt    DataStore persistence
│       │   │   ├── export/ExportManager.kt      PDF slate and CSV export
│       │   │   └── ui/
│       │   │       ├── screens/                 Settings, Planner, Gases, Charts
│       │   │       ├── components/              Charts and shared controls
│       │   │       ├── viewmodel/               PlannerViewModel
│       │   │       └── theme/                   Colours, typography, theme
│       │   ├── res/                             Icons, fonts, images, strings
│       │   └── AndroidManifest.xml
│       ├── test/                                Unit tests (engine)
│       └── androidTest/                         Instrumented tests
├── public/assets/aistudio/                      Build specification
├── gradle/libs.versions.toml                    Dependency versions
├── build.gradle.kts
└── settings.gradle.kts
```

---

## Testing and Validation

Run the unit tests from Android Studio: right-click `app/src/test` and choose **Run Tests**, or open the Gradle panel and run **app › Tasks › verification › test**.

`DecompressionEngineTest.kt` covers:

- Pressure and altitude, MOD, END and density
- Haldane, Schreiner and the Gradient Factor formula
- CNS and OTU
- **Golden schedules** for four reference dives (40 m air at GF 20/80 and 100/100, 60 m on 18/45 at GF 30/70, 30 m air without deco gas)
- Model conservatism (ZHL-16C at least as conservative as ZHL-16A) and GF monotonicity
- Residual nitrogen across a multi-dive day
- Best Gas Mix for every stage, and cylinder sizing from SAC rate
- The critical-errors-only policy (a standard dive has no alerts, a dangerous gas raises an error)

Before relying on any change to the engine, compare the results with an established planner such as Subsurface or MultiDeco.

---

## Default Settings

| Setting | Default |
|---|---|
| Deco model | ZHL-16C |
| Gas mode | Best GAS Mix |
| PPO2 bottom / deco / min | 1.40 / 1.60 / 0.18 bar |
| Water | Salt (1030 kg/m³) |
| Last stop | 3 m |
| Units | Metric |

All defaults can be changed in the **Settings** tab.

---

## Known Limitations

- **VPM-B is experimental and approximate.** It is not a full implementation of Baker's VPM-B algorithm. It reuses the ZHL-16C tissue model and makes the tolerated supersaturation stricter, scaled by the conservatism setting. Do not treat its schedules as true VPM-B schedules. Use the Bühlmann models for real dive planning.
- **Alerts show critical errors only.** Lower-level warnings such as END above your narcosis limit, ppO2 between your chosen limit and 1.60 bar, OTU, and ICD at user-chosen gas switches are not raised as alerts. Check these values yourself in the runtime table and summary.
- Results can differ by about one minute per stop from other planners because of rounding and gas-switch handling.
- The app has not yet been independently validated. Always cross-check with a second planner.

---

## Roadmap

- [x] Test APK on GitHub Releases (v1.27.2)
- [ ] APK signed with a permanent release key
- [ ] Google Play release
- [ ] Full VPM-B implementation
- [ ] Optional warning level (END, ICD, OTU, chosen ppO2 limits)
- [ ] Screenshots and a short demo video
- [ ] Arabic language support
- [ ] pSCR (passive semi-closed rebreather) mode

---

## Contributing

Bug reports, test cases and independent validation of the engine are very welcome.

1. Open an [issue](../../issues) describing the bug or idea. For a planning bug, include the full settings and the schedule you expected.
2. Fork the repository and create a branch: `git checkout -b fix/my-change`
3. Add or update tests in `app/src/test` for any engine change.
4. Run the tests and open a pull request explaining what changed and how you validated it.

Any change that alters a decompression result must include before-and-after schedules and a comparison with at least one other planner.

---

## References

- A. A. Bühlmann, *Decompression: Decompression Sickness*, Springer
- Erik C. Baker, *Understanding M-values* and *Clearing Up the Confusion About "Deep Stops"*
- Erik C. Baker, *Oxygen Toxicity Calculations*
- NOAA Diving Manual, oxygen exposure limits
- Anthony and Mitchell, research on gas density limits in diving
- Subsurface, open-source dive log and planner (used for cross-checking)

---

## Contact

Built by **Ramy Reda**, an engineer who loves technical scuba diving.

For proposals and feedback: **RamyRedaM@gmail.com**, or open an issue on this repository.

---

## License

Copyright © 2026 Ramy Reda. All rights reserved.

The author accepts no liability for any injury or loss arising from the use of this software. See the warning at the top of this file.

---

<p align="center"><em>Plan the dive, dive the plan.</em></p>
