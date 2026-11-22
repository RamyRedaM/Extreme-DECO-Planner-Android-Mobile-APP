package com.example.export

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.engine.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Generates PDF Dive Slates & Technical Reports (via Android PdfDocument)
 * and comprehensive CSV analytics exports (Runtime, Gas Consumption, 16-Compartment Tissue Timeline).
 * Supports both SAF (CreateDocument) and FileProvider Share Intents with zero storage permissions.
 */
object ExportManager {

    fun buildCsvContent(dayResult: MultiDiveDayResult, activeDiveIndex: Int): String {
        val sb = StringBuilder()
        val res = dayResult.diveResults.getOrNull(activeDiveIndex) ?: return ""
        val units = res.input.general.units
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

        sb.appendLine("Extreme DECO Planner Ver 1.27.2 by Ramy.DECO — Technical Dive Plan Export")
        sb.appendLine("Generated,$dateStr")
        sb.appendLine("Dive Number,${activeDiveIndex + 1} of ${dayResult.diveResults.size}")
        sb.appendLine("Surface Interval Before (min),${dayResult.surfaceIntervalsMin.getOrElse(activeDiveIndex) { 0.0 }}")
        sb.appendLine("Model,${res.input.general.decoModel.label}")
        sb.appendLine("Gradient Factors,${res.input.general.gfLow}/${res.input.general.gfHigh}")
        sb.appendLine("Planning Type,${res.input.general.planningType.label}")
        sb.appendLine("Salinity (kg/m3),${res.env.salinityKgM3}")
        sb.appendLine("Elevation (m),${res.env.elevationM}")
        sb.appendLine("Max Depth (${units.depthUnit}),${"%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(res.maxDepthM, units))}")
        sb.appendLine("Total Runtime (min),${"%.2f".format(Locale.US, res.totalRuntimeMin)}")
        sb.appendLine("Total Deco (min),${"%.2f".format(Locale.US, res.totalDecoMin)}")
        sb.appendLine("TTS (min),${"%.2f".format(Locale.US, res.ttsMin)}")
        sb.appendLine("Start CNS (%),${"%.1f".format(Locale.US, res.oxygen.startCnsPct)}")
        sb.appendLine("End CNS (%),${"%.1f".format(Locale.US, res.oxygen.endCnsPct)}")
        sb.appendLine("Dive OTU,${"%.1f".format(Locale.US, res.oxygen.diveOtu)}")
        sb.appendLine("Cumulative Daily OTU,${"%.1f".format(Locale.US, res.oxygen.dailyOtu)}")
        sb.appendLine("Surface GF (%),${"%.1f".format(Locale.US, res.endSurfaceGfPct)}")
        sb.appendLine()

        // Section 1: Runtime Table
        sb.appendLine("--- RUNTIME SCHEDULE ---")
        sb.appendLine("Action,FromDepth(${units.depthUnit}),ToDepth(${units.depthUnit}),Duration(min),Runtime(min),Runtime(mm:ss),TTS(min),TTS(mm:ss),Gas,ppO2(bar),ppN2(bar),ppHe(bar),END(${units.depthUnit}),Density(g/L),Ceiling(${units.depthUnit}),GF99(%),SurfGF(%)")
        for (seg in res.segments) {
            val segTts = (res.totalRuntimeMin - seg.runtimeMin).coerceAtLeast(0.0)
            sb.appendLine(
                listOf(
                    seg.type.label,
                    "%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(seg.fromDepthM, units)),
                    "%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(seg.toDepthM, units)),
                    "%.2f".format(Locale.US, seg.durationMin),
                    "%.2f".format(Locale.US, seg.runtimeMin),
                    UnitsAndPressure.formatRuntimeMmSs(seg.runtimeMin),
                    "%.2f".format(Locale.US, segTts),
                    UnitsAndPressure.formatRuntimeMmSs(segTts),
                    seg.gas.displayName,
                    "%.2f".format(Locale.US, seg.ppO2End),
                    "%.2f".format(Locale.US, seg.ppN2End),
                    "%.2f".format(Locale.US, seg.ppHeEnd),
                    "%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(seg.endM, units)),
                    "%.2f".format(Locale.US, seg.densityGL),
                    "%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(seg.ceilingM, units)),
                    "%.1f".format(Locale.US, seg.gf99Pct),
                    "%.1f".format(Locale.US, seg.surfaceGfPct)
                ).joinToString(",")
            )
        }
        sb.appendLine()

