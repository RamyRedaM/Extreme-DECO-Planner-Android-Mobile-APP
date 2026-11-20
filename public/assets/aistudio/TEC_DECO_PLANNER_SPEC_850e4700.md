# TEC DECO PLANNER: Build Specification for an AI Code Builder

**Document type:** Product + engineering specification (single source of truth)
**Target:** Web-based technical decompression dive planner (Open Circuit + CCR)
**Algorithms:** Bühlmann ZHL-16A, ZHL-16B, ZHL-16C, each with Gradient Factors (GF Low / GF High). Optional Phase 2: VPM-B.
**Version:** 1.0 (2026-11-20)

---

## 0. MASTER PROMPT (paste this into the AI builder first)

```
You are a senior full-stack engineer and a technical diving (TEC) subject-matter expert.
Build a production-quality, mobile-first web application called "TEC DECO PLANNER"
that plans technical decompression dives, exactly as specified in the attached
TEC_DECO_PLANNER_SPEC.md. Follow the spec literally. Where the spec gives a formula,
constant or table, use it exactly; do not substitute values from memory.

Hard requirements:
1. Tech stack: React 18 + TypeScript (strict) + Vite + Tailwind CSS. Charts: Recharts.
   State: Zustand. Tests: Vitest. No backend; all computation runs client-side.
2. The decompression engine is a pure TypeScript module (/src/engine) with ZERO UI
   imports, run inside a Web Worker, fully unit-tested against Section 14 test vectors.
3. Implement Bühlmann ZHL-16A, ZHL-16B and ZHL-16C with Gradient Factors, selectable
   by the user. Coefficient tables in Section 5 are authoritative.
4. Implement every input in Section 3 (mirrors the reference app screens), every output
   in Section 10, every safety check/alert in Section 9, and the "Best Mix" gas
   selector in Section 7.
5. Use SI units internally (bar, metres, litres, minutes, °C). Convert to imperial
   only at the UI layer.
6. Show a permanent safety disclaimer (Section 15). Never hide warnings.
7. Deliver: complete source code, README with run/test instructions, and a passing
   test suite. Build in the phase order of Section 16, running tests after each phase.
```

---

## 1. Product Overview

A planner for technical divers that:

1. Takes dive settings (depth, bottom time, gases, ascent/descent rates, PPO2 limits, GF, salinity, altitude, RMV).
2. Recommends the **best bottom (trimix/nitrox) mix and deco gases** for the target depth.
3. Computes a decompression schedule with the selected algorithm (ZHL-16A/B/C + GF).
4. Reports runtime table, gas consumption, CNS %, OTU, END, gas density, ICD warnings.
5. Draws charts: dive profile, gas partial pressures, tissue tensions, gas consumption.
6. Supports multi-level dives, repetitive dives (surface interval), bailout / lost-gas plans, and contingency plans (deeper / longer).
7. Exports / shares the plan (PDF, PNG, text, URL).

**Users:** Technical divers (Normoxic/Hypoxic Trimix, Advanced Nitrox, Deco Procedures, CCR). **Not** a substitute for training or a dive computer.

---

## 2. Architecture

```
/src
  /engine                  <- pure TS, no React, no DOM
    constants.ts           physical constants, gas densities, NOAA tables
    coefficients.ts        ZHL-16A/B/C tables (Section 5)
    units.ts               conversions
    pressure.ts            depth <-> pressure, altitude, salinity
    gas.ts                 Gas type, MOD, END, EAD, density, min depth
    bestMix.ts             best bottom mix + deco gas recommender
    tissues.ts             TissueState, Haldane, Schreiner
    buhlmann.ts            M-values, ceilings, GF interpolation
    planner.ts             schedule builder (descent, bottom, ascent, stops)
    oxygen.ts              CNS %, OTU
    consumption.ts         gas volume per segment, reserves, turn pressure
    ccr.ts                 closed-circuit inspired pressure model
    checks.ts              all alerts / warnings (Section 9)
    repetitive.ts          surface interval off-gassing
    contingency.ts         +depth / +time / lost-gas plans
    vpm/                   (Phase 2) VPM-B
    index.ts               public API: planDive(input): PlanResult
  /worker
    engine.worker.ts       wraps engine; postMessage in/out
  /state
    useSettings.ts         Zustand store, persisted to localStorage
  /ui
    /sections              DiveSettings, Gases, Alerts, Profile, Results, Charts
    /components            NumberSelect, GasRow, Table, Badge
    /charts                ProfileChart, PPChart, TissueChart, ConsumptionChart
  /export                  pdf.ts, png.ts, shareUrl.ts, text.ts
/tests                     Vitest unit + golden tests
```

**Rules**
- Engine functions are pure and deterministic. Input object in, result object out.
- Debounce recalculation 300 ms after any input change; compute in the Web Worker.
- All numbers in the engine use `number` (float64). Round only for display.

---

## 3. User Inputs (mirror the reference planner screens)

### 3.1 General Settings

