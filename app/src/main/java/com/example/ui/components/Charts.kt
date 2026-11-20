package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.*
import com.example.ui.theme.*
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private fun safeFrac(value: Double, maxValue: Double): Float {
    if (!value.isFinite() || !maxValue.isFinite() || maxValue <= 0.0) return 0f
    return (value / maxValue).toFloat().coerceIn(0f, 1f)
}

private data class GasPhaseBlock(
    val gas: Gas,
    val startTimeMin: Double,
    val startDepthM: Double,
    val points: List<Pair<Double, Double>> // (timeMin, depthM)
)

private fun phaseFillColor(gas: Gas, isPrimaryBottom: Boolean): Color = when {
    gas.o2Pct >= 80 -> DecoGreen
    gas.o2Pct >= 45 -> DiveOrange
    gas.hePct > 0 && gas.role == GasRole.DECO -> TrimixViolet
    gas.role == GasRole.TRAVEL -> BrightSkyBlue
    isPrimaryBottom && gas.hePct > 0 -> Color(0xFF3949AB)
    isPrimaryBottom -> Color(0xFF155E75)
    else -> OceanLightBlue
}

/**
 * 1. Visualized Dive Profile Chart:
 *    - Color-blocked filled vertical bands per breathing gas phase from surface (0m) to contour
 *    - Crisp decompression stair-steps, vertical mix-change transitions & floating gas callout tags
 *    - Prominent TTS (Total Time to Surface) header badge, ascent-zone indicator & live scrub TTS
 *    - Interactive Zoom In / Zoom Out (1.0x – 5.0x) via buttons OR pinch-to-zoom + drag pan
 */