        // Section 2: Gas Consumption
        sb.appendLine("--- GAS CONSUMPTION & RESERVES ---")
        sb.appendLine("Gas,Role,Used(${units.volUnit}),Reserve(${units.volUnit}),Required(${units.volUnit}),Available(${units.volUnit}),Turn(${units.pressureUnit}),MinPressure(${units.pressureUnit}),Sufficient")
        for (gc in res.gasConsumption) {
            sb.appendLine(
                listOf(
                    gc.gas.displayName,
                    gc.gas.role.label,
                    "%.1f".format(Locale.US, UnitsAndPressure.volumeDisplay(gc.usedLitres, units)),
                    "%.1f".format(Locale.US, UnitsAndPressure.volumeDisplay(gc.reserveLitres, units)),
                    "%.1f".format(Locale.US, UnitsAndPressure.volumeDisplay(gc.totalRequiredLitres, units)),
                    "%.1f".format(Locale.US, UnitsAndPressure.volumeDisplay(gc.availableLitres, units)),
                    "%.0f".format(Locale.US, UnitsAndPressure.pressureDisplay(gc.turnPressureBar, units)),
                    "%.0f".format(Locale.US, UnitsAndPressure.pressureDisplay(gc.minPressureBar, units)),
                    gc.isSufficient.toString()
                ).joinToString(",")
            )
        }
        sb.appendLine()