| Field | Type | Default | Range / Options |
|---|---|---|---|
| Planning Type | enum | Open Circuit | Open Circuit, CCR, pSCR (Phase 2) |
| Units | enum | Metres/Litres/Bar/°C | Metric, Imperial (ft/cuft/psi/°F) |
| Deco Model | enum | ZHL-16C | ZHL-16A, ZHL-16B, ZHL-16C, VPM-B (Phase 2) |
| Gradient Factor Low / High (%) | int, int | 30 / 70 | 5 to 100, GF Low ≤ GF High |
| GF presets | buttons | | 100/100 (pure Bühlmann), 50/80, 30/85, 30/70, 20/80, 40/70 |
| Water Salinity | enum | Salt (1030) | Fresh 1000, EN13319 1020, Salt 1030, Red Sea 1030 (editable), Custom kg/m³ |
| Dive Elevation (m) | int | 0 | 0 to 5000 |
| Surface Temperature (°C) | int | 22 | -10 to 45 (used for gas volume correction, optional) |
| Interface Style | enum | Dark | Dark, Light |
| Plan Style | enum | Detailed | Detailed (all segments), Compact (stops only) |

### 3.2 Dive Profile

| Field | Type | Default | Notes |
|---|---|---|---|
| Levels | list of {depth m, time min} | [{40, 20}] | Multi-level. Bottom time counts from leaving surface (configurable: "BT includes descent" true/false) |
| Mix per level | gas ref | auto | Auto = Best Mix or user pick |
| Repetitive dive | bool | false | If true, ask previous plan + surface interval (min) |

### 3.3 Dive Settings

| Field | Default | Range |
|---|---|---|
| PPO2 Bottom (bar) | 1.40 | 1.00 to 1.60 |
| PPO2 Deco (bar) | 1.60 | 1.20 to 1.60 |
| PPO2 Min (bar) | 0.18 | 0.16 to 0.21 |
| PPN2 Max (bar), narcosis limit | 3.95 | 2.37 to 4.74 (= END 20 m to 50 m) |
| PPN2 Max Deco (bar) | 3.16 | |
| Max END (m), derived or direct | 30 | 20 to 50 |
| O2 narcotic? | true | true/false (affects END) |
| Max gas density (g/L) | 5.2 | warn > 5.2, error > 6.2 |
| Descent Rate (m/min) | 20 | 5 to 30 |
| Ascent Rate to first stop (m/min) | 9 | 3 to 18 |
| Ascent Deco Rate, between stops (m/min) | 9 | 1 to 18 |
| Ascent Rate to Surface, last stop to surface (m/min) | 3 | 1 to 9 |
| Stop increment (m) | 3 | 3 (fixed, or 1 for "continuous") |
| Last Stop (m) | 3 | 3 or 6 |
| Minimum stop time (min) | 1 | 0.5, 1 |
| Mix Change Extra Time (min) | 1 | 0 to 3; added at every gas switch |
| Stop on gas switch | true | Forces a min stop at switch depth even if not required |

### 3.4 Gas Breaks (air breaks)

| Field | Default |
|---|---|
| Enable | No |
| Apply when depth ≤ (m) | 6 |
| Break Mix | Bottom/back gas (Air) |
| Break After (min on high-O2 gas) | 20 |
| Break Time (min) | 5 |

### 3.5 Gas Consumption

| Field | Default |
|---|---|
| Bottom RMV / SAC (L/min at surface) | 20 |
| Deco RMV (L/min) | 15 |
| Stress factor for lost-gas plan | 1.5 |
| Reserve rule bottom gas | Thirds (or Quarters, Half, Custom %) |
| Reserve rule deco gas | 1.5 × required (or lost-gas teammate) |
| Cylinders per gas | size (L water), fill (bar), count (single/twin) |

### 3.6 Dive Gases

- **Travel / Bottom / Diluent mixes:** 1 to 10 rows. Each row: O2 %, He %, MOD (Auto or manual, m), Role (Travel / Bottom / Diluent), Enabled flag.
- **Deco / Bailout mixes:** 0 to 6 rows. Same fields. Role: Deco / Bailout.
- N2 % = 100 - O2 - He (computed, read-only). Validate O2 + He ≤ 100.
- Quick-add buttons: Air, EAN32, EAN50, EAN80, O2, TX 21/35, 18/45, 15/55, 12/65, 10/70.
- Button "Recommend Best Mix" runs Section 7.

### 3.7 CCR only

| Field | Default |
|---|---|
| Setpoint descent / bottom / deco (bar) | 0.7 / 1.3 / 1.3 |
| Setpoint switch depths (m) | low→high at 20 m, high-deco at 6 m (optional 1.4 or 1.6) |
| Diluent | from gas list |
| Bailout gases | from deco list |
| Bailout plan | Compute OC bailout from the worst point (end of bottom time at max depth) |

### 3.8 Dive Alerts (thresholds)

| Field | Default | Meaning |
|---|---|---|
| ICD ΔPPN2 Max (bar) | 0.5 | Max allowed rise in inspired PPN2 at a gas switch |
| ICD ΔPPHe check | on | Apply "Rule of Fifths" |
| Consider Lipid Response | No | Informational flag (shows advisory text only) |
| Bottom mix more than (L) | 4800 | Warn if required volume exceeds |
| Deco mix ≤49% O2 more than (L) | 2400 | |
| Deco mix 50 to 99% O2 more than (L) | 2400 | |
| Deco O2 more than (L) | 1200 | |
| CNS warn / error (%) | 80 / 100 | |
| OTU warn per dive | 300 | |

---

## 4. Physics and Constants

```ts
export const P_SURFACE_SEA = 1.01325;        // bar
export const G = 9.80665;                     // m/s²
export const P_WATER_VAPOUR = 0.0627;         // bar, Bühlmann value at 37 °C
export const FN2_AIR = 0.7902;                // use 0.7902 for surface saturation (Bühlmann)
export const FO2_AIR = 0.2095;
export const DENSITY_G_PER_L = { O2: 1.429, N2: 1.2506, He: 0.1786 }; // at 1 atm, 0 °C
export const SALINITY = { fresh: 1000, en13319: 1020, salt: 1030, redSea: 1030 }; // kg/m³ (Red Sea editable, true value ~1028 to 1031)
```

