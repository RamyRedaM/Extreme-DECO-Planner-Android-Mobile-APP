package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.viewmodel.PlannerUiState
import com.example.ui.viewmodel.PlannerViewModel
import java.util.Locale
import kotlin.math.roundToInt

private data class QuickMixPreset(val label: String, val o2: Int, val he: Int, val role: GasRole)

private val QUICK_MIXES = listOf(
    QuickMixPreset("Air", 21, 0, GasRole.BOTTOM),
    QuickMixPreset("EAN32", 32, 0, GasRole.BOTTOM),
    QuickMixPreset("TX 21/35", 21, 35, GasRole.BOTTOM),
    QuickMixPreset("TX 18/45", 18, 45, GasRole.BOTTOM),
    QuickMixPreset("TX 15/55", 15, 55, GasRole.BOTTOM),
    QuickMixPreset("TX 12/65", 12, 65, GasRole.BOTTOM),
    QuickMixPreset("TX 10/70", 10, 70, GasRole.BOTTOM),
    QuickMixPreset("EAN50", 50, 0, GasRole.DECO),
    QuickMixPreset("EAN80", 80, 0, GasRole.DECO),
    QuickMixPreset("O2 100%", 100, 0, GasRole.DECO)
)

@Composable
fun GasesScreen(
    state: PlannerUiState,
    viewModel: PlannerViewModel,
    modifier: Modifier = Modifier
) {
    val units = state.input.general.units
    val env = remember(state.input.general) { UnitsAndPressure.buildEnvironment(state.input.general) }
    val maxDepthM = state.activeDive.levels.maxOfOrNull { it.depthM } ?: 40.0

    val bottomTravelGases = remember(state.input.gases) {
        state.input.gases.filter {
            it.role == GasRole.BOTTOM || it.role == GasRole.TRAVEL || it.role == GasRole.DILUENT
        }
    }
    val decoBailoutGases = remember(state.input.gases) {
        state.input.gases.filter {
            it.role == GasRole.DECO || it.role == GasRole.BAILOUT
        }
    }
    val isBestMode = state.input.general.gasSelectionMode == GasSelectionMode.BEST_GAS_MIX

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 840.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 0. Hero Scuba Cylinders Photo Banner
        item {
            HeroPhotoBanner(
                imageRes = R.drawable.img_deco_tanks,
                contentDescription = "Technical scuba gas cylinders on sunlit dive deck",
                badge = if (isBestMode && state.bestMixRec?.isRecreational == true) "REC · Best GAS Mix" else state.input.general.gasSelectionMode.label,
                title = "Gas Blending & Cylinder Kit",
                subtitle = if (isBestMode && state.bestMixRec?.isRecreational == true) {
                    "REC Dive (< 40m, Within NDL) · ${state.bestMixRec?.bottomGas?.displayName ?: "EAN32"} · Air / Nitrox only"
                } else {
                    "100% O₂ @ 6m · EAN50 @ 21m · Depth Travel & MOD Bottom Mixes"
                }
            )
        }

        // 1. Compact Gas Mode Status (Full Best Mix Kit summary lives in Planner tab)
        item {
            TelemetryCard(borderColor = if (isBestMode) OceanLightBlue.copy(alpha = 0.65f) else DiveOrange.copy(alpha = 0.65f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isBestMode) "Best GAS Mix Active (${UnitsAndPressure.formatDepth(maxDepthM, units)})" else "User Input GAS Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isBestMode) OceanLightBlue else DiveOrange
                        )
                        Text(
                            text = if (isBestMode) {
                                "12L default cylinders auto-scaled to your SAC rate. Adjust tank size/pressure below or edit O₂/He for custom blends."
                            } else {
                                "Custom gas blends & tank counts active. Switch back anytime to auto-optimize mixes."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!isBestMode) {
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.applyBestMixRecommendation() },
                            modifier = Modifier.testTag("apply_best_mix_button"),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Use Best Mix")
                        }
                    }
                }
            }
        }

        // 2. Quick-Add Standard Gas Chips
        item {
            TelemetryCard {
                Text(
                    text = "Quick-Add Technical Gases",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    QUICK_MIXES.forEach { preset ->
                        AssistChip(
                            onClick = { viewModel.addQuickGas(preset.o2, preset.he, preset.role) },
                            label = { Text("+ ${preset.label}", style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.testTag("quick_add_${preset.label.lowercase().replace(" ", "_")}")
                        )
                    }
                }
            }
        }

        // 3. Travel / Bottom / Diluent Mixes
        item {
            Text(
                text = "Travel / Bottom / Diluent Gases (${bottomTravelGases.size}/10)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        items(bottomTravelGases.size, key = { bottomTravelGases[it].id }) { idx ->
            val gas = bottomTravelGases[idx]
            GasEditorCard(
                gas = gas,
                maxDepthM = maxDepthM,
                env = env,
                dive = state.input.dive,
                units = units,
                canDelete = state.input.gases.size > 1,
                onUpdate = { viewModel.updateGas(it) },
                onDelete = { viewModel.removeGas(gas.id) }
            )
        }

        // 4. Deco / Bailout Mixes
        item {
            Text(
                text = "Deco / Bailout Gases (${decoBailoutGases.size}/6)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (decoBailoutGases.isEmpty()) {
            item {
                TelemetryCard {
                    Text(
                        text = "No decompression gases configured. Tap EAN50 or O2 100% above to accelerate off-gassing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(decoBailoutGases.size, key = { decoBailoutGases[it].id }) { idx ->
                val gas = decoBailoutGases[idx]
                GasEditorCard(
                    gas = gas,
                    maxDepthM = maxDepthM,
                    env = env,
                    dive = state.input.dive,
                    units = units,
                    canDelete = true,
                    onUpdate = { viewModel.updateGas(it) },
                    onDelete = { viewModel.removeGas(gas.id) }
                )
            }
        }

    }
}

@Composable
private fun GasEditorCard(
    gas: Gas,
    maxDepthM: Double,
    env: Environment,
    dive: DiveSettings,
    units: UnitSystem,
    canDelete: Boolean,
    onUpdate: (Gas) -> Unit,
    onDelete: () -> Unit
) {
    val evalDepth = if (gas.role == GasRole.DECO || gas.role == GasRole.BAILOUT) {
        GasCalculator.effectiveSwitchModM(gas, dive.ppO2Deco, env)
    } else {
        maxDepthM
    }
    val metrics = GasCalculator.gasMetrics(gas, evalDepth, env, dive)

    TelemetryCard(
        borderColor = if (gas.enabled) gasAccentColor(gas).copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = gas.enabled,
                    onCheckedChange = { onUpdate(gas.copy(enabled = it)) }
                )
                GasBadge(gas)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = gas.role.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove ${gas.displayName}", tint = AlertCrimson)
                }
            }
        }

        // Live Gas Telemetry Metrics Line
        Text(
            text = "N₂ ${gas.n2Pct}% · MOD ${UnitsAndPressure.formatDepth(metrics.modM, units)} · " +
                "Min ${UnitsAndPressure.formatDepth(metrics.minDepthM, units)} · " +
                "END ${UnitsAndPressure.formatDepth(metrics.endM, units, 1)} · " +
                "${"%.2f".format(Locale.US, metrics.densityGL)} g/L",
            style = MaterialTheme.typography.labelSmall,
            color = if (metrics.densityGL > 6.2) AlertCrimson else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )

        // O2 & He Steppers
        NumberStepperRow(
            label = "Oxygen (O₂)",
            value = gas.o2Pct.toDouble(),
            step = 1.0,
            min = 8.0,
            max = 100.0,
            unit = "%",
            testTag = "gas_${gas.id}_o2",
            onValueChange = { newO2 ->
                val o2Int = newO2.roundToInt().coerceIn(8, 100)
                val clampedHe = gas.hePct.coerceAtMost(100 - o2Int)
                onUpdate(gas.copy(o2 = o2Int / 100.0, he = clampedHe / 100.0))
            }
        )
        NumberStepperRow(
            label = "Helium (He)",
            value = gas.hePct.toDouble(),
            step = 5.0,
            min = 0.0,
            max = (100 - gas.o2Pct).toDouble(),
            unit = "%",
            testTag = "gas_${gas.id}_he",
            onValueChange = { newHe ->
                val heInt = newHe.roundToInt().coerceIn(0, 100 - gas.o2Pct)
                onUpdate(gas.copy(he = heInt / 100.0))
            }
        )

        // Cylinder Setup Row
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
        Text(
            text = "Cylinder (${gas.cylinder.count}×${gas.cylinder.waterVolumeL.roundToInt()}L @ ${gas.cylinder.fillPressureBar.roundToInt()} bar = ${UnitsAndPressure.formatVolume(gas.cylinder.availableLitres, units)})",
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface
        )
        NumberStepperRow(
            label = "Number of Tanks",
            value = gas.cylinder.count.toDouble(),
            step = 1.0,
            min = 1.0,
            max = 12.0,
            unit = "×",
            testTag = "gas_${gas.id}_count",
            onValueChange = { c -> onUpdate(gas.copy(cylinder = gas.cylinder.copy(count = c.roundToInt().coerceIn(1, 12)))) }
        )
        NumberStepperRow(
            label = "Tank Size",
            value = gas.cylinder.waterVolumeL,
            step = 1.0,
            min = 3.0,
            max = 20.0,
            unit = "L",
            testTag = "gas_${gas.id}_vol",
            onValueChange = { v -> onUpdate(gas.copy(cylinder = gas.cylinder.copy(waterVolumeL = v))) }
        )
        NumberStepperRow(
            label = "Fill Pressure",
            value = gas.cylinder.fillPressureBar,
            step = 10.0,
            min = 50.0,
            max = 300.0,
            unit = "bar",
            testTag = "gas_${gas.id}_bar",
            onValueChange = { p -> onUpdate(gas.copy(cylinder = gas.cylinder.copy(fillPressureBar = p))) }
        )
    }
}
