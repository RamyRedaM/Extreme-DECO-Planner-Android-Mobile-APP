package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.UnitsAndPressure
import com.example.ui.components.*
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.DecoEmerald
import com.example.ui.theme.NitroxAmber
import com.example.ui.viewmodel.PlannerUiState
import com.example.ui.viewmodel.PlannerViewModel
import kotlin.math.roundToInt

private enum class ChartFilter(val label: String) {
    ALL("All Charts"),
    TISSUES("16 Tissues"),
    GAS_USE("Gas & PP"),
    M_VALUE("M-Value")
}

@Composable
fun ChartsScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    modifier: Modifier = Modifier
) {
    val result = state.activeResult
    val units = state.input.general.units
    var selectedFilter by remember { mutableStateOf(ChartFilter.ALL) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Underwater Charts Hero Photo Banner
        item {
            HeroPhotoBanner(
                imageRes = R.drawable.img_charts_reef,
                contentDescription = "Sunlit ocean ascent with rising scuba bubbles",
                badge = "TELEMETRY ANALYTICS",
                title = "Decompression & Tissue Charts",
                subtitle = "16-compartment tissue saturation, inert gas tension, gas consumption & M-value envelope"
            )
        }
        // Dive Selector + Chart Filter Bar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.dives.size > 1) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.dives.forEachIndexed { idx, entry ->
                            FilterChip(
                                selected = idx == state.activeDiveIndex,
                                onClick = { viewModel.selectDive(idx) },
                                label = {
                                    Text(
                                        text = if (idx == 0) entry.label else "${entry.label} (SI ${entry.surfaceIntervalBeforeMin.roundToInt()}m)",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChartFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("chart_filter_${filter.name.lowercase()}")
                        )
                    }
                }
            }
        }

        if (result == null || result.tissueTimeline.size < 2) {
            item {
                TelemetryCard {
                    Text(
                        text = "Enter a valid dive profile on the Plan tab to render telemetry charts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@LazyColumn
        }

        // 1. 16-Compartment Tissue Saturation & Loading Levels (Interactive Scrubber)
        if (selectedFilter == ChartFilter.ALL || selectedFilter == ChartFilter.TISSUES) {
            item {
                TelemetryCard {
                    Text(
                        text = "16-Compartment Tissue Gas Loading & Saturation",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Inspect Nitrogen + Helium tensions and % surface M-value saturation for every tissue group C1–C16 at any moment of ${state.activeDive.label}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TissueCompartmentBarsChart(result = result)
                }
            }

            // 3. 16-Line Tissue Tension Timeline
            item {
                TelemetryCard {
                    Text(
                        text = "Compartment Inert Gas Tension Timeline",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "All 16 Bühlmann compartments (pN₂ + pHe in bar) plotted against ambient pressure.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    TissueTensionTimelineChart(result = result)
                }
            }
        }

        // 4. Gas Consumption + Partial Pressures
        if (selectedFilter == ChartFilter.ALL || selectedFilter == ChartFilter.GAS_USE) {
            item {
                TelemetryCard {
                    Text(
                        text = "Gas Consumption vs Cylinder Capacity",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Used volume + reserve requirement compared against cylinder available volume.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    GasConsumptionBarChart(consumption = result.gasConsumption, units = units)
                }
            }

            item {
                TelemetryCard {
                    Text(
                        text = "Gas Partial Pressures (ppO₂, ppN₂, ppHe)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Real-time inspired partial pressures with bottom/deco ppO₂ and narcosis ppN₂ limit lines.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    GasPartialPressuresChart(result = result)
                }
            }
        }

        // 5. M-Value & Gradient Factor Learning Diagram
        if (selectedFilter == ChartFilter.ALL || selectedFilter == ChartFilter.M_VALUE) {
            item {
                TelemetryCard {
                    Text(
                        text = "M-Value & Gradient Factor Envelope",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "P_amb vs P_tissue trajectory showing pure Bühlmann M-value line and active GF ${state.input.general.gfLow}/${state.input.general.gfHigh} envelope.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    MValueDiagramChart(result = result)
                }
            }
        }

        // 6. Multi-Dive Day Progression Card (when > 1 dive planned)
        val dayRes = state.dayResult
        if (dayRes != null && dayRes.diveResults.size > 1) {
            item {
                TelemetryCard {
                    Text(
                        text = "Multi-Dive Day Residual Nitrogen & O₂ Progression",
                        style = MaterialTheme.typography.titleMedium,
                        color = CyanTelemetry
                    )
                    Spacer(Modifier.height(8.dp))
                    dayRes.diveResults.forEachIndexed { idx, dr ->
                        val si = dayRes.surfaceIntervalsMin.getOrElse(idx) { 0.0 }
                        val startMaxN2 = dr.startTissues.compartments.maxOf { it.pN2 }
                        val endMaxN2 = dr.endTissues.compartments.maxOf { it.pN2 }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Dive ${idx + 1} (${UnitsAndPressure.formatDepth(dr.maxDepthM, units)} · RT ${UnitsAndPressure.formatRuntimeMmSs(dr.totalRuntimeMin)})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (idx == 0) "Clean surface tissues (Start N₂ ${"%.2f".format(startMaxN2)}b → End ${"%.2f".format(endMaxN2)}b)"
                                    else "After ${si.roundToInt()}m SI · Start peak N₂ ${"%.2f".format(startMaxN2)}b → End ${"%.2f".format(endMaxN2)}b",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Column {
                                Text(
                                    text = "Deco ${dr.totalDecoMin.roundToInt()}m",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = NitroxAmber
                                )
                                Text(
                                    text = "CNS ${dr.oxygen.endCnsPct.roundToInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = DecoEmerald
                                )
                            }
                        }
                        if (idx < dayRes.diveResults.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}