### 4.1 Surface pressure from altitude (barometric formula)
```
P_surf(h) = 1.01325 × (1 − 2.25577e-5 × h)^5.25588      // h in metres
```

### 4.2 Depth ↔ ambient pressure
```
barPerMetre = ρ × g / 100000          // ρ in kg/m³ (salt 1030 → 0.10101 bar/m)
P_amb(d)  = P_surf + d × barPerMetre
d(P_amb)  = (P_amb − P_surf) / barPerMetre
```

### 4.3 Inspired inert gas pressure (alveolar, open circuit)
```
P_i,gas = (P_amb − P_WATER_VAPOUR) × f_gas
```

### 4.4 Altitude acclimatisation
At altitude, initial tissue tension = `(P_surf(h) − P_WV) × 0.7902` (assume fully acclimatised; add option "arrived < 12 h ago" which uses sea-level tensions instead).

---

## 5. Bühlmann ZHL-16 Coefficients (AUTHORITATIVE)

16 compartments. Compartment 1 has variant "1b" (ZHL-16A uses 1b optionally). Use **compartment 1 (4.0 min)** by default for all variants; expose "use 1b" as an advanced toggle.

### 5.1 Half-times (minutes), all variants

| # | N2 HT | He HT |
|---|---|---|
| 1 | 4.0 | 1.51 |
| 1b | 5.0 | 1.88 |
| 2 | 8.0 | 3.02 |
| 3 | 12.5 | 4.72 |
| 4 | 18.5 | 6.99 |
| 5 | 27.0 | 10.21 |
| 6 | 38.3 | 14.48 |
| 7 | 54.3 | 20.53 |
| 8 | 77.0 | 29.11 |
| 9 | 109.0 | 41.20 |
| 10 | 146.0 | 55.19 |
| 11 | 187.0 | 70.69 |
| 12 | 239.0 | 90.34 |
| 13 | 305.0 | 115.29 |
| 14 | 390.0 | 147.42 |
| 15 | 498.0 | 188.24 |
| 16 | 635.0 | 240.03 |

### 5.2 Nitrogen "a" coefficients (bar). Variants differ ONLY here.

| # | ZHL-16A | ZHL-16B | ZHL-16C |
|---|---|---|---|
| 1 | 1.2599 | 1.2599 | 1.2599 |
| 1b | 1.1696 | 1.1696 | 1.1696 |
| 2 | 1.0000 | 1.0000 | 1.0000 |
| 3 | 0.8618 | 0.8618 | 0.8618 |
| 4 | 0.7562 | 0.7562 | 0.7562 |
| 5 | 0.6667 | 0.6667 | 0.6200 |
| 6 | 0.5933 | 0.5600 | 0.5043 |
| 7 | 0.5282 | 0.4947 | 0.4410 |
| 8 | 0.4701 | 0.4500 | 0.4000 |
| 9 | 0.4187 | 0.4187 | 0.3750 |
| 10 | 0.3798 | 0.3798 | 0.3500 |
| 11 | 0.3497 | 0.3497 | 0.3295 |
| 12 | 0.3223 | 0.3223 | 0.3065 |
| 13 | 0.2971 | 0.2850 | 0.2835 |
| 14 | 0.2737 | 0.2737 | 0.2610 |
| 15 | 0.2523 | 0.2523 | 0.2480 |
| 16 | 0.2327 | 0.2327 | 0.2327 |

### 5.3 Nitrogen "b" coefficients (dimensionless), same for A/B/C

| # | b N2 |
|---|---|
| 1 | 0.5050 |
| 1b | 0.5578 |
| 2 | 0.6514 |
| 3 | 0.7222 |
| 4 | 0.7825 |
| 5 | 0.8126 |
| 6 | 0.8434 |
| 7 | 0.8693 |
| 8 | 0.8910 |
| 9 | 0.9092 |
| 10 | 0.9222 |
| 11 | 0.9319 |
| 12 | 0.9403 |
| 13 | 0.9477 |
| 14 | 0.9544 |
| 15 | 0.9602 |
| 16 | 0.9653 |

### 5.4 Helium a / b coefficients, same for A/B/C

| # | a He | b He |
|---|---|---|
| 1 | 1.7424 | 0.4245 |
| 1b | 1.6189 | 0.4770 |
| 2 | 1.3830 | 0.5747 |
| 3 | 1.1919 | 0.6527 |
| 4 | 1.0458 | 0.7223 |
| 5 | 0.9220 | 0.7582 |
| 6 | 0.8205 | 0.7957 |
| 7 | 0.7305 | 0.8279 |
| 8 | 0.6502 | 0.8553 |
| 9 | 0.5950 | 0.8757 |
| 10 | 0.5545 | 0.8903 |
| 11 | 0.5333 | 0.8997 |
| 12 | 0.5189 | 0.9073 |
| 13 | 0.5181 | 0.9122 |
| 14 | 0.5176 | 0.9171 |
| 15 | 0.5172 | 0.9217 |
| 16 | 0.5119 | 0.9267 |

**Variant notes for the UI help text**
- **ZHL-16A:** original experimental/theoretical coefficients.
- **ZHL-16B:** more conservative middle compartments, intended for printed tables.
- **ZHL-16C:** most conservative in middle compartments, intended for dive computers / real-time planning. Default.

