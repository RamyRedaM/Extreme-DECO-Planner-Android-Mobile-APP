

> [!WARNING]
> **There is always a risk of decompression sickness (DCS), oxygen toxicity and other injury on any dive, even when you follow a plan produced by this tool.** TEC DECO PLANNER is for training and planning only. It does not replace technical diver training, certification, a dive computer or your own judgement. Always cross-check plans with a second independent planner and carry backup tables. You use this software entirely at your own risk.

---

## Table of Contents

- [Overview](#overview)
- [Features](#features)
- [Screenshots](#screenshots)
- [Quick Start](#quick-start)
- [How to Plan a Dive](#how-to-plan-a-dive)
- [Decompression Models](#decompression-models)
- [Gas Selection: Best Mix](#gas-selection-best-mix)
- [Safety Checks and Alerts](#safety-checks-and-alerts)
- [Tech Stack and Architecture](#tech-stack-and-architecture)
- [Project Structure](#project-structure)
- [Engine API](#engine-api)
- [Testing and Validation](#testing-and-validation)
- [Configuration Reference](#configuration-reference)
- [Roadmap](#roadmap)
- [Contributing](#contributing)
- [References](#references)
- [License](#license)

---

## Overview

TEC DECO PLANNER helps technical divers plan open circuit and closed circuit (CCR) decompression dives. You enter the depth, bottom time, gases and your personal limits. The planner then:

1. Recommends the **best bottom mix, travel gas and deco gases** for the depth.
2. Builds a full **decompression schedule** with the model you choose.
3. Reports **runtime, CNS %, OTU, END, gas density, gas volumes** and every warning.
4. Draws **charts** of the dive profile, partial pressures, tissue loading and gas use.
5. Generates **contingency plans** (deeper, longer, lost deco gas, CCR bailout).

Everything is calculated client-side. No account, no server, no data leaves your device.

---

## Features

### Planning
- Open Circuit and CCR (setpoint-based) planning, with OC bailout from the worst point of the dive
- Multi-level profiles and repetitive dives with surface interval off-gassing
- Up to 10 travel / bottom / diluent mixes and 6 deco / bailout mixes
- Automatic gas switching at the best stop depth, with optional extra switch time
- Configurable descent rate, ascent rate, deco ascent rate and final ascent rate
- Last stop at 3 m or 6 m
- Air / gas breaks on high-O2 deco (for example 20 min O2, 5 min back gas)
- Salinity (fresh, EN13319, salt, Red Sea, custom) and altitude support

### Decompression models
- **Bühlmann ZHL-16A**, **ZHL-16B**, **ZHL-16C**
- **Gradient Factors** (GF Low / GF High) with presets: 100/100, 50/80, 30/85, 30/70, 20/80, 40/70
- Live **GF99** and **Surface GF** per runtime row
- No-decompression limit (NDL) and time to surface (TTS)
- VPM-B planned for a later release

### Gas management
- **Best Mix** recommender for bottom, travel and deco gases
- MOD, minimum (hypoxic) depth, END, EAD, PPN2 and gas density for every gas
- Gas consumption per segment from bottom and deco RMV
- Reserve rules: thirds, quarters, custom percent, or lost-gas (rock bottom)
- Cylinder sizes, fill pressures and turn pressures
- Optional gas price per fill

### Oxygen and safety
- CNS % (NOAA limits) with 90-minute surface half-time
- Pulmonary OTU with daily REPEX guidance
- Isobaric counter-diffusion (ICD) check at every gas switch, including the Rule of Fifths
- Gas density warnings at 5.2 g/L and errors at 6.2 g/L
- Volume alerts per gas type

### Output
- Detailed or compact runtime table
- Charts: dive profile with ceiling, partial pressures, tissue tensions, gas consumption, and an M-value learning chart
- Export as PDF slate, PNG, plain text or JSON
- Share any plan as a link (all inputs encoded in the URL)
- Dark and light themes, mobile first, installable as an offline PWA


---

## Quick Start

### Requirements
- Node.js 20 or later
- npm 10 or later (or pnpm / yarn)

### Install and run

.APK file to be included soon & Published later on Google Play

---

Open the address Vite prints (usually `http://localhost:5173`).

### Scripts

| Command | What it does |
|---|---|
| `npm run dev` | Start the development server with hot reload |
| `npm run build` | Type-check and build for production into `dist/` |
| `npm run preview` | Serve the production build locally |
| `npm test` | Run all unit and golden-schedule tests (Vitest) |
| `npm run test:watch` | Run tests in watch mode |
| `npm run lint` | Lint with ESLint |
| `npm run typecheck` | Run the TypeScript compiler without emitting |

### Deploy

The build output is a static site. Deploy `dist/` to GitHub Pages, Netlify, Vercel, Cloudflare Pages or any static host.

For GitHub Pages, set `base` in `vite.config.ts` to `/tec-deco-planner/` and publish `dist/` with the `actions/deploy-pages` workflow.

---

## How to Plan a Dive

1. **General settings:** choose Open Circuit or CCR, units, deco model and gradient factors.
2. **Environment:** set water salinity and dive site elevation.
3. **Profile:** enter the maximum depth and bottom time (add levels for a multi-level dive).
4. **Gases:** press **Recommend Best Mix**, or enter your own bottom, travel and deco gases.
5. **Limits:** check PPO2 bottom / deco / minimum, maximum END, gas density and ascent rates.
6. **Consumption:** enter bottom and deco RMV and your cylinders.
7. **Build Dive Profile:** read the runtime table, check every warning, then review the charts and contingency plans.
8. **Export or share** the plan, and cross-check it with a second planner before the dive.

### Example

40 m for 20 min on air, ZHL-16C, GF 20/80, salt water, deco on EAN50 and O2:

| Action | Depth (m) | Stop (min) | Mix |
|---|---|---|---|
| Stop | 18 | 1 | EAN50 |
| Stop | 12 | 1 | EAN50 |
| Stop | 9 | 1 | EAN50 |
| Stop | 6 | 3 | Oxygen |
| Stop | 3 | 4 | Oxygen |

Total runtime about 35 minutes. Exact numbers vary slightly between planners because of rounding and gas-switch handling.

---

## Decompression Models

### Bühlmann ZHL-16

The model tracks nitrogen and helium in 16 theoretical tissue compartments with half-times from 4 to 635 minutes (N2) and 1.51 to 240.03 minutes (He).

| Variant | Use |
|---|---|
| ZHL-16A | Original experimental coefficients |
| ZHL-16B | More conservative middle compartments, designed for printed tables |
| ZHL-16C | Most conservative middle compartments, designed for dive computers (default) |

The three variants differ only in the nitrogen `a` coefficients. Helium coefficients and all half-times are shared.

**Tissue loading** at constant depth uses the Haldane equation:

```
P(t) = P0 + (Pi − P0) × (1 − e^(−k·t)),   k = ln 2 / half-time
```

During ascents and descents the planner uses the Schreiner equation:

```
P(t) = Pi0 + R·(t − 1/k) − (Pi0 − P0 − R/k) × e^(−k·t)
```

### Gradient Factors

Gradient Factors scale how close each tissue may come to its Bühlmann M-value:

```
Tolerated ambient pressure = (P_tissue − a × GF) / (GF / b + 1 − GF)
```

- **GF Low** sets the depth of the first stop (lower = deeper first stop).
- **GF High** sets the allowed supersaturation at the surface (lower = longer shallow stops).
- GF rises linearly from GF Low at the first stop to GF High at the surface.
- GF 100/100 is pure Bühlmann.

Full coefficient tables and every formula are in [`docs/SPEC.md`](docs/SPEC.md).

---

## Gas Selection: Best Mix

For a target depth, the recommender:

1. Sets oxygen so PPO2 at maximum depth stays at or below **PPO2 Bottom**.
2. Adds helium until **END** is within your limit (with or without O2 counted as narcotic).
3. Adds more helium if **gas density** is above 5.2 g/L.
4. Detects **hypoxic** mixes and adds a travel gas for the descent and shallow ascent.
5. Picks **deco gases** by depth band: O2 at 6 m, EAN50 at 21 m, and richer trimix intermediates for deeper dives.
6. Optionally snaps to standard mixes: Air, EAN32, 21/35, 18/45, 15/55, 12/65, 10/70.

| Metric | Formula |
|---|---|
| MOD | depth where PPO2 = PPO2 max / fO2 |
| Minimum depth | depth where PPO2 = PPO2 min / fO2 |
| END (O2 narcotic) | depth where (fN2 + fO2) × P_amb = P_surface |
| Density | (fO2 × 1.429 + fN2 × 1.2506 + fHe × 0.1786) × P_amb |

---

## Safety Checks and Alerts

| Check | Default limit | Level |
|---|---|---|
| PPO2 bottom | 1.40 bar | Warning, error above 1.60 |
| PPO2 deco | 1.60 bar | Error |
| Hypoxic gas | PPO2 below 0.18 bar | Error |
| Narcosis (END / PPN2) | END 30 m | Warning |
| Gas density | 5.2 g/L | Warning, error above 6.2 g/L |
| ICD at gas switch | ΔPPN2 above 0.5 bar, or Rule of Fifths | Warning |
| CNS | 80 % | Warning, error above 100 % |
| OTU | 300 per dive | Warning |
| Gas volume | Required plus reserve above available | Error |
| Gas used beyond its MOD | Any | Error |

Errors never hide the plan. The plan is shown in red with the reason, so you can fix the inputs.

---

## Tech Stack and Architecture

| Layer | Choice |
|---|---|
| UI | React 18, TypeScript (strict), Tailwind CSS |
| State | Zustand, persisted to localStorage |
| Charts | Recharts |
| Computation | Pure TypeScript engine running in a Web Worker |
| Tests | Vitest |
| Build | Vite, PWA plugin for offline use |

**Design rules**
- The engine in `src/engine` has no UI imports. It takes an input object and returns a result object, with no side effects.
- All internal units are SI: bar, metres, litres, minutes, °C. Imperial units are converted only in the UI.
- Recalculation is debounced (300 ms) and runs in a worker, so the UI never freezes.

---

## Project Structure

```
tec-deco-planner/
├── public/
│   ├── icons/                 App icons, favicon, maskable PWA icon
│   └── manifest.webmanifest
├── src/
│   ├── engine/                Decompression and gas engine (pure TS)
│   │   ├── constants.ts       Physical constants, gas densities, NOAA tables
│   │   ├── coefficients.ts    ZHL-16A / B / C tables
│   │   ├── pressure.ts        Depth, pressure, altitude, salinity
│   │   ├── gas.ts             MOD, END, EAD, density, minimum depth
│   │   ├── bestMix.ts         Bottom, travel and deco gas recommender
│   │   ├── tissues.ts         Haldane and Schreiner equations
│   │   ├── buhlmann.ts        M-values, ceilings, gradient factors
│   │   ├── planner.ts         Schedule builder
│   │   ├── oxygen.ts          CNS and OTU
│   │   ├── consumption.ts     Gas volumes, reserves, turn pressure
│   │   ├── ccr.ts             Closed circuit model
│   │   ├── checks.ts          Warnings and alerts
│   │   ├── repetitive.ts      Surface interval
│   │   ├── contingency.ts     Deeper, longer, lost gas, bailout
│   │   └── index.ts           Public API
│   ├── worker/                Web Worker wrapper for the engine
│   ├── state/                 Zustand store
│   ├── ui/                    Sections, components and charts
│   └── export/                PDF, PNG, text and share-link export
├── tests/                     Unit and golden-schedule tests
├── docs/
│   ├── SPEC.md                Full build specification
│   └── screenshots/
├── index.html
├── vite.config.ts
├── package.json
└── README.md
```

---

## Engine API

```ts
import { planDive, recommendBottomMix } from './engine';

const result = planDive({
  model: 'ZHL16C',
  gfLow: 0.20,
  gfHigh: 0.80,
  salinity: 'salt',
  elevation: 0,
  levels: [{ depth: 40, time: 20 }],
  gases: [
    { name: 'Air',   o2: 0.21, he: 0, role: 'bottom' },
    { name: 'EAN50', o2: 0.50, he: 0, role: 'deco' },
    { name: 'O2',    o2: 1.00, he: 0, role: 'deco' },
  ],
  ppO2Bottom: 1.4,
  ppO2Deco: 1.6,
  descentRate: 20,
  ascentRate: 9,
  decoAscentRate: 9,
  surfaceAscentRate: 3,
  lastStop: 3,
  bottomRmv: 20,
  decoRmv: 15,
});

console.log(result.stops, result.runtime, result.oxygen.cns, result.warnings);
```

| Function | Returns |
|---|---|
| `planDive(input)` | Segments, stops, runtime, TTS, tissue timeline, CNS, OTU, gas use, warnings |
| `recommendBottomMix(depth, settings)` | Recommended bottom gas with MOD, END, density and notes |
| `recommendTravelGas(bottom, settings)` | Travel gas, or `null` if not needed |
| `recommendDecoGases(depth, bottom, settings)` | Ordered list of deco gases |
| `gasMetrics(gas, depth, env, settings)` | PPO2, PPN2, PPHe, END, EAD, density, MOD, minimum depth |
| `ndl(depth, gas, settings)` | No-decompression limit in minutes |
| `ceiling(tissues, gf, model, env)` | Current ceiling in metres |
| `gf99(tissues, pAmb, model)` | Current supersaturation as % of the M-value gradient |
| `contingencies(input)` | Array of contingency plans |

---

## Testing and Validation

```bash
npm test
```

The suite includes:

- **Unit tests** for pressure, altitude, MOD, END, density, Haldane, Schreiner, CNS and OTU, checked against hand-calculated values.
- **Golden schedules** for reference dives (air, nitrox and trimix, several GF settings). Each stop must be within ±1 minute and the runtime within ±2 minutes of the reference.
- **Property tests**, for example: a lower GF High never shortens deco, ZHL-16C is never less conservative than ZHL-16A, and adding a richer deco gas never lengthens deco.

Before relying on any change to the engine, cross-check results against an established planner such as Subsurface or MultiDeco.

---

## Configuration Reference

| Setting | Default | Range |
|---|---|---|
| Deco model | ZHL-16C | ZHL-16A, ZHL-16B, ZHL-16C |
| Gradient factors | 30 / 70 | 5 to 100 % |
| PPO2 bottom / deco / min | 1.40 / 1.60 / 0.18 bar | |
| Maximum END | 30 m | 20 to 50 m |
| Maximum gas density | 5.2 g/L | |
| Descent rate | 20 m/min | 5 to 30 |
| Ascent rate to first stop | 9 m/min | 3 to 18 |
| Deco ascent rate | 9 m/min | 1 to 18 |
| Last stop to surface | 3 m/min | 1 to 9 |
| Last stop | 3 m | 3 or 6 m |
| Bottom / deco RMV | 20 / 15 L/min | |
| Water salinity | Salt (1030 kg/m³) | Fresh, EN13319, Salt, Red Sea, Custom |
| Elevation | 0 m | 0 to 5000 m |

---

## Roadmap

- [ ] VPM-B model with conservatism settings
- [ ] Passive semi-closed rebreather (pSCR) mode
- [ ] Real-gas (Z-factor) cylinder volume correction
- [ ] Dive log import and comparison with planned profile
- [ ] Multi-language interface (Arabic and English first)
- [ ] Printable waterproof slate templates

---

## Contributing

Contributions are welcome, especially independent validation of the engine.

1. Fork the repository and create a branch: `git checkout -b feature/my-change`
2. Add or update tests for any engine change.
3. Run `npm run lint`, `npm run typecheck` and `npm test`.
4. Open a pull request that explains what changed and how you validated it.

Engine changes that alter any decompression result must include before-and-after schedules and a comparison with at least one other planner.

---

## References

- A. A. Bühlmann, *Tauchmedizin* / *Decompression: Decompression Sickness*, Springer
- Erik C. Baker, *Clearing Up the Confusion About "Deep Stops"* and *Understanding M-values*
- Erik C. Baker, *Oxygen Toxicity Calculations*
- NOAA Diving Manual, oxygen exposure limits
- Anthony and Mitchell, *Respiratory physiology of rebreather diving* (gas density limits)
- Subsurface open-source dive log and planner, for cross-validation

---

## License

Released under the [MIT License](LICENSE).

The authors accept no liability for any injury or loss arising from the use of this software. See the warning at the top of this file.

---

<p align="center">
  Built by <strong>Ramy Reda</strong> · Plan the dive, dive the plan.
</p>