        // Section 3: 16-Compartment Tissue Tensions Timeline
        sb.appendLine("--- 16-COMPARTMENT TISSUE TENSIONS (bar N2+He) ---")
        val compHeaders = (1..16).joinToString(",") { "C${it}_Inert(bar),C${it}_Sat(%)" }
        sb.appendLine("Runtime(min),Depth(${units.depthUnit}),P_amb(bar),Ceiling(${units.depthUnit}),GF99(%),$compHeaders")
        // Downsample to <= 120 rows if very long dive
        val step = (res.tissueTimeline.size / 120).coerceAtLeast(1)
        res.tissueTimeline.forEachIndexed { idx, snap ->
            if (idx % step == 0 || idx == res.tissueTimeline.lastIndex) {
                val compCols = (0 until 16).joinToString(",") { cIdx ->
                    val t = snap.tissues.compartments[cIdx]
                    val sat = snap.compartmentSaturationPct[cIdx]
                    "${"%.3f".format(Locale.US, t.pInert)},${"%.1f".format(Locale.US, sat)}"
                }
                sb.appendLine(
                    "${"%.2f".format(Locale.US, snap.runtimeMin)}," +
                        "${"%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(snap.depthM, units))}," +
                        "${"%.3f".format(Locale.US, snap.pAmbBar)}," +
                        "${"%.1f".format(Locale.US, UnitsAndPressure.depthDisplay(snap.ceilingM, units))}," +
                        "${"%.1f".format(Locale.US, snap.gf99Pct)},$compCols"
                )
            }
        }
        sb.appendLine()
        sb.appendLine("DISCLAIMER,Planning purposes only. Cross-check independently and dive within training limits.")
        return sb.toString()
    }

    fun buildPdfBytes(dayResult: MultiDiveDayResult, activeDiveIndex: Int): ByteArray {
        val res = dayResult.diveResults.getOrNull(activeDiveIndex) ?: return ByteArray(0)
        val units = res.input.general.units
        val doc = PdfDocument()
        val pageWidth = 595
        val pageHeight = 842

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(6, 25, 46)
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 115, 150)
            textSize = 10.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(6, 25, 46)
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 35, 48)
            textSize = 9.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 28, 40)
            textSize = 8.8f
            typeface = Typeface.MONOSPACE
        }
        val monoBoldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(6, 25, 46)
            textSize = 9.0f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val linePaint = Paint().apply {
            color = Color.rgb(190, 205, 220)
            strokeWidth = 1f
        }
        val headerBgPaint = Paint().apply {
            color = Color.rgb(232, 243, 250)
            style = Paint.Style.FILL
        }
        val warnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(175, 40, 45)
            textSize = 8.8f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        try {
            // ---- Page 1: Waterproof Dive Slate + Runtime + Gas Consumption ----
            val page1 = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
            val c1 = page1.canvas
            var y = 38f

            c1.drawText("Extreme DECO Planner Ver 1.27.2", 32f, y, titlePaint)
        c1.drawText("by Ramy.DECO — Technical Decompression Slate", 32f, y + 15f, subtitlePaint)
        val dateText = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        c1.drawText("Dive ${activeDiveIndex + 1}/${dayResult.diveResults.size}  |  $dateText", 390f, y, bodyPaint)
        y += 28f
        c1.drawLine(32f, y, pageWidth - 32f, y, linePaint)
        y += 16f

        // Summary Box
        c1.drawRect(32f, y - 10f, pageWidth - 32f, y + 54f, headerBgPaint)
        val si = dayResult.surfaceIntervalsMin.getOrElse(activeDiveIndex) { 0.0 }
        c1.drawText(
            "Model: ${res.input.general.decoModel.shortLabel}  GF ${res.input.general.gfLow}/${res.input.general.gfHigh}  |  " +
                "Mode: ${res.input.general.planningType.label}  |  Salinity: ${res.env.salinityKgM3.roundToInt()} kg/m³  |  Alt: ${res.env.elevationM} m",
            40f, y + 5f, monoBoldPaint
        )
        c1.drawText(
            "Max Depth: ${UnitsAndPressure.formatDepth(res.maxDepthM, units)}   |   Runtime: ${UnitsAndPressure.formatRuntimeMmSs(res.totalRuntimeMin)}   |   " +
                "Deco: ${res.totalDecoMin.roundToInt()} min   |   TTS: ${res.ttsMin.roundToInt()} min   |   1st Stop: ${if (res.firstStopM > 0) UnitsAndPressure.formatDepth(res.firstStopM.toDouble(), units) else "NDL"}",
            40f, y + 23f, monoPaint
        )
        c1.drawText(
            "CNS: ${res.oxygen.startCnsPct.roundToInt()}% → ${res.oxygen.endCnsPct.roundToInt()}%   |   " +
                "Dive OTU: ${res.oxygen.diveOtu.roundToInt()} (Day: ${res.oxygen.dailyOtu.roundToInt()})   |   " +
                "SurfGF: ${res.endSurfaceGfPct.roundToInt()}%   |   SI Before: ${si.roundToInt()} min",
            40f, y + 41f, monoPaint
        )
        y += 72f

        // Runtime Schedule Table
        c1.drawText("DECOMPRESSION & RUNTIME SCHEDULE", 32f, y, sectionPaint)
        y += 14f
        c1.drawRect(32f, y - 10f, pageWidth - 32f, y + 5f, headerBgPaint)
        c1.drawText("ACTION      DEPTH     TIME   RUN    TTS    MIX       ppO2  END  DENS CEIL  GF99", 36f, y, monoBoldPaint)
        y += 14f

        val maxRowsPage1 = 26
        val displaySegs = if (res.input.general.planStyle == PlanStyle.COMPACT) {
            res.segments.filter { it.type == SegmentType.LEVEL || it.type == SegmentType.STOP || it.type == SegmentType.SWITCH || it.type == SegmentType.BREAK }
        } else {
            res.segments
        }
        displaySegs.take(maxRowsPage1).forEach { seg ->
            val depthLabel = if (seg.fromDepthM == seg.toDepthM) {
                UnitsAndPressure.formatDepth(seg.toDepthM, units)
            } else {
                "${UnitsAndPressure.depthDisplay(seg.fromDepthM, units).roundToInt()}→${UnitsAndPressure.depthDisplay(seg.toDepthM, units).roundToInt()}${units.depthUnit}"
            }
            val segTts = (res.totalRuntimeMin - seg.runtimeMin).coerceAtLeast(0.0)
            val rowStr = String.format(
                Locale.US,
                "%-11s %-9s %-6s %-6s %-6s %-9s %4.2f %4.0f %4.1f %4.0f  %3.0f%%",
                seg.type.label.take(11),
                depthLabel.take(9),
                UnitsAndPressure.formatRuntimeMmSs(seg.durationMin),
                UnitsAndPressure.formatRuntimeMmSs(seg.runtimeMin),
                UnitsAndPressure.formatRuntimeMmSs(segTts),
                seg.gas.displayName.take(9),
                seg.ppO2End,
                UnitsAndPressure.depthDisplay(seg.endM, units),
                seg.densityGL,
                UnitsAndPressure.depthDisplay(seg.ceilingM, units),
                seg.gf99Pct
            )
            val paint = if (seg.type == SegmentType.STOP || seg.type == SegmentType.SWITCH) monoBoldPaint else monoPaint
            c1.drawText(rowStr, 36f, y, paint)
            y += 12.5f
        }
        if (displaySegs.size > maxRowsPage1) {
            c1.drawText("... (${displaySegs.size - maxRowsPage1} additional segments in CSV export)", 36f, y, bodyPaint)
            y += 13f
        }

        y += 10f
        c1.drawLine(32f, y, pageWidth - 32f, y, linePaint)
        y += 16f

        // Gas Consumption Table
        c1.drawText("GAS CONSUMPTION & RESERVES", 32f, y, sectionPaint)
        y += 14f
        c1.drawRect(32f, y - 10f, pageWidth - 32f, y + 5f, headerBgPaint)
        c1.drawText("GAS MIX      ROLE     USED        RESERVE     TOTAL REQ   CYLINDER    TURN     MIN", 36f, y, monoBoldPaint)
        y += 14f
        for (gc in res.gasConsumption) {
            val row = String.format(
                Locale.US,
                "%-12s %-8s %-11s %-11s %-11s %-11s %-8s %-8s",
                gc.gas.displayName.take(12),
                gc.gas.role.label.take(8),
                UnitsAndPressure.formatVolume(gc.usedLitres, units),
                UnitsAndPressure.formatVolume(gc.reserveLitres, units),
                UnitsAndPressure.formatVolume(gc.totalRequiredLitres, units),
                UnitsAndPressure.formatVolume(gc.availableLitres, units),
                UnitsAndPressure.formatPressure(gc.turnPressureBar, units),
                UnitsAndPressure.formatPressure(gc.minPressureBar, units)
            )
            c1.drawText(row, 36f, y, if (gc.isSufficient) monoPaint else warnPaint)
            y += 13f
        }

        // Warnings section
        if (res.alerts.isNotEmpty() && y < pageHeight - 100f) {
            y += 10f
            c1.drawText("SAFETY ALERTS (${res.alerts.size})", 32f, y, sectionPaint)
            y += 13f
            res.alerts.take(6).forEach { alert ->
                val txt = "[${alert.level.label}] ${alert.message}"
                val paint = if (alert.level == AlertLevel.CRITICAL || alert.level == AlertLevel.HIGH) warnPaint else bodyPaint
                c1.drawText(txt.take(95), 36f, y, paint)
                y += 12f
            }
        }

        // Footer disclaimer
        c1.drawLine(32f, pageHeight - 46f, pageWidth - 32f, pageHeight - 46f, linePaint)
        c1.drawText(
            "WARNING: Risk of DCS & O2 toxicity exists for any dive. Training/planning only — cross-check independently.",
            32f, pageHeight - 32f, bodyPaint
        )
        doc.finishPage(page1)

        // ---- Page 2: 16-Compartment Tissue Saturation & Multi-Dive Day Summary ----
        val page2 = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 2).create())
        val c2 = page2.canvas
        var y2 = 42f
        c2.drawText("16-COMPARTMENT TISSUE SATURATION & MULTI-DIVE SUMMARY", 32f, y2, sectionPaint)
        y2 += 18f
        c2.drawRect(32f, y2 - 10f, pageWidth - 32f, y2 + 5f, headerBgPaint)
        c2.drawText("COMP   N2 HT    He HT    START N2   START He   END N2     END He     TOTAL    SURF SAT%", 36f, y2, monoBoldPaint)
        y2 += 15f

        val coeffs = Coefficients.tableFor(res.input.general.decoModel, res.input.general.useCompartment1b)
        val endSat = TissuesAndBuhlmann.compartmentSurfaceLoadPct(res.endTissues, res.env, coeffs)
        for (i in 0 until 16) {
            val cf = coeffs[i]
            val st = res.startTissues.compartments[i]
            val et = res.endTissues.compartments[i]
            val row = String.format(
                Locale.US,
                "%-6s %6.1fm  %6.2fm  %6.3f b   %6.3f b   %6.3f b   %6.3f b   %6.3f b  %5.1f%%",
                cf.label, cf.n2HalfTime, cf.heHalfTime,
                st.pN2, st.pHe, et.pN2, et.pHe, et.pInert, endSat[i]
            )
            c2.drawText(row, 36f, y2, monoPaint)
            y2 += 14.5f
        }

        if (dayResult.diveResults.size > 1) {
            y2 += 20f
            c2.drawText("MULTI-DIVE DAY PROGRESSION (${dayResult.diveResults.size} DIVES)", 32f, y2, sectionPaint)
            y2 += 16f
            c2.drawRect(32f, y2 - 10f, pageWidth - 32f, y2 + 5f, headerBgPaint)
            c2.drawText("DIVE   SI BEFORE   MAX DEPTH   RUNTIME   DECO TIME   CNS END   DAILY OTU   SURF GF", 36f, y2, monoBoldPaint)
            y2 += 15f
            dayResult.diveResults.forEachIndexed { idx, dr ->
                val siVal = dayResult.surfaceIntervalsMin.getOrElse(idx) { 0.0 }
                val row = String.format(
                    Locale.US,
                    "D%-5d %5.0f min   %-11s %-9s %5.0f min   %5.1f%%    %6.0f      %5.1f%%",
                    idx + 1, siVal,
                    UnitsAndPressure.formatDepth(dr.maxDepthM, units),
                    UnitsAndPressure.formatRuntimeMmSs(dr.totalRuntimeMin),
                    dr.totalDecoMin,
                    dr.oxygen.endCnsPct,
                    dr.oxygen.dailyOtu,
                    dr.endSurfaceGfPct
                )
                c2.drawText(row, 36f, y2, monoPaint)
                y2 += 14f
            }
        }

            c2.drawLine(32f, pageHeight - 46f, pageWidth - 32f, pageHeight - 46f, linePaint)
            c2.drawText("Extreme DECO Planner Ver 1.27.2 by Ramy.DECO — Page 2/2", 32f, pageHeight - 32f, bodyPaint)
            doc.finishPage(page2)

            val out = ByteArrayOutputStream()
            doc.writeTo(out)
            return out.toByteArray()
        } finally {
            doc.close()
        }
    }

    fun writeBytesToUri(context: Context, uri: Uri, bytes: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { stream: OutputStream ->
                stream.write(bytes)
                stream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun createShareIntent(context: Context, fileName: String, mimeType: String, bytes: ByteArray): Intent? {
        return try {
            val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(exportDir, fileName)
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Extreme DECO Planner Ver 1.27.2 — $fileName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (_: Exception) {
            null
        }
    }
}