---

## 6. Core Decompression Algorithms

### 6.1 Data types
```ts
type Gas = { o2: number; he: number; n2: number; name: string; role: 'travel'|'bottom'|'diluent'|'deco'|'bailout'; modOverride?: number };
type Tissue = { pN2: number; pHe: number };            // bar
type TissueState = Tissue[];                           // length 16
type Model = 'ZHL16A' | 'ZHL16B' | 'ZHL16C' | 'VPMB';
type Segment = { type: 'descent'|'level'|'ascent'|'stop'|'switch'|'break'; fromDepth: number; toDepth: number; duration: number; gas: Gas; runtime: number; ceiling?: number; ppO2?: number; };
```

### 6.2 Initial tissue state (`initTissues(Psurf)`)
```
pN2_i = (P_surf − P_WV) × 0.7902   for all i
pHe_i = 0
```

### 6.3 Constant depth: Haldane equation (`haldane`)
```
k = ln(2) / halfTime
P_t = P_0 + (P_i − P_0) × (1 − e^(−k t))
```

### 6.4 Linear depth change: Schreiner equation (`schreiner`)
Use for descents and ascents (and constant depth, with R = 0, it reduces to Haldane).
```
P_i0 = (P_amb,start − P_WV) × f_gas          // inspired at segment start
R    = (ΔP_amb / t) × f_gas                    // bar/min, + descending, − ascending
P_t  = P_i0 + R × (t − 1/k) − (P_i0 − P_0 − R/k) × e^(−k t)
```
Apply separately to N2 and He for every compartment.

### 6.5 Combined coefficients for a compartment (`mixedAB`)
```
P_inert = pN2 + pHe
a = (a_N2 × pN2 + a_He × pHe) / P_inert
b = (b_N2 × pN2 + b_He × pHe) / P_inert
```

### 6.6 M-value and tolerated ambient pressure
```
M(P_amb)          = P_amb / b + a                 // max tolerated tissue tension
P_amb,tol (pure)  = (P_inert − a) × b
```

### 6.7 Gradient Factors (`toleratedAmbient(tissue, gf)`)
```
P_amb,tol = (P_inert − a × gf) / (gf / b + 1 − gf)
```
- gf = 1.0 gives pure Bühlmann. gf in 0..1.
- Ceiling of the diver = max over all 16 compartments of P_amb,tol, converted to depth.

### 6.8 GF slope (`gfAtDepth`)
1. At the end of the bottom phase compute ceiling with **GF Low**. Round **up** to the next stop increment (3 m multiples). This is `firstStopDepth` (fixed for the rest of the ascent).
2. For any depth d ≤ firstStopDepth:
```
gf(d) = GF_high + (GF_low − GF_high) × d / firstStopDepth
```
3. When checking whether the diver may leave stop S for next stop N, evaluate the ceiling with `gf(N)` (the GF at the destination depth).

### 6.9 Supersaturation display (`gfSurface`, `gf99`)
```
GF99    = max_i (P_inert,i − P_amb) / (M_i(P_amb) − P_amb)       // current % of M-value gradient
SurfGF  = same, evaluated with P_amb = P_surf                      // if surfaced now
```
Show both in the tissue chart and in each runtime row.

### 6.10 No-decompression limit (`ndl`)
At constant depth with gas g, step 1 min (then refine to 1 s) until ceiling with GF_high > 0 (above surface). Cap at 999 min.

---

## 7. Gas Selection and "Best Mix" Algorithms

### 7.1 Gas metrics (`gas.ts`)
```
MOD(g, ppO2max)   = d( ppO2max / f_O2 )                        // depth whose P_amb = ppO2max/fO2
MinDepth(g, ppMin)= max(0, d( ppMin / f_O2 ))                   // hypoxic floor
END(g, d):
   O2 narcotic:     END = d( (f_N2 + f_O2) × P_amb(d) )        // = d((1 − f_He) × P_amb)
   O2 not narcotic: END = d( f_N2 × P_amb(d) / 0.7902 )
EAD(nitrox, d)    = d( f_N2 × P_amb(d) / 0.7902 )
PPN2(g, d)        = f_N2 × P_amb(d)
Density(g, d)     = (f_O2×1.429 + f_N2×1.2506 + f_He×0.1786) × P_amb(d) / 1.01325     // g/L
```
Round MOD **down** to the nearest metre; min depth **up**.

### 7.2 Best bottom mix (`recommendBottomMix(maxDepth, settings)`)
Inputs: max depth D, PPO2 Bottom, Max END (or PPN2 max), O2 narcotic flag, max density, PPO2 Min.
```
1. P = P_amb(D)
2. fO2 = floor( PPO2_bottom / P × 100 ) / 100                 // cap at 1.0 (and at 0.40 for bottom if user sets)
3. Narcosis target:
     if O2 narcotic: f_narc_max = P_amb(END_max) / P              // f_N2 + f_O2 allowed
                     fHe = max(0, 1 − f_narc_max)
     else:           fN2_max = 0.7902 × P_amb(END_max) / P
                     fHe = max(0, 1 − fO2 − fN2_max)
   Also enforce PPN2 limit: fN2 ≤ PPN2_max / P
4. Round fHe UP to nearest 1 % (or to standard-mix step of 5 % if "Standard mixes" toggle on).
5. fN2 = 1 − fO2 − fHe   (if < 0, set fN2 = 0 and reduce fHe accordingly)
6. Density check: while Density(g, D) > maxDensity and fN2 > 0: fHe += 0.01, fN2 −= 0.01.
   If still above limit: flag "Density exceeds limit, reduce depth or use more He".
7. Hypoxic check: if MinDepth(g, PPO2_min) > 0 → bottom mix is hypoxic → REQUIRE a
   travel gas. Call recommendTravelGas().
8. Snap to "Standard mix" list if toggle on: choose the standard mix with
   fO2 ≤ computed fO2 and END ≤ END_max and density ≤ max, with the highest fO2.
   Standard list: Air, EAN32, 21/35, 18/45, 15/55, 12/65, 10/70, 8/80 (TX O2/He).
9. Return {gas, MOD, MinDepth, END at D, Density at D, ppO2 at D, notes[]}.
```