@Composable
fun DiveProfileChart(
    result: DivePlanResult,
    units: UnitSystem,
    modifier: Modifier = Modifier
) {
    val segments = result.segments
    val timeline = result.tissueTimeline
    if (segments.isEmpty() || timeline.size < 2) return

    val textMeasurer = rememberTextMeasurer()

    // Build exact segment-accurate gas phase blocks for razor-sharp stair steps & mix switches
    val gasPhases = remember(result) {
        val blocks = mutableListOf<GasPhaseBlock>()
        var curGas: Gas? = null
        var curStartT = 0.0
        var curStartD = 0.0
        var curPts = mutableListOf<Pair<Double, Double>>()

        for (seg in segments) {
            val segStartT = (seg.runtimeMin - seg.durationMin).coerceAtLeast(0.0)
            if (curGas == null || curGas.id != seg.gas.id) {
                if (curGas != null && curPts.isNotEmpty()) {
                    blocks += GasPhaseBlock(curGas, curStartT, curStartD, curPts.toList())
                }
                curGas = seg.gas
                curStartT = segStartT
                curStartD = seg.fromDepthM
                curPts = mutableListOf(segStartT to seg.fromDepthM, seg.runtimeMin to seg.toDepthM)
            } else {
                val last = curPts.lastOrNull()
                if (last == null || abs(last.first - segStartT) > 1e-4 || abs(last.second - seg.fromDepthM) > 1e-4) {
                    curPts += segStartT to seg.fromDepthM
                }
                curPts += seg.runtimeMin to seg.toDepthM
            }
        }
        if (curGas != null && curPts.isNotEmpty()) {
            blocks += GasPhaseBlock(curGas, curStartT, curStartD, curPts.toList())
        }
        blocks.toList()
    }

    val primaryBottomGasId = remember(result) {
        segments.firstOrNull { it.type == SegmentType.LEVEL }?.gas?.id ?: segments.first().gas.id
    }

    // Dynamic base axis bounds matching Metric (m) or Imperial (ft)
    val maxDepthDisplay = remember(result, units) {
        UnitsAndPressure.depthDisplay(result.maxDepthM, units)
    }
    val baseDepthStepDisplay = remember(maxDepthDisplay, units) {
        if (units == UnitSystem.IMPERIAL) {
            when {
                maxDepthDisplay <= 80.0 -> 30.0
                maxDepthDisplay <= 180.0 -> 50.0
                else -> 100.0
            }
        } else {
            when {
                maxDepthDisplay <= 25.0 -> 10.0
                maxDepthDisplay <= 75.0 -> 20.0
                maxDepthDisplay <= 130.0 -> 30.0
                else -> 50.0
            }
        }
    }
    val axisMaxDepthDisplay = remember(maxDepthDisplay, baseDepthStepDisplay) {
        val raw = ceil((maxDepthDisplay + baseDepthStepDisplay * 0.45) / baseDepthStepDisplay) * baseDepthStepDisplay
        max(raw, baseDepthStepDisplay * 2.0)
    }
    val axisMaxDepthM = remember(axisMaxDepthDisplay, units) {
        UnitsAndPressure.depthFromDisplay(axisMaxDepthDisplay, units).coerceAtLeast(10.0)
    }

    val totalRuntimeMin = remember(result) {
        result.totalRuntimeMin.coerceAtLeast(1.0)
    }
    val axisMaxTimeMin = remember(totalRuntimeMin) { totalRuntimeMin * 1.04 }
    val ttsStartMin = remember(result, totalRuntimeMin) {
        (totalRuntimeMin - result.ttsMin).coerceIn(0.0, totalRuntimeMin)
    }

    // Zoom & Pan Viewport State (1.0x .. 5.0x)
    var zoomScale by remember(result) { mutableStateOf(1f) }
    var viewStartTimeMin by remember(result) { mutableStateOf(0.0) }
    var viewStartDepthM by remember(result) { mutableStateOf(0.0) }

    val visibleTimeSpan = (axisMaxTimeMin / zoomScale.toDouble()).coerceAtLeast(1.0)
    val visibleDepthSpanM = (axisMaxDepthM / zoomScale.toDouble()).coerceAtLeast(6.0)
    val clampedStartTime = viewStartTimeMin.coerceIn(0.0, (axisMaxTimeMin - visibleTimeSpan).coerceAtLeast(0.0))
    val clampedStartDepthM = viewStartDepthM.coerceIn(0.0, (axisMaxDepthM - visibleDepthSpanM).coerceAtLeast(0.0))
    val viewEndTimeMin = clampedStartTime + visibleTimeSpan
    val viewEndDepthM = clampedStartDepthM + visibleDepthSpanM

    fun applyZoom(targetScale: Float) {
        val newScale = targetScale.coerceIn(1f, 5f)
        val centerT = clampedStartTime + visibleTimeSpan * 0.5
        val centerD = clampedStartDepthM + visibleDepthSpanM * 0.5
        val newTimeSpan = axisMaxTimeMin / newScale.toDouble()
        val newDepthSpan = axisMaxDepthM / newScale.toDouble()
        zoomScale = newScale
        viewStartTimeMin = if (newScale <= 1.01f) 0.0
        else (centerT - newTimeSpan * 0.5).coerceIn(0.0, (axisMaxTimeMin - newTimeSpan).coerceAtLeast(0.0))
        viewStartDepthM = if (newScale <= 1.01f) 0.0
        else (centerD - newDepthSpan * 0.5).coerceIn(0.0, (axisMaxDepthM - newDepthSpan).coerceAtLeast(0.0))
    }

    // Dynamic tick spacing for current zoomed viewport
    val visibleDepthDispSpan = UnitsAndPressure.depthDisplay(visibleDepthSpanM, units)
    val depthStepDisplay = when {
        visibleDepthDispSpan <= 12.0 -> 2.0
        visibleDepthDispSpan <= 25.0 -> 5.0
        visibleDepthDispSpan <= 60.0 -> 10.0
        visibleDepthDispSpan <= 130.0 -> 20.0
        visibleDepthDispSpan <= 260.0 -> 30.0
        else -> 50.0
    }
    val timeStepMin = when {
        visibleTimeSpan <= 8.0 -> 1.0
        visibleTimeSpan <= 16.0 -> 2.0
        visibleTimeSpan <= 32.0 -> 5.0
        visibleTimeSpan <= 65.0 -> 10.0
        visibleTimeSpan <= 130.0 -> 20.0
        visibleTimeSpan <= 220.0 -> 30.0
        else -> 60.0
    }

    var scrubTimeMin by remember(result) { mutableStateOf<Double?>(null) }
    val selectedSnapshot = scrubTimeMin?.let { t ->
        timeline.minByOrNull { abs(it.runtimeMin - t) }
    }

    val usedGases = remember(gasPhases) { gasPhases.map { it.gas }.distinctBy { it.id } }

    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
    val axisTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    val calloutBg = MaterialTheme.colorScheme.surface
    val calloutTextColor = MaterialTheme.colorScheme.onSurface
    val crosshairColor = MaterialTheme.colorScheme.primary

    val axisStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Medium,
        color = axisTextColor
    )
    val calloutStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.5.sp,
        fontWeight = FontWeight.Bold,
        color = calloutTextColor
    )
    val ttsBadgeStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = OceanLightBlue
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Bold Centered Max Depth + TTS Header
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = "MAX DEPTH ${maxDepthDisplay.roundToInt()} ${units.depthUnit.uppercase(Locale.US)}",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Runtime ${UnitsAndPressure.formatRuntimeMmSs(totalRuntimeMin)} · " +
                    "TTS ${UnitsAndPressure.formatRuntimeMmSs(result.ttsMin)} · " +
                    "Deco ${result.totalDecoMin.roundToInt()}m · " +
                    "${result.input.general.decoModel.shortLabel} GF ${result.input.general.gfLow}/${result.input.general.gfHigh}",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Zoom In / Zoom Out Toolbar + TTS Status Pill
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = OceanLightBlue.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, OceanLightBlue.copy(alpha = 0.45f))
            ) {
                Text(
                    text = "TTS ${UnitsAndPressure.formatRuntimeMmSs(result.ttsMin)} (${result.ttsMin.roundToInt()} min)",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = OceanLightBlue,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                FilledTonalIconButton(
                    onClick = { applyZoom(zoomScale - 0.5f) },
                    enabled = zoomScale > 1.01f,
                    modifier = Modifier.testTag("profile_zoom_out"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out dive profile", modifier = Modifier.size(20.dp))
                }
                Surface(
                    onClick = { if (zoomScale > 1.01f) applyZoom(1f) },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.testTag("profile_zoom_reset")
                ) {
                    Text(
                        text = String.format(Locale.US, "%.1f×", zoomScale),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
                    )
                }
                FilledTonalIconButton(
                    onClick = { applyZoom(zoomScale + 0.5f) },
                    enabled = zoomScale < 4.99f,
                    modifier = Modifier.testTag("profile_zoom_in"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in dive profile", modifier = Modifier.size(20.dp))
                }
                if (zoomScale > 1.01f) {
                    FilledTonalIconButton(
                        onClick = { applyZoom(1f) },
                        modifier = Modifier.testTag("profile_zoom_fit"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CenterFocusStrong, contentDescription = "Reset dive profile zoom", modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // Live Interactive Scrubbing Readout Pill (with live remaining TTS)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
        ) {
            val snap = selectedSnapshot ?: timeline[timeline.size / 2]
            val remTts = (totalRuntimeMin - snap.runtimeMin).coerceAtLeast(0.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedSnapshot != null) "INSPECT" else if (zoomScale > 1.01f) "DRAG TO PAN · TAP" else "PINCH / TAP",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "RT ${UnitsAndPressure.formatRuntimeMmSs(snap.runtimeMin)} · " +
                        "${UnitsAndPressure.formatDepth(snap.depthM, units, 1)} · " +
                        "${snap.gas.displayName} · " +
                        "TTS ${UnitsAndPressure.formatRuntimeMmSs(remTts)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Main Visualized Dive Profile Canvas (Pinch-to-Zoom + Pan + Tap-to-Inspect)
        val latestZoom by rememberUpdatedState(zoomScale)
        val latestStartT by rememberUpdatedState(clampedStartTime)
        val latestStartD by rememberUpdatedState(clampedStartDepthM)
        val latestTimeSpan by rememberUpdatedState(visibleTimeSpan)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(282.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(surfaceBg)
                .testTag("dive_profile_chart")
                .pointerInput(result, axisMaxTimeMin, axisMaxDepthM) {
                    val leftPx = 50.dp.toPx()
                    val rightPx = 14.dp.toPx()
                    detectTapGestures { offset ->
                        val plotW = (size.width - leftPx - rightPx).coerceAtLeast(1f)
                        val frac = ((offset.x - leftPx) / plotW).coerceIn(0f, 1f)
                        scrubTimeMin = latestStartT + frac * latestTimeSpan
                    }
                }
                .pointerInput(result, axisMaxTimeMin, axisMaxDepthM) {
                    val leftPx = 50.dp.toPx()
                    val rightPx = 14.dp.toPx()
                    val topPx = 36.dp.toPx()
                    val bottomPx = 38.dp.toPx()
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            val pressedCount = event.changes.count { it.pressed }
                            val plotW = (size.width - leftPx - rightPx).coerceAtLeast(1f)
                            val plotH = (size.height - topPx - bottomPx).coerceAtLeast(1f)
                            val oldScale = latestZoom
                            val oldTimeSpan = axisMaxTimeMin / oldScale.toDouble()
                            val oldDepthSpan = axisMaxDepthM / oldScale.toDouble()

                            if (pressedCount >= 2) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                val newScale = (oldScale * zoom).coerceIn(1f, 5f)
                                val newTimeSpan = axisMaxTimeMin / newScale.toDouble()
                                val newDepthSpan = axisMaxDepthM / newScale.toDouble()
                                val fx = ((centroid.x - leftPx) / plotW).coerceIn(0f, 1f).toDouble()
                                val fy = ((centroid.y - topPx) / plotH).coerceIn(0f, 1f).toDouble()
                                val anchorT = latestStartT + fx * oldTimeSpan
                                val anchorD = latestStartD + fy * oldDepthSpan
                                zoomScale = newScale
                                viewStartTimeMin = if (newScale <= 1.01f) 0.0
                                else (anchorT - fx * newTimeSpan - (pan.x / plotW) * newTimeSpan)
                                    .coerceIn(0.0, (axisMaxTimeMin - newTimeSpan).coerceAtLeast(0.0))
                                viewStartDepthM = if (newScale <= 1.01f) 0.0
                                else (anchorD - fy * newDepthSpan - (pan.y / plotH) * newDepthSpan)
                                    .coerceIn(0.0, (axisMaxDepthM - newDepthSpan).coerceAtLeast(0.0))
                                event.changes.forEach { if (it.pressed) it.consume() }
                            } else if (pressedCount == 1) {
                                val pan = event.calculatePan()
                                if (oldScale > 1.01f && pan != Offset.Zero) {
                                    // Single-finger drag pans the zoomed dive profile
                                    val dT = -(pan.x / plotW) * oldTimeSpan
                                    val dD = -(pan.y / plotH) * oldDepthSpan
                                    viewStartTimeMin = (latestStartT + dT)
                                        .coerceIn(0.0, (axisMaxTimeMin - oldTimeSpan).coerceAtLeast(0.0))
                                    viewStartDepthM = (latestStartD + dD)
                                        .coerceIn(0.0, (axisMaxDepthM - oldDepthSpan).coerceAtLeast(0.0))
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                } else if (oldScale <= 1.01f && abs(pan.x) > abs(pan.y) && abs(pan.x) > 1.5f) {
                                    // At 1.0x zoom, horizontal drag scrubs telemetry while vertical drag scrolls the page
                                    val pos = event.changes.first().position
                                    val frac = ((pos.x - leftPx) / plotW).coerceIn(0f, 1f)
                                    scrubTimeMin = frac * axisMaxTimeMin
                                    event.changes.forEach { if (it.pressed) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPad = 50.dp.toPx()
                val rightPad = 14.dp.toPx()
                val topPad = 36.dp.toPx()
                val bottomPad = 38.dp.toPx()
                val plotW = size.width - leftPad - rightPad
                val plotH = size.height - topPad - bottomPad
                if (plotW <= 10f || plotH <= 10f) return@Canvas

                val bottomY = topPad + plotH
                fun xForTime(tMin: Double): Float =
                    leftPad + (((tMin - clampedStartTime) / visibleTimeSpan).toFloat()) * plotW
                fun yForDepth(dM: Double): Float =
                    topPad + (((dM - clampedStartDepthM) / visibleDepthSpanM).toFloat()) * plotH

                // 1. Horizontal Depth Grid Lines & Labels for Visible Viewport
                val startDepthDisp = UnitsAndPressure.depthDisplay(clampedStartDepthM, units)
                val endDepthDisp = UnitsAndPressure.depthDisplay(viewEndDepthM, units)
                var dDisp = ceil(startDepthDisp / depthStepDisplay) * depthStepDisplay
                var guard = 0
                while (dDisp <= endDepthDisp + 1e-3 && guard++ < 14) {
                    val dM = UnitsAndPressure.depthFromDisplay(dDisp, units)
                    val y = yForDepth(dM)
                    if (y in (topPad - 1f)..(bottomY + 1f)) {
                        drawLine(
                            color = if (dDisp <= 0.01) gridColor.copy(alpha = 0.85f) else gridColor,
                            start = Offset(leftPad, y),
                            end = Offset(leftPad + plotW, y),
                            strokeWidth = if (dDisp <= 0.01) 1.4.dp.toPx() else 1.dp.toPx()
                        )
                        val label = "${dDisp.roundToInt()}${units.depthUnit}."
                        val measured = textMeasurer.measure(label, axisStyle)
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(
                                x = (leftPad - measured.size.width - 7.dp.toPx()).coerceAtLeast(2f),
                                y = y - measured.size.height / 2f
                            )
                        )
                    }
                    dDisp += depthStepDisplay
                }

                // 2. Vertical Time Ticks & Labels for Visible Viewport
                var tTick = ceil(clampedStartTime / timeStepMin) * timeStepMin
                var tGuard = 0
                while (tTick <= viewEndTimeMin + 1e-3 && tGuard++ < 14) {
                    val x = xForTime(tTick)
                    if (x in (leftPad - 1f)..(leftPad + plotW + 1f)) {
                        drawLine(
                            color = gridColor.copy(alpha = 0.85f),
                            start = Offset(x, bottomY),
                            end = Offset(x, bottomY + 5.dp.toPx()),
                            strokeWidth = 1.2.dp.toPx()
                        )
                        val label = "${tTick.roundToInt()}min"
                        val measured = textMeasurer.measure(label, axisStyle)
                        val tx = (x - measured.size.width / 2f)
                            .coerceIn(leftPad - 8.dp.toPx(), size.width - measured.size.width - 4.dp.toPx())
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(tx, bottomY + 6.dp.toPx())
                        )
                    }
                    tTick += timeStepMin
                }
                val timeTitle = textMeasurer.measure("Time", axisStyle)
                drawText(
                    textLayoutResult = timeTitle,
                    topLeft = Offset(
                        x = leftPad + (plotW - timeTitle.size.width) / 2f,
                        y = size.height - timeTitle.size.height - 2.dp.toPx()
                    )
                )

                // 3. Clipped Plot Area: Color-Blocked Gas Phases, TTS Bracket, Ceiling & Crosshair
                clipRect(left = leftPad, top = topPad, right = leftPad + plotW, bottom = bottomY) {
                    gasPhases.forEachIndexed { idx, phase ->
                        val baseColor = phaseFillColor(phase.gas, phase.gas.id == primaryBottomGasId)
                        val poly = Path().apply {
                            val firstPt = phase.points.first()
                            val lastPt = phase.points.last()
                            moveTo(xForTime(firstPt.first), yForDepth(0.0))
                            for ((t, d) in phase.points) {
                                lineTo(xForTime(t), yForDepth(d))
                            }
                            lineTo(xForTime(lastPt.first), yForDepth(0.0))
                            close()
                        }
                        drawPath(
                            path = poly,
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    baseColor.copy(alpha = 0.82f),
                                    baseColor.copy(alpha = 0.62f)
                                ),
                                startY = topPad,
                                endY = bottomY
                            )
                        )

                        // Crisp mix-change vertical seam between gas bands
                        if (idx > 0) {
                            val sx = xForTime(phase.startTimeMin)
                            val sy = yForDepth(phase.startDepthM)
                            drawLine(
                                color = Color.White.copy(alpha = 0.72f),
                                start = Offset(sx, yForDepth(0.0)),
                                end = Offset(sx, sy),
                                strokeWidth = 1.4.dp.toPx()
                            )
                        }

                        // Highlighted bottom contour edge
                        val edge = Path().apply {
                            phase.points.forEachIndexed { pIdx, (t, d) ->
                                val px = xForTime(t)
                                val py = yForDepth(d)
                                if (pIdx == 0) moveTo(px, py) else lineTo(px, py)
                            }
                        }
                        drawPath(
                            path = edge,
                            color = baseColor,
                            style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                        )
                    }

                    // Visual TTS Span Bracket along the bottom of the ascent/deco zone
                    if (result.ttsMin > 0.2) {
                        val ttsX0 = xForTime(ttsStartMin).coerceIn(leftPad, leftPad + plotW)
                        val ttsX1 = xForTime(totalRuntimeMin).coerceIn(leftPad, leftPad + plotW)
                        if (ttsX1 - ttsX0 >= 28.dp.toPx()) {
                            val brY = bottomY - 11.dp.toPx()
                            drawLine(
                                color = OceanLightBlue,
                                start = Offset(ttsX0, brY),
                                end = Offset(ttsX1, brY),
                                strokeWidth = 2.dp.toPx()
                            )
                            drawLine(OceanLightBlue, Offset(ttsX0, brY - 4.dp.toPx()), Offset(ttsX0, brY + 4.dp.toPx()), 2.dp.toPx())
                            drawLine(OceanLightBlue, Offset(ttsX1, brY - 4.dp.toPx()), Offset(ttsX1, brY + 4.dp.toPx()), 2.dp.toPx())
                            val ttsLabel = "TTS ${result.ttsMin.roundToInt()}m"
                            val mTts = textMeasurer.measure(ttsLabel, ttsBadgeStyle)
                            val padX = 4.dp.toPx()
                            val padY = 1.5.dp.toPx()
                            val bx = ((ttsX0 + ttsX1) / 2f - (mTts.size.width + padX * 2f) / 2f)
                                .coerceIn(leftPad + 2f, leftPad + plotW - mTts.size.width - padX * 2f - 2f)
                            val by = brY - mTts.size.height - padY * 2f - 2.dp.toPx()
                            drawRoundRect(
                                color = calloutBg.copy(alpha = 0.92f),
                                topLeft = Offset(bx, by),
                                size = Size(mTts.size.width + padX * 2f, mTts.size.height + padY * 2f),
                                cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                            )
                            drawText(mTts, topLeft = Offset(bx + padX, by + padY))
                        }
                    }

                    // Dashed Bühlmann Decompression Ceiling Curve
                    val ceilPath = Path()
                    var hasCeiling = false
                    timeline.forEachIndexed { idx, snap ->
                        if (snap.ceilingM > 0.2) hasCeiling = true
                        val cx = xForTime(snap.runtimeMin)
                        val cy = yForDepth(snap.ceilingM)
                        if (idx == 0) ceilPath.moveTo(cx, cy) else ceilPath.lineTo(cx, cy)
                    }
                    if (hasCeiling) {
                        drawPath(
                            path = ceilPath,
                            color = AlertCrimson.copy(alpha = 0.90f),
                            style = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f), 0f)
                            )
                        )
                    }

                    // Interactive Touch-to-Scrub Crosshair
                    selectedSnapshot?.let { snap ->
                        val cx = xForTime(snap.runtimeMin)
                        val cy = yForDepth(snap.depthM)
                        drawLine(
                            color = crosshairColor,
                            start = Offset(cx, topPad),
                            end = Offset(cx, bottomY),
                            strokeWidth = 1.8.dp.toPx()
                        )
                        drawCircle(crosshairColor.copy(alpha = 0.28f), radius = 9.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(Color.White, radius = 4.2.dp.toPx(), center = Offset(cx, cy))
                        drawCircle(crosshairColor, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
                    }
                }

                // 4. Floating Inline Gas Mix Callout Tags (drawn for visible switch anchors, collision-safe!)
                val placedRects = mutableListOf<Rect>()
                val hPad = 6.dp.toPx()
                val vPad = 3.dp.toPx()
                val pillRadius = 5.dp.toPx()

                gasPhases.take(6).forEachIndexed { idx, phase ->
                    val ax = xForTime(phase.startTimeMin)
                    val ay = yForDepth(phase.startDepthM)
                    // Skip callouts whose switch anchor is scrolled completely outside the zoomed viewport
                    if (ax < leftPad - 4.dp.toPx() || ax > leftPad + plotW + 4.dp.toPx() ||
                        ay < topPad - 4.dp.toPx() || ay > bottomY + 4.dp.toPx()
                    ) {
                        return@forEachIndexed
                    }
                    val accent = phaseFillColor(phase.gas, phase.gas.id == primaryBottomGasId)
                    val label = phase.gas.displayName
                    val measured = textMeasurer.measure(label, calloutStyle)
                    val boxW = measured.size.width + hPad * 2f
                    val boxH = measured.size.height + vPad * 2f

                    val bx = if (idx == 0) {
                        (ax - boxW * 0.25f).coerceIn(leftPad - 4.dp.toPx(), size.width - rightPad - boxW)
                    } else {
                        (ax - boxW * 0.5f).coerceIn(leftPad + 2.dp.toPx(), size.width - rightPad - boxW)
                    }
                    var by = if (idx == 0) {
                        (topPad - boxH - 8.dp.toPx()).coerceAtLeast(3.dp.toPx())
                    } else {
                        (ay - boxH - 10.dp.toPx()).coerceIn(3.dp.toPx(), bottomY - boxH - 4.dp.toPx())
                    }

                    var tries = 0
                    while (tries++ < 6) {
                        val candidate = Rect(bx, by, bx + boxW, by + boxH)
                        val hit = placedRects.firstOrNull { it.inflate(3.dp.toPx()).overlaps(candidate) }
                        if (hit == null) break
                        by = if (by - boxH - 5.dp.toPx() >= 3.dp.toPx()) {
                            hit.top - boxH - 5.dp.toPx()
                        } else {
                            (hit.bottom + 5.dp.toPx()).coerceAtMost(bottomY - boxH - 4.dp.toPx())
                        }
                    }

                    val finalRect = Rect(bx, by, bx + boxW, by + boxH)
                    placedRects += finalRect

                    val stemX = ax.coerceIn(finalRect.left + 4.dp.toPx(), finalRect.right - 4.dp.toPx())
                    val stemStartY = if (finalRect.bottom <= ay) finalRect.bottom else finalRect.top
                    drawLine(
                        color = accent,
                        start = Offset(stemX, stemStartY),
                        end = Offset(ax, ay),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawCircle(color = Color.White, radius = 4.dp.toPx(), center = Offset(ax, ay))
                    drawCircle(color = accent, radius = 2.6.dp.toPx(), center = Offset(ax, ay))

                    drawRoundRect(
                        color = calloutBg,
                        topLeft = Offset(finalRect.left, finalRect.top),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(pillRadius, pillRadius)
                    )
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(finalRect.left, finalRect.top),
                        size = Size(boxW, boxH),
                        cornerRadius = CornerRadius(pillRadius, pillRadius),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                    drawText(
                        textLayoutResult = measured,
                        topLeft = Offset(finalRect.left + hPad, finalRect.top + vPad)
                    )
                }
            }
        }

        // Compact Gas Phase & Ceiling Legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            usedGases.forEach { g ->
                LegendChip(
                    color = phaseFillColor(g, g.id == primaryBottomGasId),
                    label = g.displayName
                )
            }
            if (result.totalDecoMin > 0.0) {
                LegendChip(color = AlertCrimson, label = "Ceiling")
            }
        }
    }
}

/**
 * 2. Gas Partial Pressures Chart (ppO2, ppN2, ppHe vs Time + Limit lines).
 */
@Composable
fun GasPartialPressuresChart(
    result: DivePlanResult,
    modifier: Modifier = Modifier
) {
    val timeline = result.tissueTimeline
    if (timeline.size < 2) return

    var scrubFrac by remember(result) { mutableStateOf<Float?>(null) }
    val selected = scrubFrac?.let { f ->
        timeline[(f * timeline.lastIndex).roundToInt().coerceIn(0, timeline.lastIndex)]
    }

    val maxTime = remember(result) { timeline.last().runtimeMin.coerceAtLeast(1.0) }
    val maxPp = remember(result) {
        max(
            5.0,
            timeline.maxOf { max(it.ppO2, max(it.ppN2, it.ppHe)) } * 1.15
        )
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val cursorColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val snap = selected ?: timeline[timeline.size / 3]
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RT ${UnitsAndPressure.formatRuntimeMmSs(snap.runtimeMin)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ppO2 ${"%.2f".format(Locale.US, snap.ppO2)} · " +
                        "ppN2 ${"%.2f".format(Locale.US, snap.ppN2)} · " +
                        "ppHe ${"%.2f".format(Locale.US, snap.ppHe)} bar",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .testTag("partial_pressures_chart")
                .pointerInput(result) {
                    detectTapGestures { o -> if (size.width > 0) scrubFrac = (o.x / size.width).coerceIn(0f, 1f) }
                }
                .pointerInput(result) {
                    detectHorizontalDragGestures { change, _ ->
                        if (size.width > 0) scrubFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                for (s in 0..4) {
                    val y = h * (s / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
                }

                // Limit threshold lines
                fun drawLimit(barVal: Double, color: Color) {
                    val y = h - safeFrac(barVal, maxPp) * h
                    drawLine(
                        color = color.copy(alpha = 0.55f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.4.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                    )
                }
                drawLimit(result.input.dive.ppO2Deco, AlertCrimson)
                drawLimit(result.input.dive.ppO2Bottom, NitroxAmber)
                drawLimit(result.input.dive.ppN2Max, CyanTelemetry)

                fun drawCurve(selector: (TissueSnapshot) -> Double, color: Color) {
                    val p = Path()
                    timeline.forEachIndexed { idx, s ->
                        val x = safeFrac(s.runtimeMin, maxTime) * w
                        val y = h - safeFrac(selector(s), maxPp) * h
                        if (idx == 0) p.moveTo(x, y) else p.lineTo(x, y)
                    }
                    drawPath(p, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }

                drawCurve({ it.ppN2 }, CyanTelemetry)
                drawCurve({ it.ppHe }, TrimixViolet)
                drawCurve({ it.ppO2 }, DecoEmerald)

                selected?.let { s ->
                    val cx = safeFrac(s.runtimeMin, maxTime) * w
                    drawLine(cursorColor, Offset(cx, 0f), Offset(cx, h), 1.6.dp.toPx())
                }
            }

            Text(
                text = "${"%.1f".format(Locale.US, maxPp)} bar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            Text(
                text = "0.0 bar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendChip(DecoEmerald, "ppO2")
            LegendChip(CyanTelemetry, "ppN2")
            LegendChip(TrimixViolet, "ppHe")
            LegendChip(AlertCrimson, "Deco ppO2 Limit")
        }
    }
}

/**
 * 3A. 16-Compartment Tissue Gas Loading & Saturation Level Bars (with interactive time slider!).
 * Shows N2 (Cyan) + He (Violet) tension & % M-value saturation per compartment C1..C16.
 */
@Composable
fun TissueCompartmentBarsChart(
    result: DivePlanResult,
    modifier: Modifier = Modifier
) {
    val timeline = result.tissueTimeline
    if (timeline.isEmpty()) return

    val coeffs = remember(result.input.general.decoModel, result.input.general.useCompartment1b) {
        Coefficients.tableFor(result.input.general.decoModel, result.input.general.useCompartment1b)
    }
    // Default slider to end of bottom (deepest/peak loading moment) or last snapshot
    val peakIndex = remember(timeline) {
        timeline.indices.maxByOrNull { timeline[it].tissues.compartments.maxOf { c -> c.pInert } } ?: timeline.lastIndex
    }
    var sliderPos by remember(result) { mutableStateOf(peakIndex.toFloat()) }
    val snapIdx = sliderPos.roundToInt().coerceIn(0, timeline.lastIndex)
    val snap = timeline[snapIdx]

    val maxTensionBar = remember(timeline) {
        max(3.5, timeline.maxOf { s -> s.tissues.compartments.maxOf { it.pInert } } * 1.15)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Time Scrubber Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "RT ${UnitsAndPressure.formatRuntimeMmSs(snap.runtimeMin)} · Depth ${snap.depthM.roundToInt()} m",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Leading: ${coeffs[snap.leadingCompartmentIndex].label} (${coeffs[snap.leadingCompartmentIndex].n2HalfTime}m HT) · SurfGF ${snap.surfaceGfPct.roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = { sliderPos = 0f },
                    label = { Text("Start", style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = { sliderPos = peakIndex.toFloat() },
                    label = { Text("Peak", style = MaterialTheme.typography.labelSmall) }
                )
                AssistChip(
                    onClick = { sliderPos = timeline.lastIndex.toFloat() },
                    label = { Text("End", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Slider(
            value = sliderPos,
            onValueChange = { sliderPos = it },
            valueRange = 0f..timeline.lastIndex.toFloat().coerceAtLeast(1f),
            modifier = Modifier.fillMaxWidth().testTag("tissue_time_slider")
        )

        // 16-Compartment Stacked N2 + He Bars with Saturation % Heatmap
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .padding(10.dp)
                .testTag("tissue_compartment_bars"),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            for (i in 0 until 16) {
                val tissue = snap.tissues.compartments[i]
                val satPct = snap.compartmentSaturationPct[i]
                val isLeader = i == snap.leadingCompartmentIndex
                val satColor = when {
                    satPct >= 95.0 -> AlertCrimson
                    satPct >= 75.0 -> NitroxAmber
                    else -> DecoEmerald
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = coeffs[i].label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = if (isLeader) FontWeight.Bold else FontWeight.Medium
                        ),
                        color = if (isLeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(26.dp)
                    )
                    Text(
                        text = "${coeffs[i].n2HalfTime.roundToInt()}m",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(34.dp)
                    )
                    // Stacked bar (N2 + He)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                    ) {
                        val n2Frac = (tissue.pN2 / maxTensionBar).toFloat().coerceIn(0f, 1f)
                        val heFrac = (tissue.pHe / maxTensionBar).toFloat().coerceIn(0f, 1f - n2Frac)
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (n2Frac > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(n2Frac)
                                        .background(CyanTelemetry)
                                )
                            }
                            if (heFrac > 0f) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(if (1f - n2Frac > 0f) (heFrac / (1f - n2Frac)).coerceIn(0f, 1f) else 0f)
                                        .background(TrimixViolet)
                                )
                            }
                        }
                        // Ambient pressure tick line
                        val ambFrac = (snap.pAmbBar / maxTensionBar).toFloat().coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(ambFrac)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.CenterEnd)
                                    .width(2.5.dp)
                                    .fillMaxHeight()
                                    .background(DeepNavyText)
                            )
                        }
                    }
                    Text(
                        text = "${"%.2f".format(Locale.US, tissue.pInert)}b",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(40.dp)
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = satColor.copy(alpha = 0.18f),
                        modifier = Modifier.width(44.dp)
                    ) {
                        Text(
                            text = "${satPct.roundToInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = satColor,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendChip(CyanTelemetry, "N2 Tension")
            LegendChip(TrimixViolet, "He Tension")
            LegendChip(DeepNavyText, "P_amb Marker")
            LegendChip(DecoEmerald, "Surf Sat %")
        }
    }
}

/**
 * 3B. 16-Line Tissue Inert Gas Tension Timeline vs Ambient Pressure.
 */
@Composable
fun TissueTensionTimelineChart(
    result: DivePlanResult,
    modifier: Modifier = Modifier
) {
    val timeline = result.tissueTimeline
    if (timeline.size < 2) return

    val maxTime = remember(result) { timeline.last().runtimeMin.coerceAtLeast(1.0) }
    val maxBar = remember(result) {
        max(
            timeline.maxOf { it.pAmbBar },
            timeline.maxOf { s -> s.tissues.compartments.maxOf { it.pInert } }
        ) * 1.1
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val ambLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .testTag("tissue_timeline_chart")
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                for (s in 0..4) {
                    val y = h * (s / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
                }

                // Draw all 16 compartment curves (fast = light blue, mid = violet/orange, slow = green)
                for (compIdx in 0 until 16) {
                    val color = when {
                        compIdx < 4 -> CyanTelemetry.copy(alpha = 0.9f - compIdx * 0.1f)
                        compIdx < 10 -> TrimixViolet.copy(alpha = 0.85f - (compIdx - 4) * 0.08f)
                        else -> DecoEmerald.copy(alpha = 0.85f - (compIdx - 10) * 0.08f)
                    }
                    val path = Path()
                    timeline.forEachIndexed { idx, s ->
                        val x = safeFrac(s.runtimeMin, maxTime) * w
                        val y = h - safeFrac(s.tissues.compartments[compIdx].pInert, maxBar) * h
                        if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, color, style = Stroke(width = 1.6.dp.toPx()))
                }

                // Ambient pressure dashed line
                val ambPath = Path()
                timeline.forEachIndexed { idx, s ->
                    val x = safeFrac(s.runtimeMin, maxTime) * w
                    val y = h - safeFrac(s.pAmbBar, maxBar) * h
                    if (idx == 0) ambPath.moveTo(x, y) else ambPath.lineTo(x, y)
                }
                drawPath(
                    ambPath,
                    color = ambLineColor,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    )
                )
            }

            Text(
                text = "${"%.1f".format(Locale.US, maxBar)} bar",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
            )
            Text(
                text = "Ambient P_amb (dashed)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendChip(CyanTelemetry, "Fast C1–C4")
            LegendChip(TrimixViolet, "Mid C5–C10")
            LegendChip(DecoEmerald, "Slow C11–C16")
            LegendChip(DeepNavyText, "P_amb")
        }
    }
}

/**
 * 4. Gas Consumption vs Cylinder Available Capacity Chart (Section 12.3).
 */
@Composable
fun GasConsumptionBarChart(
    consumption: List<GasConsumptionResult>,
    units: UnitSystem,
    modifier: Modifier = Modifier
) {
    if (consumption.isEmpty()) return
    val maxScale = max(
        100.0,
        consumption.maxOf { max(it.totalRequiredLitres, it.availableLitres) } * 1.12
    )

    Column(
        modifier = modifier.fillMaxWidth().testTag("gas_consumption_chart"),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        consumption.forEach { gc ->
            val accent = gasAccentColor(gc.gas)
            val usedFrac = (gc.usedLitres / maxScale).toFloat().coerceIn(0f, 1f)
            val resFrac = (gc.reserveLitres / maxScale).toFloat().coerceIn(0f, 1f - usedFrac)
            val availFrac = (gc.availableLitres / maxScale).toFloat().coerceIn(0f, 1f)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        GasBadge(gc.gas)
                        Text(
                            text = "${gc.gas.cylinder.count}×${gc.gas.cylinder.waterVolumeL.roundToInt()}L @ ${gc.gas.cylinder.fillPressureBar.roundToInt()}b",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "${UnitsAndPressure.formatVolume(gc.usedLitres, units)} + ${UnitsAndPressure.formatVolume(gc.reserveLitres, units)} res / ${UnitsAndPressure.formatVolume(gc.availableLitres, units)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (gc.isSufficient) MaterialTheme.colorScheme.onSurface else AlertCrimson
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        if (w <= 0f || h <= 0f) return@Canvas
                        // Used gas solid bar
                        drawRoundRect(
                            color = accent,
                            topLeft = Offset.Zero,
                            size = Size(w * usedFrac, h),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        // Reserve gas translucent bar
                        drawRect(
                            color = if (gc.isSufficient) accent.copy(alpha = 0.35f) else AlertCrimson.copy(alpha = 0.5f),
                            topLeft = Offset(w * usedFrac, 0f),
                            size = Size(w * resFrac, h)
                        )
                        // Available cylinder capacity vertical marker
                        val availX = w * availFrac
                        drawLine(
                            color = if (gc.isSufficient) DecoEmerald else AlertCrimson,
                            start = Offset(availX, 0f),
                            end = Offset(availX, h),
                            strokeWidth = 3.dp.toPx()
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Turn: ${UnitsAndPressure.formatPressure(gc.turnPressureBar, units)} · Min: ${UnitsAndPressure.formatPressure(gc.minPressureBar, units)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (gc.isSufficient) "OK (${UnitsAndPressure.formatPressure(gc.remainingBar, units)} rem)" else "INSUFFICIENT GAS",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (gc.isSufficient) DecoEmerald else AlertCrimson
                    )
                }
            }
        }
    }
}

/**
 * 5. M-Value & Gradient Factor Learning Diagram (P_amb vs P_tissue, Section 12.3).
 */
@Composable
fun MValueDiagramChart(
    result: DivePlanResult,
    modifier: Modifier = Modifier
) {
    val timeline = result.tissueTimeline
    if (timeline.size < 2) return

    val coeffs = remember(result.input.general.decoModel, result.input.general.useCompartment1b) {
        Coefficients.tableFor(result.input.general.decoModel, result.input.general.useCompartment1b)
    }
    // Track the leading compartment at peak GF99
    val leadingCompIdx = remember(timeline) {
        timeline.maxByOrNull { it.gf99Pct }?.leadingCompartmentIndex ?: 3
    }
    val coeff = coeffs[leadingCompIdx]
    val gfHigh = result.input.general.gfHigh / 100.0
    val gfLow = result.input.general.gfLow / 100.0

    val maxAxis = remember(result, leadingCompIdx) {
        max(
            6.0,
            max(
                timeline.maxOf { it.pAmbBar },
                timeline.maxOf { it.tissues.compartments[leadingCompIdx].pInert }
            ) * 1.15
        )
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val ambEquilColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Leading Compartment ${coeff.label} (${coeff.n2HalfTime}m N2 HT) — P_amb (X) vs P_tissue (Y)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                .testTag("m_value_diagram_chart")
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(14.dp)) {
                val w = size.width
                val h = size.height
                if (w <= 0f || h <= 0f) return@Canvas

                for (s in 0..4) {
                    val y = h * (s / 4f)
                    val x = w * (s / 4f)
                    drawLine(gridColor, Offset(0f, y), Offset(w, y), 1f)
                    drawLine(gridColor, Offset(x, 0f), Offset(x, h), 1f)
                }

                fun pt(pAmb: Double, pTis: Double): Offset {
                    val x = safeFrac(pAmb, maxAxis) * w
                    val y = h - safeFrac(pTis, maxAxis) * h
                    return Offset(x, y)
                }

                // 1. Ambient equilibrium line (P_tissue = P_amb)
                drawLine(ambEquilColor, pt(0.0, 0.0), pt(maxAxis, maxAxis), strokeWidth = 1.5.dp.toPx())

                // 2. Pure Buhlmann M-value line (GF = 100%)
                val mAt0 = TissuesAndBuhlmann.mValue(0.0, coeff.aN2, coeff.bN2)
                val mAtMax = TissuesAndBuhlmann.mValue(maxAxis, coeff.aN2, coeff.bN2)
                drawLine(AlertCrimson, pt(0.0, mAt0), pt(maxAxis, mAtMax), strokeWidth = 2.dp.toPx())

                // 3. GF High line
                val gfHi0 = coeff.aN2 * gfHigh
                val gfHiMax = maxAxis + (mAtMax - maxAxis) * gfHigh
                drawLine(
                    NitroxAmber,
                    pt(0.0, gfHi0),
                    pt(maxAxis, gfHiMax),
                    strokeWidth = 1.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                )

                // 4. GF Low line
                val gfLo0 = coeff.aN2 * gfLow
                val gfLoMax = maxAxis + (mAtMax - maxAxis) * gfLow
                drawLine(
                    DecoEmerald,
                    pt(0.0, gfLo0),
                    pt(maxAxis, gfLoMax),
                    strokeWidth = 1.8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                )

                // 5. Leading compartment trajectory path
                val path = Path()
                timeline.forEachIndexed { idx, s ->
                    val o = pt(s.pAmbBar, s.tissues.compartments[leadingCompIdx].pInert)
                    if (idx == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                }
                drawPath(path, CyanTelemetry, style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LegendChip(AlertCrimson, "M-Value (100%)")
            LegendChip(NitroxAmber, "GF Hi ${result.input.general.gfHigh}%")
            LegendChip(DecoEmerald, "GF Lo ${result.input.general.gfLow}%")
            LegendChip(CyanTelemetry, "Dive Path")
        }
    }
}

@Composable
private fun LegendChip(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
