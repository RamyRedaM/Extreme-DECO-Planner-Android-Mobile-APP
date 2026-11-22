package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

private val SI_PRESETS_MIN = listOf(30, 60, 90, 120, 180, 240, 360, 720)

/**
 * Interactive Surface Interval Calculator in the Planner TAB.
 * Estimates remaining inert gas levels across all 16 Bühlmann ZHL-16C compartments,
 * leading compartment saturation, Surface GF & CNS decay, and next-dive NDL recovery.
 */
@Composable
fun SurfaceIntervalCalculatorCard(
    dayResult: MultiDiveDayResult,
    dives: List<MultiDiveEntry>,
    activeDiveIndex: Int,
    general: GeneralSettings,
    units: UnitSystem,
    onApplySurfaceInterval: (diveIndex: Int, minutes: Double) -> Unit,
    onAddDiveWithInterval: (minutes: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // Source dive for off-gassing:
    // - If viewing Dive 2+, default to off-gassing from Dive (N-1) into Dive N.
    // - If viewing Dive 1, estimate off-gassing after Dive 1 before a sequential dive.
    val hasPrevDive = activeDiveIndex > 0 && (activeDiveIndex - 1) in dayResult.diveResults.indices
    val hasNextDive = (activeDiveIndex + 1) in dives.indices

    val sourceDiveIdx = if (hasPrevDive) activeDiveIndex - 1 else activeDiveIndex
    val sourceResult = dayResult.diveResults.getOrNull(sourceDiveIdx) ?: return
    val targetApplyDiveIdx: Int? = when {
        hasPrevDive -> activeDiveIndex
        hasNextDive -> activeDiveIndex + 1
        else -> null
    }
    val initialSiMin = targetApplyDiveIdx?.let { dives.getOrNull(it)?.surfaceIntervalBeforeMin }
        ?.takeIf { it > 0.0 } ?: 90.0

    val defaultNextDepthM = when {
        hasPrevDive -> dives.getOrNull(activeDiveIndex)?.levels?.maxOfOrNull { it.depthM }
        hasNextDive -> dives.getOrNull(activeDiveIndex + 1)?.levels?.maxOfOrNull { it.depthM }
        else -> (sourceResult.maxDepthM - 6.0).coerceAtLeast(18.0)
    } ?: 24.0

    var siMinutes by remember(sourceDiveIdx, targetApplyDiveIdx, initialSiMin) {
        mutableStateOf(initialSiMin.coerceIn(0.0, 720.0))
    }
    var nextDepthM by remember(sourceDiveIdx, defaultNextDepthM) {
        mutableStateOf(defaultNextDepthM.coerceIn(9.0, 60.0))
    }

    // Next dive gas: the NEXT dive's own planned bottom gas (not the current dive's), or a picked gas
    val plannedNextGases: List<Gas>? = targetApplyDiveIdx?.let { dayResult.diveResults.getOrNull(it)?.input?.gases }
    var gasChoice by remember(sourceDiveIdx, targetApplyDiveIdx) { mutableStateOf("planned") }
    val nextGas = remember(plannedNextGases, nextDepthM, gasChoice, sourceResult.input.dive, sourceResult.env) {
        when (gasChoice) {
            "air" -> Gas.fromPercent("si_air", 21, 0, GasRole.BOTTOM)
            "ean32" -> Gas.fromPercent("si_ean32", 32, 0, GasRole.BOTTOM)
            else -> GasCalculator.defaultNextDiveGas(plannedNextGases, nextDepthM, sourceResult.input.dive, sourceResult.env)
        }
    }

    val estimate = remember(sourceResult.endTissues, sourceResult.oxygen.endCnsPct, siMinutes, nextDepthM, nextGas, general) {
        TissuesAndBuhlmann.estimateSurfaceInterval(
            endTissues = sourceResult.endTissues,
            endCnsPct = sourceResult.oxygen.endCnsPct,
            surfaceIntervalMin = siMinutes,
            env = sourceResult.env,
            nextDiveDepthM = nextDepthM,
            nextDiveGas = nextGas,
            general = general
        )
    }

    val sourceLabel = dives.getOrNull(sourceDiveIdx)?.label ?: "Dive ${sourceDiveIdx + 1}"
    val targetLabel = targetApplyDiveIdx?.let { dives.getOrNull(it)?.label } ?: "Next Dive"

    TelemetryCard(
        modifier = modifier.testTag("surface_interval_calculator_card"),
        borderColor = OceanLightBlue.copy(alpha = 0.65f)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = OceanLightBlue.copy(alpha = 0.14f)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Timer,
                        contentDescription = "Surface Interval Calculator",
                        tint = OceanLightBlue,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
                Column {
                    Text(
                        text = "Surface Interval Calculator",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Bühlmann ZHL-16C · $sourceLabel → $targetLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = OceanLightBlue.copy(alpha = 0.14f),
                border = BorderStroke(1.dp, OceanLightBlue.copy(alpha = 0.45f))
            ) {
                Text(
                    text = formatSiDuration(siMinutes),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = OceanLightBlue,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Interactive Slider (0 min .. 720 min / 12h)
        Slider(
            value = siMinutes.toFloat().coerceIn(0f, 720f),
            onValueChange = { raw -> siMinutes = (raw / 5f).roundToInt() * 5.0 },
            valueRange = 0f..720f,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("si_calculator_slider")
        )

        // Quick Preset Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SI_PRESETS_MIN.forEach { preset ->
                val selected = kotlin.math.abs(siMinutes - preset) < 2.5
                FilterChip(
                    selected = selected,
                    onClick = { siMinutes = preset.toDouble() },
                    label = {
                        Text(
                            text = if (preset >= 60 && preset % 60 == 0) "${preset / 60}h" else "${preset}m",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier.testTag("si_preset_${preset}m")
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 2x2 Bühlmann ZHL-16C Remaining Inert Gas Telemetry Grid
        val inertAccent = when {
            estimate.remainingExcessInertPct > 60.0 -> DiveOrange
            estimate.remainingExcessInertPct > 25.0 -> OceanLightBlue
            else -> DecoGreen
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SiMetricBox(
                label = "REMAINING INERT GAS",
                value = "${estimate.remainingExcessInertPct.roundToInt()}%",
                sub = "N₂ ${String.format(Locale.US, "%.2f", estimate.residualN2PeakBar)}b" +
                    if (estimate.residualHePeakBar > 0.01) " · He ${String.format(Locale.US, "%.2f", estimate.residualHePeakBar)}b" else " (ambient ${String.format(Locale.US, "%.2f", estimate.ambientN2Bar)}b)",
                accent = inertAccent,
                modifier = Modifier.weight(1f)
            )
            SiMetricBox(
                label = "LEADING COMP (ZHL-16C)",
                value = "C${estimate.leadingCompartmentNumber} · ${estimate.leadingSaturationAfterPct.roundToInt()}%",
                sub = "HT ${estimate.leadingN2HalfTimeMin.roundToInt()}m · was ${estimate.leadingSaturationBeforePct.roundToInt()}%",
                accent = TrimixViolet,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SiMetricBox(
                label = "SURFACE GF & CNS",
                value = "GF ${estimate.surfaceGfAfterPct.roundToInt()}%",
                sub = "from ${estimate.surfaceGfBeforePct.roundToInt()}% · CNS ${estimate.cnsBeforePct.roundToInt()}→${estimate.cnsAfterPct.roundToInt()}%",
                accent = if (estimate.surfaceGfAfterPct > 50.0) DiveOrange else DecoGreen,
                modifier = Modifier.weight(1f)
            )
            SiMetricBox(
                label = "NEXT NDL @ ${UnitsAndPressure.formatDepth(nextDepthM, units)}",
                value = if (estimate.nextDiveNdlMin >= 999.0) ">999m" else "${estimate.nextDiveNdlMin.roundToInt()} min",
                sub = "Fresh ${if (estimate.freshNdlMin >= 999.0) ">999m" else "${estimate.freshNdlMin.roundToInt()}m"} on ${nextGas.displayName}",
                accent = OceanLightBlue,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(10.dp))

        // 16-Compartment ZHL-16C Off-Gassing Mini Bar Chart (Surfacing vs Remaining After SI)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "16-Compartment Inert Gas Off-Gassing (C1–C16)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "<10% excess ≈ ${formatSiDuration(estimate.estimatedCleanTimeMin)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                val outlineCol = MaterialTheme.colorScheme.outline
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                ) {
                    val count = 16
                    val gap = 3.dp.toPx()
                    val barW = ((size.width - gap * (count - 1)) / count).coerceAtLeast(4f)
                    val maxPct = maxOf(
                        100.0,
                        estimate.compartmentLoadsBeforePct.maxOrNull() ?: 100.0
                    ).toFloat()

                    // Ambient reference line (~35-45% depending on compartment; show 50% & 100% M-value guide)
                    val y50 = size.height * (1f - 50f / maxPct)
                    drawLine(
                        color = outlineCol.copy(alpha = 0.55f),
                        start = Offset(0f, y50),
                        end = Offset(size.width, y50),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )

                    for (i in 0 until count) {
                        val x = i * (barW + gap)
                        val beforePct = estimate.compartmentLoadsBeforePct[i].toFloat().coerceIn(0f, maxPct)
                        val afterPct = estimate.compartmentLoadsAfterPct[i].toFloat().coerceIn(0f, maxPct)

                        val hBefore = (beforePct / maxPct) * size.height
                        val hAfter = (afterPct / maxPct) * size.height

                        // Ghost bar = surfacing inert gas load
                        drawRoundRect(
                            color = OceanLightBlue.copy(alpha = 0.22f),
                            topLeft = Offset(x, size.height - hBefore),
                            size = Size(barW, hBefore),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                        // Foreground bar = remaining load after SI
                        val barColor = when {
                            afterPct >= 80f -> SeverityCriticalRed
                            afterPct >= 60f -> DiveOrange
                            afterPct >= 45f -> OceanLightBlue
                            else -> DecoGreen
                        }
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset(x, size.height - hAfter),
                            size = Size(barW, hAfter),
                            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("C1 (4m fast)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Faded = Surfacing · Solid = Remaining", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("C16 (635m slow)", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // Next dive gas picker
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Next Gas:",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val plannedLabel = if (plannedNextGases != null) {
                "Planned · ${GasCalculator.defaultNextDiveGas(plannedNextGases, nextDepthM, sourceResult.input.dive, sourceResult.env).displayName}"
            } else {
                "Auto · ${GasCalculator.defaultNextDiveGas(null, nextDepthM, sourceResult.input.dive, sourceResult.env).displayName}"
            }
            listOf("planned" to plannedLabel, "air" to "Air", "ean32" to "EAN32").forEach { (key, label) ->
                FilterChip(
                    selected = gasChoice == key,
                    onClick = { gasChoice = key },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.testTag("si_gas_$key")
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        // Next Dive Target Depth Adjuster + Apply / Add Dive Action
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "Next Depth:",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = { nextDepthM = (nextDepthM - 3.0).coerceAtLeast(9.0) },
                    modifier = Modifier.testTag("si_next_depth_minus")
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Decrease next dive depth", modifier = Modifier.size(18.dp))
                }
                Text(
                    text = UnitsAndPressure.formatDepth(nextDepthM, units),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                IconButton(
                    onClick = { nextDepthM = (nextDepthM + 3.0).coerceAtMost(60.0) },
                    modifier = Modifier.testTag("si_next_depth_plus")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Increase next dive depth", modifier = Modifier.size(18.dp))
                }
            }

            if (targetApplyDiveIdx != null) {
                val currentPlannedSi = dives.getOrNull(targetApplyDiveIdx)?.surfaceIntervalBeforeMin ?: 0.0
                val isChanged = kotlin.math.abs(currentPlannedSi - siMinutes) >= 1.0
                FilledTonalButton(
                    onClick = { onApplySurfaceInterval(targetApplyDiveIdx, siMinutes) },
                    enabled = isChanged,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("si_apply_button")
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (isChanged) "Apply to $targetLabel" else "Applied",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            } else {
                FilledTonalButton(
                    onClick = { onAddDiveWithInterval(siMinutes) },
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.testTag("si_add_next_dive_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Add Dive 2",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
            }
        }
    }
}

@Composable
private fun SiMetricBox(
    label: String,
    value: String,
    sub: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun formatSiDuration(minutes: Double): String {
    val total = minutes.roundToInt().coerceAtLeast(0)
    val h = total / 60
    val m = total % 60
    return when {
        h == 0 -> "${m}m"
        m == 0 -> "${h}h"
        else -> "${h}h ${m}m"
    }
}