### 7.3 Travel gas (`recommendTravelGas`)
Pick the richest standard gas (21/35, 18/45, Air, EAN32) whose MinDepth = 0 and whose MOD ≥ the bottom mix's MinDepth + 3 m safety, and END within limit at its switch depth. Switch to bottom gas at `max(bottomMinDepth + 3, travelMOD − 3)` on descent.

### 7.4 Deco gases (`recommendDecoGases(maxDepth, bottomGas, settings)`)
Strategy (standard TEC practice):
- Always include **O2 (100 %)** for ≥ 6 m stops (MOD at 1.6 = 6 m).
- Include **EAN50** (MOD 21 m at 1.6) for dives deeper than ~30 m.
- Dives deeper than ~60 m: add an intermediate gas such as **TX 35/25 or 30/30** or **EAN32/EAN36**, chosen so the switch depth ≈ MOD at PPO2 Deco and END at switch ≤ END_max.
- Deeper than ~90 m: add **TX 21/35 or 18/45** as the first deco gas.
- General rule: each new gas gives a meaningful ppO2 jump (≥ 0.3 bar) at its switch depth; max 4 deco gases.
- Optional: "Optimise deco gases" brute-forces candidates (O2 in 1 % steps at switch depths 21, 36, 57 m etc.) minimising total runtime under the ICD and END constraints.

### 7.5 Gas to breathe at depth during ascent (`bestGasAt(depth, available)`)
```
candidates = gases where MinDepth ≤ depth ≤ MOD(PPO2_deco) and role ∈ {deco, bailout, bottom, travel}
choose candidate with highest fO2; tie-break: highest fHe
apply ICD check before allowing switch (Section 9.3); if fails, keep current gas and warn
```
Switch happens at the first stop depth ≤ MOD of the new gas (stop depths are multiples of 3 m). Add `Mix Change Extra Time`.

---

## 8. Dive Plan Generation (`planner.ts`)

### 8.1 Top-level
```ts
function planDive(input: PlanInput): PlanResult {
  const env  = buildEnvironment(input);               // Psurf, barPerMetre
  let tissues = input.previousTissues ?? initTissues(env.Psurf);
  if (input.surfaceInterval) tissues = surfaceOffgas(tissues, input.surfaceInterval, env);
  const segs: Segment[] = [];
  descend(...);  bottomLevels(...);  ascendAndDecompress(...);
  return { segments, stops, runtime, tissuesTimeline, oxygen: computeOxygen(segs),
           gasUse: computeConsumption(segs), warnings: runChecks(segs, ...), charts };
}
```

### 8.2 Descent (`descend`)
- From 0 to first level depth at Descent Rate. Use Schreiner.
- If travel gas defined, switch to bottom gas at its switch depth (split the segment).
- Option "BT includes descent" (default true): level time = BT − descent time.

### 8.3 Bottom / multi-level (`bottomLevels`)
- Level segment: Haldane (or Schreiner with R = 0).
- Between levels: Schreiner at descent or ascent rate. Record tissues every 10 s for charts.

### 8.4 Ascent and decompression (`ascendAndDecompress`) PSEUDOCODE
```
depth = lastLevelDepth
firstStop = ceilToIncrement( depthOf( ceilingPressure(tissues, GF_low) ) )
if firstStop < lastStopDepth and firstStop > 0: firstStop = lastStopDepth
if firstStop <= 0: ascend to surface at ascentRate (then rateToSurface for last 3 m) → done (NDL dive)

// 1) Ascent to first stop, in 1 m (or 3 m) sub-steps, switching gas on the way if a stop
//    at a gas switch depth is required, or if "stop on gas switch" is ON.
while depth > firstStop:
    next = max(firstStop, depth − 1)
    gas  = bestGasAt(depth)   (only switch at multiples of stopIncrement)
    schreiner(tissues, depth→next, (depth−next)/ascentRate, gas)
    depth = next
    // re-check: ceiling may have moved deeper; if ceiling(GF_low) > next, stop here

// 2) Stops
while depth > 0:
    nextStop = (depth > lastStopDepth) ? depth − stopIncrement : 0
    gas = bestGasAt(depth); if gas changed: add switch segment (Mix Change Extra Time), apply ICD check
    stopTime = 0
    loop:
        gfNext = gfAtDepth(nextStop)
        if depthOf(ceilingPressure(tissues, gfNext)) <= nextStop: break
        haldane(tissues, depth, 1 minute (or 1 s resolution then round up to minStopTime), gas)
        stopTime += 1
        if gasBreaks enabled and time on high-O2 ≥ breakAfter and depth ≤ breakDepth: insert break segment on breakMix
    if stopTime > 0 or (switched gas and stopOnSwitch): push stop segment (min 1 min)
    rate = (nextStop == 0) ? rateToSurface : decoAscentRate
    schreiner(tissues, depth→nextStop, (depth−nextStop)/rate, gas)
    depth = nextStop
```
**Precision:** compute stops at 1 s resolution internally, then display rounded **up** to whole minutes (or 30 s). Runtime shown as mm:ss.

