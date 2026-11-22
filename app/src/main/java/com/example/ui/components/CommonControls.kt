package com.example.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.engine.AlertLevel
import com.example.engine.Gas
import com.example.engine.MixRecommendation
import com.example.engine.UnitSystem
import com.example.engine.UnitsAndPressure
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.roundToInt

fun gasAccentColor(gas: Gas): Color = when {
    gas.o2Pct >= 80 -> DecoEmerald
    gas.o2Pct >= 45 -> NitroxAmber
    gas.hePct > 0 -> TrimixViolet
    gas.o2Pct >= 28 -> Color(0xFF38BDF8)
    else -> CyanTelemetry
}

@Composable
fun GasBadge(gas: Gas, modifier: Modifier = Modifier) {
    val accent = gasAccentColor(gas)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.16f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.55f))
    ) {
        Text(
            text = gas.displayName,
            color = accent,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1
        )
    }
}

@Composable
fun HeroPhotoBanner(
    @DrawableRes imageRes: Int,
    contentDescription: String,
    badge: String,
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(152.dp)
            .clip(RoundedCornerShape(16.dp))
    ) {
        Image(
            painter = painterResource(id = imageRes),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x280A2540),
                            Color(0xC80A2540)
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = OceanLightBlue.copy(alpha = 0.92f)
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                if (actionLabel != null && onAction != null) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = DiveOrange,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = onAction)
                    ) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE3F2FD),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun BestGasKitGrid(
    rec: MixRecommendation,
    units: UnitSystem,
    modifier: Modifier = Modifier
) {
    fun cylTag(g: Gas): String = "${g.cylinder.count}×${g.cylinder.waterVolumeL.roundToInt()}L @ ${g.cylinder.fillPressureBar.roundToInt()}b"

    if (rec.isRecreational) {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = DecoGreen.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, DecoGreen.copy(alpha = 0.45f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rec_dive_badge")
            ) {
                Text(
                    text = "REC — Recreational Dive (< 40m & Within NDL) · No DECO or Trimix required",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = DecoGreen,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                )
            }
            val mixLabel = if (rec.bottomGas.o2Pct == 32) {
                "REC Best Gas Mix — Default Nitrox (EAN32)"
            } else if (rec.bottomGas.o2Pct == 21) {
                "REC Best Gas Mix — Air (21% O₂)"
            } else {
                "REC Best Gas Mix — Nitrox (${rec.bottomGas.displayName})"
            }
            BestGasBottleTile(
                stepLabel = mixLabel,
                gas = rec.bottomGas,
                detail = "MOD ${UnitsAndPressure.formatDepth(rec.modM, units)} · ppO₂ ${String.format(Locale.US, "%.2f", rec.ppO2AtMaxDepth)} bar · ${cylTag(rec.bottomGas)}",
                accent = DecoGreen,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val deco6m = rec.deco6mO2
        val deco21m = rec.deco21mEan50
        if (deco6m != null && deco21m != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BestGasBottleTile(
                    stepLabel = "1) DECO @ 6 m",
                    gas = deco6m,
                    detail = "${deco6m.o2Pct}% O₂ · MOD ${UnitsAndPressure.formatDepth(6.0, units)} · ${cylTag(deco6m)}",
                    accent = DecoGreen,
                    modifier = Modifier.weight(1f)
                )
                BestGasBottleTile(
                    stepLabel = "2) DECO @ 21 m",
                    gas = deco21m,
                    detail = "${deco21m.o2Pct}% O₂ · MOD ${UnitsAndPressure.formatDepth(21.0, units)} · ${cylTag(deco21m)}",
                    accent = DiveOrange,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BestGasBottleTile(
                stepLabel = "3) Travel Gas Mix",
                gas = rec.travelGasDisplay,
                detail = "${rec.travelRangeNote} · ${cylTag(rec.travelGasDisplay)}",
                accent = OceanLightBlue,
                modifier = Modifier.weight(1f)
            )
            BestGasBottleTile(
                stepLabel = "4) MOD Bottom Mix",
                gas = rec.bottomGas,
                detail = "MOD ${UnitsAndPressure.formatDepth(rec.modM, units)} · END ${UnitsAndPressure.formatDepth(rec.endAtMaxDepthM, units)} · ${cylTag(rec.bottomGas)}",
                accent = TrimixViolet,
                modifier = Modifier.weight(1f)
            )
        }
        rec.deepDecoGas?.let { deep ->
            val deepMod = deep.modOverrideM ?: 36.0
            BestGasBottleTile(
                stepLabel = "5) Deep DECO @ ${UnitsAndPressure.formatDepth(deepMod, units)}",
                gas = deep,
                detail = "ICD-safe intermediate helium deco stage · ${cylTag(deep)}",
                accent = DeepNavyText,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun BestGasBottleTile(
    stepLabel: String,
    gas: Gas,
    detail: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = accent.copy(alpha = 0.08f),
        border = BorderStroke(1.2.dp, accent.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = stepLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = gas.displayName,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "O₂ ${gas.o2Pct}% · He ${gas.hePct}% · N₂ ${gas.n2Pct}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.85f),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    subtitle: String? = null,
    badgeText: String? = null,
    initiallyExpanded: Boolean = false,
    testTag: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (badgeText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

@Composable
fun NumberStepperRow(
    label: String,
    value: Double,
    step: Double,
    min: Double,
    max: Double,
    unit: String = "",
    decimals: Int = 0,
    subtitle: String? = null,
    testTag: String,
    onValueChange: (Double) -> Unit
) {
    val safeValue = if (value.isFinite()) value.coerceIn(min, max) else min
    var textVal by remember(safeValue) {
        mutableStateOf(
            if (decimals == 0) safeValue.roundToInt().toString()
            else String.format(Locale.US, "%.${decimals}f", safeValue)
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            FilledTonalIconButton(
                onClick = { onValueChange((safeValue - step).coerceIn(min, max)) },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("${testTag}_minus"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease $label", modifier = Modifier.size(18.dp))
            }
            OutlinedTextField(
                value = textVal,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isDigit() || (decimals > 0 && it == '.') || (min < 0 && it == '-') }
                    if (filtered.length <= 8) {
                        textVal = filtered
                        filtered.toDoubleOrNull()?.let { parsed ->
                            if (parsed.isFinite() && parsed in min..max) {
                                onValueChange(parsed)
                            }
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = JetBrainsMonoFamily,
                    textAlign = TextAlign.Center
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (decimals > 0) KeyboardType.Decimal else KeyboardType.Number
                ),
                suffix = if (unit.isNotEmpty()) {
                    { Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else null,
                modifier = Modifier
                    .width(if (unit.length > 3) 108.dp else 90.dp)
                    .testTag("${testTag}_input"),
                shape = RoundedCornerShape(10.dp)
            )
            FilledTonalIconButton(
                onClick = { onValueChange((safeValue + step).coerceIn(min, max)) },
                modifier = Modifier
                    .size(40.dp)
                    .testTag("${testTag}_plus"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase $label", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun ExportShareCard(
    onExportPdf: () -> Unit,
    onExportCsv: () -> Unit,
    onSharePdf: () -> Unit,
    onShareCsv: () -> Unit,
    testTagPrefix: String,
    modifier: Modifier = Modifier
) {
    TelemetryCard(
        modifier = modifier.testTag("${testTagPrefix}_export_share_card"),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    ) {
        Text(
            text = "Export & Share Dive Plan",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Confirm all inputs and decompression stops above, then save or share your waterproof PDF slate or full CSV telemetry.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header row
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "FORMAT",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "SAVE TO DEVICE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1.1f)
                    )
                    Text(
                        text = "SHARE",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1.1f)
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

                // PDF Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PDF Slate",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Decompression table & gases",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onExportPdf,
                        modifier = Modifier.weight(1.1f).height(44.dp).testTag("${testTagPrefix}_export_pdf"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save PDF", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onSharePdf,
                        modifier = Modifier.weight(1.1f).height(44.dp).testTag("${testTagPrefix}_share_pdf"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share", maxLines = 1)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

                // CSV Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CSV Data",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "16 tissues & gas analytics",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onExportCsv,
                        modifier = Modifier.weight(1.1f).height(44.dp).testTag("${testTagPrefix}_export_csv"),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = DecoGreen,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save CSV", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = onShareCsv,
                        modifier = Modifier.weight(1.1f).height(44.dp).testTag("${testTagPrefix}_share_csv"),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share", maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun SwitchSettingRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .minimumInteractiveComponentSize()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
fun AlertLevelIcon(level: AlertLevel, modifier: Modifier = Modifier) {
    val (icon, tint) = when (level) {
        AlertLevel.ERROR -> Icons.Default.Error to AlertCrimson
        AlertLevel.WARNING -> Icons.Default.Warning to NitroxAmber
        AlertLevel.INFO -> Icons.Default.Info to CyanTelemetry
    }
    Icon(icon, contentDescription = level.label, tint = tint, modifier = modifier)
}

@Composable
fun PlanStatusPill(hasErrors: Boolean, hasWarnings: Boolean, alertCount: Int) {
    val (bg, fg, icon, label) = when {
        hasErrors -> Quadruple(AlertCrimson.copy(alpha = 0.18f), AlertCrimson, Icons.Default.Error, "$alertCount CRITICAL")
        hasWarnings -> Quadruple(NitroxAmber.copy(alpha = 0.18f), NitroxAmber, Icons.Default.Warning, "$alertCount ALERT")
        else -> Quadruple(DecoEmerald.copy(alpha = 0.18f), DecoEmerald, Icons.Default.CheckCircle, "PLAN READY")
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(1.dp, fg.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(14.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = fg
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun SafetyDisclaimerDialog(
    onAccept: () -> Unit,
    isFirstRun: Boolean,
    onDismiss: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = { if (!isFirstRun) onDismiss?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = !isFirstRun,
            dismissOnClickOutside = !isFirstRun,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, NitroxAmber.copy(alpha = 0.7f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(NitroxAmber.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Safety Warning",
                            tint = NitroxAmber
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.app_brand_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = stringResource(R.string.disclaimer_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                ) {
                    Text(
                        text = stringResource(R.string.disclaimer_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp),
                        lineHeight = 20.sp
                    )
                }

                Button(
                    onClick = {
                        if (isFirstRun) onAccept() else onDismiss?.invoke()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("disclaimer_accept_button"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = if (isFirstRun) stringResource(R.string.disclaimer_accept_button) else "Close",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
