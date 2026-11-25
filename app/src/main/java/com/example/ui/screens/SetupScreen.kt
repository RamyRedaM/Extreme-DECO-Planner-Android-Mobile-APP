package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.*
import com.example.ui.components.*
import com.example.ui.theme.AlertCrimson
import com.example.ui.theme.NitroxAmber
import com.example.ui.viewmodel.PlannerUiState
import com.example.ui.viewmodel.PlannerViewModel
import kotlin.math.roundToInt

@Composable
fun SetupScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onShowDisclaimer: () -> Unit,
    onNavigateToPlanner: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val g = state.input.general
    val d = state.input.dive
    val b = state.input.gasBreaks
    val c = state.input.consumption
    val ccr = state.input.ccr
    val a = state.input.alerts

    var planNameInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Settings Hero Dive Computer Photo Banner
        item {
            HeroPhotoBanner(
                imageRes = R.drawable.img_settings_computer,
                contentDescription = "Technical scuba diver checking wrist dive computer underwater",
                badge = "VER 1.27.2 · RAMY.DECO",
                title = "Dive Computer & Model Settings",
                subtitle = "${g.decoModel.shortLabel} · GF ${g.gfLow}/${g.gfHigh} · ${g.planningType.label}",
                actionLabel = "Open Planner →",
                onAction = onNavigateToPlanner
            )
        }

        // 2. General Settings (Section 3.1)
        item {
            CollapsibleSection(
                title = "General Settings & Algorithm",
                subtitle = "${g.decoModel.shortLabel} · GF ${g.gfLow}/${g.gfHigh} · ${g.units.depthUnit}",
                initiallyExpanded = true,
                testTag = "section_general"
            ) {
                Text("Planning Type", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlanningType.entries.forEach { pt ->
                        FilterChip(
                            selected = g.planningType == pt,
                            onClick = { viewModel.updateGeneral { it.copy(planningType = pt) } },
                            label = { Text(pt.label) }
                        )
                    }
                }

                Text("Units System", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    UnitSystem.entries.forEach { u ->
                        FilterChip(
                            selected = g.units == u,
                            onClick = { viewModel.updateGeneral { it.copy(units = u) } },
                            label = { Text(if (u == UnitSystem.METRIC) "Metric (m/L/bar)" else "Imperial (ft/ft³/psi)") }
                        )
                    }
                }

                Text("Decompression Model", style = MaterialTheme.typography.labelLarge)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DecoModel.entries.forEach { m ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = g.decoModel == m,
                                onClick = { viewModel.updateGeneral { it.copy(decoModel = m) } }
                            )
                            Column {
                                Text(m.label, style = MaterialTheme.typography.bodyMedium)
                                Text(m.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                if (g.decoModel == DecoModel.VPMB) {
                    NumberStepperRow(
                        label = "VPM-B Conservatism",
                        value = g.vpmConservatism.toDouble(),
                        step = 1.0,
                        min = 0.0,
                        max = 5.0,
                        unit = "+c",
                        testTag = "vpm_conservatism",
                        onValueChange = { v -> viewModel.updateGeneral { it.copy(vpmConservatism = v.roundToInt()) } }
                    )
                }

                Text("Diving Agency (Best GAS Mix method)", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .testTag("gas_agency_row"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GasAgency.entries.forEach { a ->
                        FilterChip(
                            selected = g.gasAgency == a,
                            onClick = { viewModel.updateGeneral { it.copy(gasAgency = a) } },
                            label = { Text(a.label) }
                        )
                    }
                }
                Text(
                    g.gasAgency.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Gradient Factors (Manual Input)", style = MaterialTheme.typography.labelLarge)
                NumberStepperRow(
                    label = "GF Low",
                    subtitle = "Manual entry (5–100%)",
                    value = g.gfLow.toDouble(),
                    step = 5.0,
                    min = 5.0,
                    max = 100.0,
                    unit = "%",
                    testTag = "gf_low",
                    onValueChange = { v -> viewModel.updateGeneral { it.copy(gfLow = v.roundToInt()) } }
                )
                NumberStepperRow(
                    label = "GF High",
                    subtitle = "Manual entry (${g.gfLow}–100%)",
                    value = g.gfHigh.toDouble(),
                    step = 5.0,
                    min = g.gfLow.toDouble(),
                    max = 100.0,
                    unit = "%",
                    testTag = "gf_high",
                    onValueChange = { v -> viewModel.updateGeneral { it.copy(gfHigh = v.roundToInt()) } }
                )

                Text("Water Salinity", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    SalinityPreset.entries.forEach { sp ->
                        FilterChip(
                            selected = g.salinityPreset == sp,
                            onClick = { viewModel.updateGeneral { it.copy(salinityPreset = sp, customSalinityKgM3 = sp.densityKgM3) } },
                            label = { Text(sp.label.substringBefore(" (")) }
                        )
                    }
                }
                if (g.salinityPreset == SalinityPreset.CUSTOM || g.salinityPreset == SalinityPreset.RED_SEA) {
                    val isRedSea = g.salinityPreset == SalinityPreset.RED_SEA
                    NumberStepperRow(
                        label = if (isRedSea) "Red Sea Water Density" else "Water Density",
                        value = g.customSalinityKgM3,
                        step = if (isRedSea) 1.0 else 5.0,
                        min = if (isRedSea) GeneralSettings.RED_SEA_MIN_KGM3 else 995.0,
                        max = if (isRedSea) GeneralSettings.RED_SEA_MAX_KGM3 else 1050.0,
                        unit = "kg/m³",
                        subtitle = "Used in calculations: ${g.effectiveSalinity.roundToInt()} kg/m³",
                        testTag = "custom_salinity",
                        onValueChange = { v -> viewModel.updateGeneral { it.copy(customSalinityKgM3 = v) } }
                    )
                }

                NumberStepperRow(
                    label = "Dive Elevation",
                    value = g.elevationM.toDouble(),
                    step = 100.0,
                    min = 0.0,
                    max = 5000.0,
                    unit = "m",
                    testTag = "elevation_m",
                    onValueChange = { v -> viewModel.updateGeneral { it.copy(elevationM = v.roundToInt()) } }
                )
                if (g.elevationM > 0) {
                    SwitchSettingRow(
                        label = "Arrived < 12h ago at altitude",
                        subtitle = "Starts with sea-level N₂ tissue saturation instead of acclimatised altitude",
                        checked = g.arrivedUnder12hAtAltitude,
                        testTag = "alt_arrived_switch",
                        onCheckedChange = { cVal -> viewModel.updateGeneral { it.copy(arrivedUnder12hAtAltitude = cVal) } }
                    )
                }

                NumberStepperRow(
                    label = "Surface Temperature",
                    value = g.surfaceTempC.toDouble(),
                    step = 2.0,
                    min = -10.0,
                    max = 45.0,
                    unit = "°C",
                    testTag = "surface_temp",
                    onValueChange = { v -> viewModel.updateGeneral { it.copy(surfaceTempC = v.roundToInt()) } }
                )

                SwitchSettingRow(
                    label = "Light Theme (White & Marine Blue)",
                    subtitle = "Toggle off for Dark Abyssal mode",
                    checked = !g.darkTheme,
                    testTag = "light_theme_switch",
                    onCheckedChange = { isLight -> viewModel.updateGeneral { it.copy(darkTheme = !isLight) } }
                )
                SwitchSettingRow(
                    label = "Bottom Time Includes Descent",
                    checked = g.btIncludesDescent,
                    testTag = "bt_includes_descent_switch",
                    onCheckedChange = { inc -> viewModel.updateGeneral { it.copy(btIncludesDescent = inc) } }
                )
                SwitchSettingRow(
                    label = "Standard Mixes Only (Best Mix)",
                    subtitle = "Snap recommended mixes to standard fills (21/35, 18/45, 15/55...)",
                    checked = g.standardMixesOnly,
                    testTag = "standard_mixes_switch",
                    onCheckedChange = { std -> viewModel.updateGeneral { it.copy(standardMixesOnly = std) } }
                )
                SwitchSettingRow(
                    label = "Use Compartment 1b (5.0 min HT)",
                    subtitle = "Optional ZHL-16A compartment 1b variant",
                    checked = g.useCompartment1b,
                    testTag = "use_1b_switch",
                    onCheckedChange = { u1b -> viewModel.updateGeneral { it.copy(useCompartment1b = u1b) } }
                )
            }
        }

        // 3. Dive Settings (Section 3.3)
        item {
            CollapsibleSection(
                title = "Dive Limits, Rates & Stops",
                subtitle = "ppO₂ ${d.ppO2Bottom}/${d.ppO2Deco} · END ${d.maxEndM.roundToInt()}m · Last stop ${d.lastStopM}m",
                testTag = "section_dive_settings"
            ) {
                NumberStepperRow("PPO₂ Bottom", d.ppO2Bottom, 0.05, 1.0, 1.6, "bar", 2, testTag = "ppo2_bottom") { v ->
                    viewModel.updateDiveSettings { it.copy(ppO2Bottom = v) }
                }
                NumberStepperRow("PPO₂ Deco", d.ppO2Deco, 0.05, 1.2, 1.6, "bar", 2, testTag = "ppo2_deco") { v ->
                    viewModel.updateDiveSettings { it.copy(ppO2Deco = v) }
                }
                NumberStepperRow("PPO₂ Min (Hypoxic)", d.ppO2Min, 0.01, 0.16, 0.21, "bar", 2, testTag = "ppo2_min") { v ->
                    viewModel.updateDiveSettings { it.copy(ppO2Min = v) }
                }
                NumberStepperRow("PPN₂ Max Bottom", d.ppN2Max, 0.1, 2.37, 4.74, "bar", 2, testTag = "ppn2_max") { v ->
                    viewModel.updateDiveSettings { it.copy(ppN2Max = v) }
                }
                NumberStepperRow("PPN₂ Max Deco", d.ppN2MaxDeco, 0.1, 2.0, 4.0, "bar", 2, testTag = "ppn2_max_deco") { v ->
                    viewModel.updateDiveSettings { it.copy(ppN2MaxDeco = v) }
                }
                NumberStepperRow("Max END", d.maxEndM, 3.0, 20.0, 50.0, "m", 0, testTag = "max_end") { v ->
                    viewModel.updateDiveSettings { it.copy(maxEndM = v) }
                }
                SwitchSettingRow("Oxygen Narcotic in END", checked = d.o2Narcotic, testTag = "o2_narcotic_switch") { v ->
                    viewModel.updateDiveSettings { it.copy(o2Narcotic = v) }
                }
                NumberStepperRow("Max Gas Density", d.maxGasDensityGL, 0.2, 4.0, 6.2, "g/L", 1, testTag = "max_density") { v ->
                    viewModel.updateDiveSettings { it.copy(maxGasDensityGL = v) }
                }
                HorizontalDivider()
                NumberStepperRow("Descent Rate", d.descentRateMMin, 2.0, 5.0, 30.0, "m/m", 0, testTag = "descent_rate") { v ->
                    viewModel.updateDiveSettings { it.copy(descentRateMMin = v) }
                }
                NumberStepperRow("Ascent to First Stop", d.ascentRateToFirstStopMMin, 1.0, 3.0, 18.0, "m/m", 0, testTag = "ascent_rate") { v ->
                    viewModel.updateDiveSettings { it.copy(ascentRateToFirstStopMMin = v) }
                }
                NumberStepperRow("Ascent Between Stops", d.ascentDecoRateMMin, 1.0, 1.0, 18.0, "m/m", 0, testTag = "deco_ascent_rate") { v ->
                    viewModel.updateDiveSettings { it.copy(ascentDecoRateMMin = v) }
                }
                NumberStepperRow("Last Stop to Surface", d.ascentRateToSurfaceMMin, 1.0, 1.0, 9.0, "m/m", 0, testTag = "surface_rate") { v ->
                    viewModel.updateDiveSettings { it.copy(ascentRateToSurfaceMMin = v) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Last Stop:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FilterChip(selected = d.lastStopM == 3, onClick = { viewModel.updateDiveSettings { it.copy(lastStopM = 3) } }, label = { Text("3 m") })
                    FilterChip(selected = d.lastStopM == 6, onClick = { viewModel.updateDiveSettings { it.copy(lastStopM = 6) } }, label = { Text("6 m") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Stop Increment:", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    FilterChip(selected = d.stopIncrementM == 3, onClick = { viewModel.updateDiveSettings { it.copy(stopIncrementM = 3) } }, label = { Text("3 m") })
                    FilterChip(selected = d.stopIncrementM == 1, onClick = { viewModel.updateDiveSettings { it.copy(stopIncrementM = 1) } }, label = { Text("1 m") })
                }
                NumberStepperRow("Mix Change Extra Time", d.mixChangeExtraTimeMin, 0.5, 0.0, 3.0, "min", 1, testTag = "mix_change_time") { v ->
                    viewModel.updateDiveSettings { it.copy(mixChangeExtraTimeMin = v) }
                }
                SwitchSettingRow("Force Stop on Gas Switch", checked = d.stopOnGasSwitch, testTag = "stop_on_switch") { v ->
                    viewModel.updateDiveSettings { it.copy(stopOnGasSwitch = v) }
                }
            }
        }

        // 4. Gas Breaks (Section 3.4)
        item {
            CollapsibleSection(
                title = "Gas Breaks (Air Breaks)",
                subtitle = if (b.enabled) "Every ${b.breakAfterMin.roundToInt()}m for ${b.breakTimeMin.roundToInt()}m at ≤ ${b.applyDepthMaxM.roundToInt()}m" else "Disabled",
                testTag = "section_gas_breaks"
            ) {
                SwitchSettingRow("Enable O₂ Air Breaks", checked = b.enabled, testTag = "air_breaks_enabled") { v ->
                    viewModel.updateGasBreaks { it.copy(enabled = v) }
                }
                if (b.enabled) {
                    NumberStepperRow("Apply Depth ≤", b.applyDepthMaxM, 3.0, 3.0, 12.0, "m", 0, testTag = "break_depth") { v ->
                        viewModel.updateGasBreaks { it.copy(applyDepthMaxM = v) }
                    }
                    NumberStepperRow("Break After (High O₂)", b.breakAfterMin, 5.0, 5.0, 45.0, "min", 0, testTag = "break_after") { v ->
                        viewModel.updateGasBreaks { it.copy(breakAfterMin = v) }
                    }
                    NumberStepperRow("Break Duration", b.breakTimeMin, 1.0, 1.0, 15.0, "min", 0, testTag = "break_time") { v ->
                        viewModel.updateGasBreaks { it.copy(breakTimeMin = v) }
                    }
                }
            }
        }

        // 5. Gas Consumption & Reserves (Section 3.5)
        item {
            CollapsibleSection(
                title = "Gas Consumption & Reserve Rules",
                subtitle = "Bottom ${c.bottomRmvLMin.roundToInt()} / Deco ${c.decoRmvLMin.roundToInt()} L/min",
                testTag = "section_consumption"
            ) {
                NumberStepperRow("Bottom RMV / SAC", c.bottomRmvLMin, 1.0, 8.0, 40.0, "L/m", 0, testTag = "bottom_rmv") { v ->
                    viewModel.updateConsumption { it.copy(bottomRmvLMin = v) }
                }
                NumberStepperRow("Deco RMV", c.decoRmvLMin, 1.0, 8.0, 35.0, "L/m", 0, testTag = "deco_rmv") { v ->
                    viewModel.updateConsumption { it.copy(decoRmvLMin = v) }
                }
                NumberStepperRow("Stress Factor (Bailout/RB)", c.stressFactor, 0.1, 1.0, 2.5, "×", 1, testTag = "stress_factor") { v ->
                    viewModel.updateConsumption { it.copy(stressFactor = v) }
                }
                Text("Bottom Reserve Rule", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ReserveRuleBottom.entries.forEach { rule ->
                        FilterChip(
                            selected = c.bottomReserveRule == rule,
                            onClick = { viewModel.updateConsumption { it.copy(bottomReserveRule = rule) } },
                            label = { Text(rule.label) }
                        )
                    }
                }
                Text("Deco Reserve Rule", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReserveRuleDeco.entries.forEach { rule ->
                        FilterChip(
                            selected = c.decoReserveRule == rule,
                            onClick = { viewModel.updateConsumption { it.copy(decoReserveRule = rule) } },
                            label = { Text(rule.label) }
                        )
                    }
                }
            }
        }

        // 6. CCR Settings (Section 3.7)
        if (g.planningType == PlanningType.CCR) {
            item {
                CollapsibleSection(
                    title = "CCR Setpoints & Bailout",
                    subtitle = "SP ${ccr.setpointDescent} / ${ccr.setpointBottom} / ${ccr.setpointDeco} bar",
                    initiallyExpanded = true,
                    testTag = "section_ccr"
                ) {
                    NumberStepperRow("Descent Setpoint", ccr.setpointDescent, 0.1, 0.4, 1.3, "bar", 1, testTag = "ccr_sp_desc") { v ->
                        viewModel.updateCcr { it.copy(setpointDescent = v) }
                    }
                    NumberStepperRow("Bottom Setpoint", ccr.setpointBottom, 0.1, 0.7, 1.4, "bar", 1, testTag = "ccr_sp_bot") { v ->
                        viewModel.updateCcr { it.copy(setpointBottom = v) }
                    }
                    NumberStepperRow("Deco Setpoint", ccr.setpointDeco, 0.1, 0.7, 1.6, "bar", 1, testTag = "ccr_sp_deco") { v ->
                        viewModel.updateCcr { it.copy(setpointDeco = v) }
                    }
                    NumberStepperRow("Low→High Switch Depth", ccr.switchToHighDepthM, 3.0, 6.0, 40.0, "m", 0, testTag = "ccr_sw_high") { v ->
                        viewModel.updateCcr { it.copy(switchToHighDepthM = v) }
                    }
                    NumberStepperRow("Deco SP Switch Depth", ccr.switchToDecoDepthM, 3.0, 3.0, 21.0, "m", 0, testTag = "ccr_sw_deco") { v ->
                        viewModel.updateCcr { it.copy(switchToDecoDepthM = v) }
                    }
                }
            }
        }

        // 7. Safety Alert Thresholds (CRITICAL / HIGH / MEDIUM / LOW)
        item {
            CollapsibleSection(
                title = "Safety Alert Thresholds",
                subtitle = "4 levels: Critical · High · Medium · Low · CNS ${a.cnsWarnPct.roundToInt()}/${a.cnsErrorPct.roundToInt()}% · OTU ${a.otuWarnPerDive.roundToInt()}",
                testTag = "section_alerts"
            ) {
                Text(
                    text = "Every plan is checked against more than 30 safety rules. Alerts are colour-coded: Critical (red) = unsafe, change the plan · High (orange) = a limit is exceeded · Medium (yellow) = review · Low (gray) = advisory.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                NumberStepperRow(
                    "CNS Warning Limit", a.cnsWarnPct, 5.0, 30.0, 100.0, "%", 0,
                    subtitle = "HIGH (orange) alert above this CNS %",
                    testTag = "alert_cns_warn"
                ) { v -> viewModel.updateAlerts { it.copy(cnsWarnPct = v.coerceAtMost(it.cnsErrorPct)) } }
                NumberStepperRow(
                    "CNS Critical Limit", a.cnsErrorPct, 5.0, 70.0, 150.0, "%", 0,
                    subtitle = "CRITICAL (red) alert above this CNS %",
                    testTag = "alert_cns_err"
                ) { v -> viewModel.updateAlerts { it.copy(cnsErrorPct = v.coerceAtLeast(it.cnsWarnPct)) } }
                NumberStepperRow(
                    "OTU Limit per Dive", a.otuWarnPerDive, 25.0, 100.0, 850.0, "OTU", 0,
                    subtitle = "HIGH alert above this pulmonary oxygen dose (daily limit 850)",
                    testTag = "alert_otu"
                ) { v -> viewModel.updateAlerts { it.copy(otuWarnPerDive = v) } }
                NumberStepperRow(
                    "ICD Max ΔppN₂ at Switch", a.icdDeltaPpN2Max, 0.05, 0.1, 1.5, "bar", 2,
                    subtitle = "HIGH alert if a gas switch raises ppN₂ by more than this",
                    testTag = "alert_icd"
                ) { v -> viewModel.updateAlerts { it.copy(icdDeltaPpN2Max = v) } }
                SwitchSettingRow(
                    label = "ICD Rule of Fifths",
                    subtitle = "MEDIUM alert if N₂ rises more than 1/5 of the helium drop",
                    checked = a.icdRuleOfFifths,
                    testTag = "alert_fifths"
                ) { on -> viewModel.updateAlerts { it.copy(icdRuleOfFifths = on) } }
                SwitchSettingRow(
                    label = "Consider Helium Lipid Response",
                    subtitle = "LOW advisory on helium mixes",
                    checked = a.considerLipidResponse,
                    testTag = "alert_lipid"
                ) { on -> viewModel.updateAlerts { it.copy(considerLipidResponse = on) } }
                Text(
                    text = "Gas budget alerts (LOW) when a gas uses more than:",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                NumberStepperRow("Bottom / Travel Mix", a.bottomMixMaxLitres, 200.0, 400.0, 20000.0, "L", 0, testTag = "alert_bottom_l") { v ->
                    viewModel.updateAlerts { it.copy(bottomMixMaxLitres = v) }
                }
                NumberStepperRow("Deco Mix up to 49% O₂", a.decoMixUpTo49MaxLitres, 200.0, 200.0, 10000.0, "L", 0, testTag = "alert_deco49_l") { v ->
                    viewModel.updateAlerts { it.copy(decoMixUpTo49MaxLitres = v) }
                }
                NumberStepperRow("Deco Mix 50 to 99% O₂", a.decoMix50To99MaxLitres, 200.0, 200.0, 10000.0, "L", 0, testTag = "alert_deco99_l") { v ->
                    viewModel.updateAlerts { it.copy(decoMix50To99MaxLitres = v) }
                }
                NumberStepperRow("Deco Oxygen", a.decoO2MaxLitres, 200.0, 200.0, 10000.0, "L", 0, testTag = "alert_o2_l") { v ->
                    viewModel.updateAlerts { it.copy(decoO2MaxLitres = v) }
                }
            }
        }

        // 8. Saved Plans (Max 50 persisted locally)
        item {
            CollapsibleSection(
                title = "Saved Dive Plans (${state.savedPlans.size}/50)",
                subtitle = "Store and recall multi-dive configurations offline",
                testTag = "section_saved_plans"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = planNameInput,
                        onValueChange = { planNameInput = it },
                        placeholder = { Text("e.g. Blue Hole 60m Trimix") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).testTag("save_plan_name_input"),
                        shape = RoundedCornerShape(10.dp)
                    )
                    Button(
                        onClick = {
                            viewModel.saveCurrentPlan(planNameInput.ifBlank { "Dive Plan ${state.savedPlans.size + 1}" })
                            planNameInput = ""
                        },
                        modifier = Modifier.height(52.dp).testTag("save_plan_button"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save Plan")
                    }
                }
                if (state.savedPlans.isEmpty()) {
                    Text(
                        text = "No saved plans yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    state.savedPlans.forEach { saved ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(saved.name, style = MaterialTheme.typography.titleSmall)
                                    Text(saved.summaryLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { viewModel.loadSavedPlan(saved) }) { Text("Load") }
                                IconButton(onClick = { viewModel.deleteSavedPlan(saved.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete plan", tint = AlertCrimson)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 9. Safety Disclaimer Trigger
        item {
            OutlinedButton(
                onClick = onShowDisclaimer,
                modifier = Modifier.fillMaxWidth().height(48.dp).testTag("view_disclaimer_button"),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = NitroxAmber, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("View Full Technical Diving Safety Disclaimer")
            }
        }
    }
}