**Edge cases**
- Last stop 6 m: skip 3 m stop; ceiling check uses nextStop = 0.
- Ceiling goes deeper during ascent to first stop: stop immediately at the current 3 m multiple.
- If no gas is breathable at a depth (hypoxic / over MOD): return error, do not produce a plan.
- Max iterations guard: 3000 min total deco → abort with "Deco obligation too large".

### 8.5 Surface interval / repetitive dives (`surfaceOffgas`)
Haldane on air at P_surf for the interval. Also decay CNS with half-time 90 min. OTU carries over the day.

### 8.6 CCR model (`ccr.ts`)
```
ppO2 = min(setpoint, P_amb − P_WV)          // cannot exceed ambient
if P_amb × f_O2,dil > setpoint: ppO2 = P_amb × f_O2,dil  (diluent flush case)
P_inert_total = P_amb − P_WV − ppO2
P_i,N2 = P_inert_total × f_N2,dil / (f_N2,dil + f_He,dil)
P_i,He = P_inert_total × f_He,dil / (f_N2,dil + f_He,dil)
```
Schreiner with CCR: use 1 m sub-steps with constant inspired pressure per sub-step (because inspired inert pressure is not linear in depth). Bailout plan: switch to OC at the end of bottom time at max depth, recompute ascent with bailout gases and stress RMV.

### 8.7 VPM-B (Phase 2, optional)
Parameters: critical radius N2 0.55 µm, He 0.45 µm, surface tension 0.0179 N/m, skin compression 0.257 N/m, crit volume λ 7500 fsw·min, regeneration 20160 min, conservatism +0..+5 (radius × 1 + 0.05·c steps). Implement per Baker's VPM-B reference; same 16 half-times. Mark UI "experimental" until validated.

---

## 9. Safety Checks and Alerts (`checks.ts`)

Each check returns `{level: 'info'|'warning'|'error', code, message, segmentIndex?}`. Errors block the "plan valid" badge but the plan is still shown in red.

| Code | Condition | Level |
|---|---|---|
| PPO2_HIGH_BOTTOM | ppO2 > PPO2 Bottom during bottom/travel | warning; > 1.6 error |
| PPO2_HIGH_DECO | ppO2 > PPO2 Deco | error |
| HYPOXIC | ppO2 < PPO2 Min at any point (incl. surface on bottom mix) | error |
| END_HIGH | END > Max END (or PPN2 > PPN2 Max) | warning |
| DENSITY_HIGH | density > 5.2 g/L warn, > 6.2 error | |
| ICD_N2 | at switch, ΔPPN2 inspired > ICD limit | warning |
| ICD_FIFTHS | ΔfN2 > ΔfHe / 5 when He drops (Rule of Fifths) | warning |
| CNS | > 80 % warn, > 100 % error | |
| OTU | > 300 per dive warn; daily REPEX limits | |
| GAS_VOLUME | required volume > available (with reserve) | error |
| GAS_ALERT_LIMITS | volume > the Dive Alerts thresholds (3.8) | warning |
| GF_EXTREME | GF Low < 10 or GF High > 95 | info |
| ASCENT_FAST | ascent rate > 18 m/min | warning |
| MOD_EXCEEDED | gas used deeper than MOD | error |
| LIPID | "Consider Lipid Response" = Yes | info: advisory text only |

### 9.1 Oxygen toxicity: CNS (NOAA single-exposure limits)

| ppO2 (bar) | Limit (min) |
|---|---|
| 0.6 | 720 |
| 0.7 | 570 |
| 0.8 | 450 |
| 0.9 | 360 |
| 1.0 | 300 |
| 1.1 | 240 |
| 1.2 | 210 |
| 1.3 | 180 |
| 1.4 | 150 |
| 1.5 | 120 |
| 1.6 | 45 |

```
CNS% per segment = Σ (dt / limit(ppO2)) × 100   // integrate in ≤ 10 s steps for ascents/descents
limit(ppO2): linear interpolation between table rows; ppO2 ≤ 0.5 → 0 % contribution;
ppO2 > 1.6 → extrapolate steeply (use 1.6→45, 1.65→ 15 min) and raise error
Surface decay: CNS(t) = CNS0 × 0.5^(t / 90)
```

### 9.2 Oxygen toxicity: OTU (pulmonary)
Constant ppO2:
```
OTU = t × ((ppO2 − 0.5) / 0.5)^0.83          // only if ppO2 > 0.5
```
Linear change P1→P2 over t (Baker):
```
OTU = (3/11) × t / (P2 − P1) × [ ((P2 − 0.5)/0.5)^(11/6) − ((P1 − 0.5)/0.5)^(11/6) ]
(clip P1, P2 to ≥ 0.5 and split the segment where it crosses 0.5)
```
REPEX daily guidance (display): 1 day 850, 2 days 700/day, 3 days 620/day, 4 days 525/day, 5 days 460/day, 6+ days ~420/day and below.

### 9.3 Isobaric Counter-Diffusion (ICD)
At each gas switch from gas A to gas B at depth d:
```
ΔPPN2 = (f_N2,B − f_N2,A) × P_amb(d)
ΔPPHe = (f_He,A − f_He,B) × P_amb(d)
Warn if ΔPPN2 > ICD_PPN2_Max   (default 0.5 bar)
Rule of Fifths: warn if ΔfN2 > ΔfHe / 5  when switching from a He-rich to a He-poor gas
```

