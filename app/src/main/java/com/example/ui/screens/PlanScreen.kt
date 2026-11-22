package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.engine.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PlannerUiState
import com.example.ui.viewmodel.PlannerViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PlanScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    onSharePdf: () -> Unit,
    onShareCsv: () -> Unit,
    onNavigateToGases: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val result = state.activeResult
    val units = state.input.general.units
    val gasMode = state.input.general.gasSelectionMode

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Hero Scuba Visual Banner
        item {
            val isRecDive = gasMode == GasSelectionMode.BEST_GAS_MIX && state.bestMixRec?.isRecreational == true
            HeroPhotoBanner(
                imageRes = R.drawable.img_hero_planner,
                contentDescription = "Technical scuba diver over turquoise reef",
                badge = if (isRecDive) {
                    "REC · NDL · ${state.input.general.decoModel.shortLabel}"
                } else {
                    "${state.input.general.decoModel.shortLabel} · GF ${state.input.general.gfLow}/${state.input.general.gfHigh}"
                },
                title = if (isRecDive) "Recreational Dive Planner" else "Technical Dive Planner",
                subtitle = "${state.activeDive.label} · Max ${UnitsAndPressure.formatDepth(state.activeDive.levels.maxOfOrNull { it.depthM } ?: 40.0, units)} · ${if (result != null) "TTS ${result.ttsMin.roundToInt()}m · " else ""}${gasMode.label}",
                actionLabel = if (result?.hasErrors == true) "CHECK ERRORS" else if (result != null) "RT ${UnitsAndPressure.formatRuntimeMmSs(result.totalRuntimeMin)}" else null,
                onAction = {}
            )
        }

        // 1. Gas Mode Selector: "Best GAS Mix" vs "User input GAS"
        item {
            TelemetryCard(
                borderColor = if (gasMode == GasSelectionMode.BEST_GAS_MIX) OceanLightBlue.copy(alpha = 0.7f) else DiveOrange.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gas Mix Planning Mode",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (gasMode == GasSelectionMode.BEST_GAS_MIX) {
                                if (state.bestMixRec?.isRecreational == true) {
                                    "REC Dive (< 40m, within NDL) · Air / Nitrox (EAN32 default)"
                                } else {
                                    "Auto-computed technical gas plan for all dive levels"
                                }
                            } else {
                                "Using custom bottles configured in the Gases tab"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (result != null) {
                        PlanStatusPill(
                            hasErrors = result.hasErrors,
                            hasWarnings = result.hasWarnings,
                            alertCount = result.alerts.count { it.level != AlertLevel.INFO }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))

                // 2-option segmented buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val bestSelected = gasMode == GasSelectionMode.BEST_GAS_MIX
                    Button(
                        onClick = { viewModel.setGasSelectionMode(GasSelectionMode.BEST_GAS_MIX) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("mode_best_gas_mix"),
                        shape = RoundedCornerShape(10.dp),
                        colors = if (bestSelected) {
                            ButtonDefaults.buttonColors(containerColor = OceanLightBlue, contentColor = Color.White)
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = DeepNavyText)
                        },
                        border = if (!bestSelected) BorderStroke(1.dp, SkyBorder) else null
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text("Best GAS Mix", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Button(
                        onClick = { viewModel.setGasSelectionMode(GasSelectionMode.USER_INPUT_GAS) },
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("mode_user_input_gas"),
                        shape = RoundedCornerShape(10.dp),
                        colors = if (!bestSelected) {
                            ButtonDefaults.buttonColors(containerColor = DiveOrange, contentColor = Color.White)
                        } else {
                            ButtonDefaults.outlinedButtonColors(contentColor = DeepNavyText)
                        },
                        border = if (bestSelected) BorderStroke(1.dp, SkyBorder) else null
                    ) {
                        Text("User input GAS", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (gasMode == GasSelectionMode.BEST_GAS_MIX) {
                    state.bestMixRec?.let { rec ->
                        BestGasKitGrid(rec = rec, units = units)
                    }
                } else {
                    val activeGases = state.input.gases.filter { it.enabled }
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        activeGases.forEach { g -> GasBadge(g) }
                    }
                    Spacer(Modifier.height(6.dp))
                    TextButton(
                        onClick = onNavigateToGases,
                        modifier = Modifier.testTag("edit_user_gases_button"),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text("Edit custom bottles in Gases tab →", color = OceanLightBlue)
                    }
                }
            }
        }

        // 2. Multi-Dive Day Strip (Dive 1 -> SI -> Dive 2 -> + Add Dive)
        item {
            MultiDiveDayStrip(
                state = state,
                viewModel = viewModel
            )
        }

        // 3. Dive Profile Levels Editor (Multi-level + Surface Interval if Dive 2+)
        item {
            DiveProfileEditorCard(state = state, viewModel = viewModel)
        }

        // 4. Summary Telemetry Grid
        if (result != null) {
            item {
                SummaryTelemetryGrid(result = result, units = units, diveIndex = state.activeDiveIndex)
            }

            // 5. Visualized Dive Profile & Gas Switch Schedule (Color-blocked phases + inline mix tags)
            if (result.segments.isNotEmpty() && result.tissueTimeline.size >= 2) {
                item {
                    TelemetryCard(borderColor = OceanLightBlue.copy(alpha = 0.65f)) {
                        DiveProfileChart(result = result, units = units)
                    }
                }
            }

            // 6. Critical Planning Errors Only
            if (result.alerts.isNotEmpty()) {
                item {
                    AlertsCard(alerts = result.alerts)
                }
            }

            // 7. Runtime & Decompression Schedule Table
            item {
                RuntimeScheduleCard(
                    result = result,
                    units = units,
                    planStyle = state.input.general.planStyle,
                    onToggleStyle = { style -> viewModel.updateGeneral { it.copy(planStyle = style) } }
                )
            }

            // 8. Contingency & CCR Bailout Plans
            if (result.contingencies.isNotEmpty() || result.ccrBailout != null) {
                item {
                    ContingenciesCard(
                        contingencies = result.contingencies,
                        ccrBailout = result.ccrBailout,
                        units = units
                    )
                }
            }
        }

        // Permanent Compact Safety Note
        item {
            Text(
                text = stringResource(R.string.disclaimer_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp, horizontal = 4.dp)
            )
        }

        // Final Action at Lowest Bottom of Planner Tab: Export & Share Dive Plan Table
        item {
            ExportShareCard(
                onExportPdf = onExportPdf,
                onExportCsv = onExportCsv,
                onSharePdf = onSharePdf,
                onShareCsv = onShareCsv,
                testTagPrefix = "planner"
            )
        }
    }
}

@Composable
private fun MultiDiveDayStrip(
    state: PlannerUiState,
    viewModel: PlannerViewModel
) {
    TelemetryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Multi-Dive Day Planner",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val dayOtu = state.dayResult?.totalDailyOtu?.roundToInt() ?: 0
                Text(
                    text = "${state.dives.size} dive(s) · Residual N₂/He carried across dives · Daily OTU: $dayOtu / 850",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = { viewModel.addRepetitiveDive() },
                modifier = Modifier.testTag("add_repetitive_dive_button"),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Repetitive Dive", modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Dive", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            state.dives.forEachIndexed { idx, entry ->
                if (idx > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(12.dp), tint = NitroxAmber)
                            Text(
                                text = "SI ${entry.surfaceIntervalBeforeMin.roundToInt()}m",
                                style = MaterialTheme.typography.labelSmall,
                                color = NitroxAmber
                            )
                        }
                    }
                }
                val isSelected = idx == state.activeDiveIndex
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { viewModel.selectDive(idx) }
                        .testTag("dive_tab_$idx")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val depthStr = UnitsAndPressure.formatDepth(
                            entry.levels.maxOfOrNull { it.depthM } ?: 0.0,
                            state.input.general.units
                        )
                        Text(
                            text = "${entry.label} ($depthStr)",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        if (state.dives.size > 1) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove ${entry.label}",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { viewModel.removeDive(idx) },
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiveProfileEditorCard(
    state: PlannerUiState,
    viewModel: PlannerViewModel
) {
    val active = state.activeDive
    val units = state.input.general.units

    TelemetryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${active.label} Profile",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = { viewModel.addLevel() },
                modifier = Modifier.testTag("add_level_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add Level")
            }
        }

        if (state.activeDiveIndex > 0) {
            val prevCns = state.activeResult?.oxygen?.startCnsPct ?: 0.0
            NumberStepperRow(
                label = "Surface Interval Before ${active.label}",
                subtitle = "Decayed CNS at start: ${prevCns.roundToInt()}% · Residual N₂/He active",
                value = active.surfaceIntervalBeforeMin,
                step = 15.0,
                min = 10.0,
                max = 1440.0,
                unit = "min",
                testTag = "surface_interval",
                onValueChange = { viewModel.updateSurfaceInterval(state.activeDiveIndex, it) }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        }

        active.levels.forEachIndexed { idx, lvl ->
            val dispDepth = UnitsAndPressure.depthDisplay(lvl.depthM, units)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (active.levels.size == 1) "Bottom Segment" else "Level ${idx + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (active.levels.size > 1) {
                        TextButton(onClick = { viewModel.removeLevel(lvl.id) }) {
                            Text("Remove", color = AlertCrimson, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                NumberStepperRow(
                    label = "Target Depth",
                    value = dispDepth,
                    step = if (units == UnitSystem.IMPERIAL) 10.0 else 3.0,
                    min = if (units == UnitSystem.IMPERIAL) 10.0 else 3.0,
                    max = if (units == UnitSystem.IMPERIAL) 600.0 else 180.0,
                    unit = units.depthUnit,
                    testTag = "level_${idx}_depth",
                    onValueChange = { newDisp ->
                        viewModel.updateLevel(
                            lvl.id,
                            depthM = UnitsAndPressure.depthFromDisplay(newDisp, units)
                        )
                    }
                )
                NumberStepperRow(
                    label = if (idx == 0 && state.input.general.btIncludesDescent) "Bottom Time (incl. descent)" else "Segment Time",
                    value = lvl.timeMin,
                    step = 5.0,
                    min = 1.0,
                    max = 240.0,
                    unit = "min",
                    testTag = "level_${idx}_time",
                    onValueChange = { newTime -> viewModel.updateLevel(lvl.id, timeMin = newTime) }
                )
            }
            if (idx < active.levels.lastIndex) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryTelemetryGrid(
    result: DivePlanResult,
    units: UnitSystem,
    diveIndex: Int
) {
    val items = listOf(
        Triple("MAX DEPTH", UnitsAndPressure.formatDepth(result.maxDepthM, units), CyanTelemetry),
        Triple("RUNTIME", UnitsAndPressure.formatRuntimeMmSs(result.totalRuntimeMin), MaterialTheme.colorScheme.onSurface),
        Triple("DECO TIME", "${result.totalDecoMin.roundToInt()} min", if (result.totalDecoMin > 0) NitroxAmber else DecoEmerald),
        Triple("TTS (TO SURFACE)", "${UnitsAndPressure.formatRuntimeMmSs(result.ttsMin)} (${result.ttsMin.roundToInt()}m)", CyanTelemetry),
        Triple("1ST STOP", if (result.firstStopM > 0) UnitsAndPressure.formatDepth(result.firstStopM.toDouble(), units) else "NDL ${result.ndlMin.roundToInt()}m", DecoEmerald),
        Triple("SURF GF", "${result.endSurfaceGfPct.roundToInt()}%", if (result.endSurfaceGfPct > 90) AlertCrimson else CyanTelemetry),
        Triple(
            "CNS%",
            if (diveIndex > 0) "${result.oxygen.startCnsPct.roundToInt()}→${result.oxygen.endCnsPct.roundToInt()}%" else "${result.oxygen.endCnsPct.roundToInt()}%",
            when {
                result.oxygen.endCnsPct > result.input.alerts.cnsErrorPct -> AlertCrimson
                result.oxygen.endCnsPct > result.input.alerts.cnsWarnPct -> NitroxAmber
                else -> DecoEmerald
            }
        ),
        Triple(
            "OTU (DAY)",
            "${result.oxygen.diveOtu.roundToInt()} (${result.oxygen.dailyOtu.roundToInt()})",
            if (result.oxygen.dailyOtu > 850 || result.oxygen.diveOtu > result.input.alerts.otuWarnPerDive) NitroxAmber else DecoEmerald
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pair.forEach { (label, value, accent) ->
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = value,
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
                                color = accent,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<PlanAlert>) {
    val hasErr = alerts.any { it.level == AlertLevel.ERROR }
    TelemetryCard(
        borderColor = if (hasErr) AlertCrimson.copy(alpha = 0.8f) else NitroxAmber.copy(alpha = 0.7f)
    ) {
        Text(
            text = "Critical Planning Errors (${alerts.size})",
            style = MaterialTheme.typography.titleMedium,
            color = if (hasErr) AlertCrimson else NitroxAmber
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            alerts.forEach { alert ->
                val bg = when (alert.level) {
                    AlertLevel.ERROR -> AlertCrimson.copy(alpha = 0.14f)
                    AlertLevel.WARNING -> NitroxAmber.copy(alpha = 0.14f)
                    AlertLevel.INFO -> CyanTelemetry.copy(alpha = 0.12f)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AlertLevelIcon(alert.level, modifier = Modifier.size(18.dp).padding(top = 1.dp))
                    Text(
                        text = alert.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeScheduleCard(
    result: DivePlanResult,
    units: UnitSystem,
    planStyle: PlanStyle,
    onToggleStyle: (PlanStyle) -> Unit
) {
    val rows = remember(result, planStyle) {
        if (planStyle == PlanStyle.COMPACT) {
            result.segments.filter {
                it.type == SegmentType.LEVEL || it.type == SegmentType.STOP ||
                    it.type == SegmentType.SWITCH || it.type == SegmentType.BREAK
            }
        } else {
            result.segments
        }
    }

    TelemetryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Runtime & Deco Table",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Total RT ${UnitsAndPressure.formatRuntimeMmSs(result.totalRuntimeMin)} · TTS (Total Time to Surface) ${UnitsAndPressure.formatRuntimeMmSs(result.ttsMin)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlanStyle.entries.forEach { st ->
                    FilterChip(
                        selected = planStyle == st,
                        onClick = { onToggleStyle(st) },
                        label = { Text(if (st == PlanStyle.DETAILED) "Detailed" else "Stops", style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Column(modifier = Modifier.width(716.dp)) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    TableHeaderCell("ACTION", 74.dp)
                    TableHeaderCell("DEPTH", 68.dp)
                    TableHeaderCell("TIME", 54.dp)
                    TableHeaderCell("RUN", 56.dp)
                    TableHeaderCell("TTS", 56.dp)
                    TableHeaderCell("MIX", 86.dp)
                    TableHeaderCell("ppO2", 52.dp)
                    TableHeaderCell("END", 56.dp)
                    TableHeaderCell("DENS", 56.dp)
                    TableHeaderCell("CEIL", 56.dp)
                    TableHeaderCell("GF99", 52.dp)
                }
                Spacer(Modifier.height(4.dp))

                rows.forEachIndexed { idx, seg ->
                    val isStop = seg.type == SegmentType.STOP
                    val isSwitch = seg.type == SegmentType.SWITCH || (idx > 0 && rows[idx - 1].gas.id != seg.gas.id)
                    if (isSwitch && idx > 0) {
                        HorizontalDivider(color = gasAccentColor(seg.gas).copy(alpha = 0.6f), thickness = 1.dp)
                    }
                    val rowBg = when {
                        isStop -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                        seg.type == SegmentType.BREAK -> NitroxAmber.copy(alpha = 0.14f)
                        idx % 2 == 1 -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        else -> Color.Transparent
                    }
                    val depthStr = if (seg.fromDepthM == seg.toDepthM) {
                        UnitsAndPressure.formatDepth(seg.toDepthM, units)
                    } else {
                        "${UnitsAndPressure.depthDisplay(seg.fromDepthM, units).roundToInt()}→${UnitsAndPressure.depthDisplay(seg.toDepthM, units).roundToInt()}"
                    }
                    val segTtsMin = (result.totalRuntimeMin - seg.runtimeMin).coerceAtLeast(0.0)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(rowBg)
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TableCell(seg.type.label, 74.dp, bold = isStop || seg.type == SegmentType.SWITCH)
                        TableCell(depthStr, 68.dp, bold = isStop)
                        TableCell(UnitsAndPressure.formatRuntimeMmSs(seg.durationMin), 54.dp, bold = isStop)
                        TableCell(UnitsAndPressure.formatRuntimeMmSs(seg.runtimeMin), 56.dp, bold = isStop, color = MaterialTheme.colorScheme.primary)
                        TableCell(UnitsAndPressure.formatRuntimeMmSs(segTtsMin), 56.dp, bold = isStop || seg.type == SegmentType.LEVEL, color = OceanLightBlue)
                        Box(modifier = Modifier.width(86.dp)) { GasBadge(seg.gas) }
                        TableCell(
                            String.format(Locale.US, "%.2f", seg.ppO2End),
                            52.dp,
                            color = if (seg.ppO2End > 1.62 || seg.ppO2End < 0.18) AlertCrimson else MaterialTheme.colorScheme.onSurface
                        )
                        TableCell(UnitsAndPressure.formatDepth(seg.endM, units), 56.dp)
                        TableCell(
                            String.format(Locale.US, "%.1f", seg.densityGL),
                            56.dp,
                            color = if (seg.densityGL > 6.5) AlertCrimson else MaterialTheme.colorScheme.onSurface
                        )
                        TableCell(UnitsAndPressure.formatDepth(seg.ceilingM, units), 56.dp)
                        TableCell("${seg.gf99Pct.roundToInt()}%", 52.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun TableHeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(width)
    )
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    bold: Boolean = false,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp
        ),
        color = color,
        modifier = Modifier.width(width),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ContingenciesCard(
    contingencies: List<ContingencySummary>,
    ccrBailout: ContingencySummary?,
    units: UnitSystem
) {
    CollapsibleSection(
        title = "Contingency & Bailout Plans",
        subtitle = "Deeper +3m, Longer +5m, Lost Deco Gas" + if (ccrBailout != null) ", CCR Bailout" else "",
        badgeText = "${contingencies.size + (if (ccrBailout != null) 1 else 0)}",
        initiallyExpanded = false,
        testTag = "contingencies_section"
    ) {
        val all = buildList {
            ccrBailout?.let { add(it) }
            addAll(contingencies)
        }
        all.forEach { c ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                border = BorderStroke(1.dp, if (c.hasErrors) AlertCrimson.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            text = "RT ${UnitsAndPressure.formatRuntimeMmSs(c.totalRuntimeMin)} · Deco ${c.totalDecoMin.roundToInt()}m · TTS ${c.ttsMin.roundToInt()}m",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        text = c.subtitle + " · CNS ${c.cnsPct.roundToInt()}% · OTU ${c.otu.roundToInt()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (c.stops.isNotEmpty()) {
                        val stopLine = c.stops.joinToString("  ") {
                            "${UnitsAndPressure.depthDisplay(it.depthM.toDouble(), units).roundToInt()}${units.depthUnit}:${it.durationMin.roundToInt()}m"
                        }
                        Text(
                            text = "Stops: $stopLine",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