### 9.4 Lipid response
Informational only. If "Yes", show advisory: high-fat meals and hydration status may alter bubble formation; consider more conservative GF. Do not change the computation unless a future "conservatism modifier" is defined.

---

## 10. Gas Consumption and Reserves (`consumption.ts`)

```
Volume per segment (surface litres) = RMV × (P_avg / P_surf) × t
    P_avg = (P_amb,start + P_amb,end) / 2     // linear segments
RMV = Bottom RMV for descent/bottom/ascent to first stop, Deco RMV for stops and above
Total per gas = Σ segments on that gas
```
**Reserves**
- Bottom gas, thirds: required_available = used × 3/2 (use 1/3 in, 1/3 out, 1/3 reserve) i.e. available ≥ 1.5 × (descent + bottom + ascent to switch).
- Better: **Lost-gas / Rock-bottom**: gas for two divers (stress RMV × 2) from the worst point (end of bottom time at max depth) to the first gas switch, plus 1 min problem-solving.
- Deco gas: lost-gas scenario (recompute ascent without that deco gas, take max per gas) or 1.5 × plan.

**Cylinder / turn pressure**
```
Available (L) = cylinderWaterVolume × fillPressure × count       // ideal gas
Optional real-gas: Z-factor correction (van der Waals) for fills > 200 bar
Turn pressure = fill − (usable_for_bottom / (waterVolume × count))
Min pressure to end dive = reserve / (waterVolume × count)
```
Gas price module: per-gas price per litre (O2, He), cost per fill (Section "Gas Price" in reference).

---

## 11. Contingency Plans (`contingency.ts`)
Auto-generate and display alongside the main plan:
1. **Deeper:** max depth + 3 m (configurable).
2. **Longer:** bottom time + 5 min.
3. **Deeper and longer** combined.
4. **Lost deco gas:** for each deco gas, plan without it.
5. **CCR bailout** (CCR mode).
6. **Lost bottom gas** volume check for teammate.
Each contingency is a full `planDive` call with modified inputs.

---

## 12. Outputs and UI

### 12.1 Runtime table (matches reference "Detailed" plan style)
Columns: **Action | Depth | Time | RunTime | Mix | ppO2 | END | Density | Ceiling | GF99**. Actions: Descent, Level, Ascent, Switch, Stop, Break. Highlight stops (bold) and gas switches (separator line + gas badge). Compact style shows stops only.

### 12.2 Summary cards
Max depth, total runtime, total deco time, TTS (time to surface at end of BT), CNS %, OTU, NDL (if no deco), first stop, gas per mix (litres + bar per cylinder), surface GF at surfacing, warnings count.

### 12.3 Charts (Recharts)
1. **Dive profile:** depth vs time (inverted Y), coloured by gas, gas-switch labels, ceiling line (dashed).
2. **Gas partial pressures:** ppO2, ppN2, ppHe vs time, with limit lines (PPO2 Bottom/Deco/Min, PPN2 max).
3. **Tissue tensions:** 16 lines (N2 + He total) vs time, plus ambient pressure; optional heat-map of % M-value (Subsurface-style).
4. **Gas consumption:** stacked bars by gas vs available (with reserve marked).
5. **Learning tools:** M-value diagram (P_amb vs P_tissue) showing M-value lines, GF lines, ambient line, and the dive's leading compartment path.

### 12.4 Export / share
PDF slate (waterproof-slate layout: big font, stops and switches only), PNG of chart, plain text, shareable URL with all inputs base64-encoded in the hash, JSON import/export of settings.

### 12.5 UX rules
- Mobile-first single column (as in reference screens), collapsible sections: General, Dive Settings, Gases, Alerts, Profile, Results, Charts.
- Number pickers with +/- spin and direct typing; validate ranges on blur.
- Red / amber badges next to any field that causes an error / warning.
- Persist last settings in localStorage. Save named plans (max 50).
- Dark and light theme. WCAG AA contrast.

---

## 13. Public Engine API

```ts
planDive(input: PlanInput): PlanResult
recommendBottomMix(depth: number, s: Settings): MixRecommendation
recommendTravelGas(bottom: Gas, s: Settings): Gas | null
recommendDecoGases(depth: number, bottom: Gas, s: Settings): Gas[]
gasMetrics(g: Gas, depth: number, env: Env, s: Settings): { ppO2, ppN2, ppHe, END, EAD, density, MOD, minDepth }
ndl(depth: number, gas: Gas, s: Settings, tissues?: TissueState): number
ceiling(tissues: TissueState, gf: number, model: Model, env: Env): number   // metres
gf99(tissues: TissueState, pAmb: number, model: Model): number
surfaceGF(tissues: TissueState, env: Env, model: Model): number
cns(segments: Segment[]): number
otu(segments: Segment[]): number
consumption(segments: Segment[], s: Settings): GasUse[]
contingencies(input: PlanInput): PlanResult[]
```
All functions are pure. `PlanResult` includes `segments`, `stops`, `runtime`, `tts`, `tissueTimeline` (sampled every 10 s), `oxygen`, `gasUse`, `warnings`, `meta` (model, GF, version).

---

## 14. Test Vectors and Validation

### 14.1 Unit tests (exact)
| Test | Input | Expected |
|---|---|---|
| Pressure | 30 m, salt 1030, sea level | 1.01325 + 30 × 0.10101 = **4.0435 bar** (±0.001) |
| Altitude | 2000 m | P_surf ≈ **0.7950 bar** (±0.001) |
| MOD | EAN50, ppO2 1.6, salt | **21.6 m → displayed 21 m** |
| MOD | O2, ppO2 1.6 | **5.8 m → 5 m (UI may show 6 m with 1.6 rounding toggle)** |
| END (O2 narc) | TX 18/45 at 60 m salt | (0.55 × 7.0736 − 1.01325)/0.101006 ≈ **28.5 m** |
| Density | Air at 40 m salt | ≈ **6.4 g/L** (error-level) |
| Haldane | P0 0.7405, Pi 3.0, HT 4 min, t 4 min | P = 0.7405 + (3.0 − 0.7405)/2 = **1.8703** |
| Schreiner R=0 | same as Haldane | identical to Haldane |
| GF formula | gf = 1 | equals pure Bühlmann (P − a) × b |
| CNS | 1.4 bar for 30 min | **20 %** |
| OTU | 1.4 bar for 30 min | 30 × (1.8)^0.83 ≈ **48.9** |

### 14.2 Golden schedules (ZHL-16C, salt 1030, sea level, descent 20 m/min, ascent 9 m/min, deco ascent 9, last-stop-to-surface 3 m/min, last stop 3 m, BT includes descent, deco gases EAN50 + O2 at PPO2 deco 1.6, no switch extra time, 1-min rounding)

Generated by a reference implementation of this spec. Accept the builder's output if each stop is within **±1 min** and runtime within **±2 min** (planners legitimately differ by rounding and switch handling). Also cross-check against Subsurface, MultiDeco or Baltic/DivePro.

| Case | Profile | GF | Expected stops (depth m: min) | Runtime ≈ |
|---|---|---|---|---|
| A | 40 m / 20 min, Air | 20/80 | 18:1, 12:1, 9:1, 6:3, 3:4 | 35 min |
| B | 40 m / 20 min, Air | 100/100 | 6:1, 3:3 | 29 min |
| C | 60 m / 20 min, TX 18/45 | 30/70 | 30:1, 27:1, 24:1, 21:2, 18:1, 15:2, 12:3, 9:5, 6:10, 3:14 | 67 min |
| D | 30 m / 30 min, Air, no deco gas | 100/100 | 3:7 | 41 min |

Reference value from the original app screens (DivePro, 40 m / 20 min air, GF 20/80, descent 25, ascent 15, deco 9, surface 6, EAN50 + O2, PPO2 deco 1.58): 21:1, 12:1, 9:1, 6:2, 3:4, runtime 34:42. The builder's plan with those exact settings must land within the tolerance above.

### 14.3 Property tests
- Lower GF Low → first stop deeper or equal.
- Lower GF High → total deco time longer or equal.
- ZHL-16C deco time ≥ ZHL-16B ≥ ZHL-16A for the same dive (middle-compartment-controlled dives).
- Adding a richer deco gas never increases total deco time (ICD warnings aside).
- Tissue tensions never negative; ceiling never deeper than current depth after a valid stop.

---

## 15. Safety Disclaimer (must be shown on first run and in the footer)

> WARNING: There is always a risk of decompression sickness (DCS), oxygen toxicity and other injuries for any dive profile, even if you follow the plan produced by this tool. No algorithm or planner can eliminate this risk. This software is for training and planning purposes only and does not replace proper technical diver training, certification, a dive computer, or your own judgement. Always dive within your training limits, cross-check plans with a second independent planner, and carry backup tables. You use this tool entirely at your own risk.

Require the user to tick "I understand" before the first plan is shown (stored in localStorage).

---

## 16. Build Phases (deliver and test in this order)

1. **Engine core:** constants, pressure, gas metrics, coefficients, Haldane/Schreiner, ceilings, GF. Pass 14.1.
2. **Planner:** descent, levels, ascent, stops, gas switching, last stop, rounding. Pass 14.2 cases A to D.
3. **Oxygen + consumption + checks:** CNS, OTU, ICD, density, END, gas volumes, alerts.
4. **Best Mix recommender:** bottom, travel, deco gases.
5. **UI:** all input sections (Section 3), runtime table, summary, warnings.
6. **Charts and exports:** 4 charts + M-value learning chart, PDF slate, share URL.
7. **Advanced:** multi-level, repetitive dives, contingencies, gas breaks, CCR + bailout, altitude, imperial units.
8. **Phase 2 (optional):** VPM-B, pSCR, gas price, real-gas Z-factor, PWA offline mode.

**Definition of done:** all tests green, Lighthouse ≥ 90 mobile, no TypeScript errors, all inputs in Section 3 wired, all warnings in Section 9 reachable from the UI, disclaimer enforced.

---

## 17. Glossary
**GF** Gradient Factor; **M-value** max tolerated inert gas tension; **END** Equivalent Narcotic Depth; **EAD** Equivalent Air Depth; **MOD** Maximum Operating Depth; **RMV/SAC** Respiratory Minute Volume / Surface Air Consumption; **CNS** central nervous system O2 toxicity clock; **OTU** Oxygen Tolerance Unit; **ICD** Isobaric Counter-Diffusion; **TTS** Time To Surface; **GF99** current supersaturation as % of the M-value gradient; **ZHL-16** Zürich (Bühlmann) 16-compartment model; **VPM-B** Varying Permeability Model (Boyle's law compensation).
