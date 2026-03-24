package com.voltron.dash.render

import android.graphics.*
import com.voltron.dash.ble.FarDriverData
import kotlin.math.*

/**
 * Shared renderer — draws the entire dashboard to any Canvas.
 * Used by both standalone DashboardView and Android Auto SurfaceCallback.
 */
object DashboardRenderer {

    // Colors (matching web/PyQt5 theme)
    private const val BG_DARK = 0xFF0F1E46.toInt()
    private const val BG_PANEL = 0xFF1E3264.toInt()
    private const val TEXT_PRI = 0xFFE6EBF5.toInt()
    private const val TEXT_DIM = 0xFF8CA0C8.toInt()
    private const val ACCENT_BLUE = 0xFF64C8FF.toInt()
    private const val ACCENT_GREEN = 0xFF32DC6E.toInt()
    private const val ACCENT_YELLOW = 0xFFFFD232.toInt()
    private const val ACCENT_RED = 0xFFFF4646.toInt()
    private const val ACCENT_ORANGE = 0xFFFF9632.toInt()
    private const val GAUGE_BG = 0xFF324678.toInt()
    private const val NEEDLE_COLOR = 0xFFFF5A46.toInt()

    private const val MAX_AMPS = 200f
    private const val MAX_RPM = 6000f
    private const val BATTERY_TOTAL_KWH = 7.992f

    // Reusable Paint objects
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val rectF = RectF()

    var glowPhase = 0f

    fun draw(canvas: Canvas, w: Int, h: Int, data: FarDriverData) {
        // Clear background
        canvas.drawColor(BG_DARK)

        // Advance animation
        glowPhase += 0.05f
        if (glowPhase > Math.PI.toFloat() * 2) glowPhase -= Math.PI.toFloat() * 2

        val pad = 4f
        val sidebarW = min(100f, w * 0.13f)
        val bottomH = min(48f, h * 0.12f)
        val batteryH = min(70f, h * 0.15f)
        val ampBarH = min(24f, h * 0.06f)

        val mainX = sidebarW + pad
        val mainW = w - sidebarW - pad * 2
        val topH = h - bottomH - batteryH - pad * 3

        // --- Sidebar ---
        drawSidebar(canvas, pad, pad, sidebarW - pad * 2, h - pad * 2, data)

        // --- Speed column (left 35%) and gauges column (right 65%) ---
        val speedColW = mainW * 0.35f
        val gaugesColW = mainW - speedColW - pad
        val gaugesX = mainX + speedColW + pad

        // Amperage bar
        drawAmperageBar(canvas, mainX, pad, speedColW, ampBarH, data.current, MAX_AMPS)

        // Big speed
        drawBigSpeed(canvas, mainX, pad + ampBarH + pad, speedColW, topH - ampBarH - pad, data.speedKmh.toInt())

        // Temp/usage row (top 32%)
        val tempRowH = topH * 0.32f
        val tempGaugeW = gaugesColW / 3f
        val tempR = min(tempGaugeW, tempRowH) * 0.35f
        val tempArcW = tempR * 0.14f

        drawTempGauge(canvas, gaugesX + tempGaugeW * 0.5f, pad + tempRowH * 0.5f,
            tempR, data.controllerTemp.toFloat(), 120f, "Ctrl", tempArcW)
        drawUsageGauge(canvas, gaugesX + tempGaugeW * 1.5f, pad + tempRowH * 0.5f,
            tempR, data.kwhUsed, 10f, tempArcW)
        drawTempGauge(canvas, gaugesX + tempGaugeW * 2.5f, pad + tempRowH * 0.5f,
            tempR, data.motorTemp.toFloat(), 120f, "Motor", tempArcW)

        // RPM gauge
        val rpmH = topH - tempRowH - pad
        val rpmCx = gaugesX + gaugesColW / 2f
        val rpmCy = pad + tempRowH + pad + rpmH / 2f
        val rpmR = min(gaugesColW, rpmH) * 0.36f
        val rpmArcW = rpmR * 0.08f
        drawArcGauge(canvas, rpmCx, rpmCy, rpmR, data.rpm.toFloat(), MAX_RPM, "RPM", 7, rpmArcW)

        // --- Battery row ---
        val battY = topH + pad * 2
        drawBatteryBar(canvas, mainX, battY, mainW * 0.7f, batteryH, data.soc, data.voltage)
        drawRangeDisplay(canvas, mainX + mainW * 0.7f + pad, battY, mainW * 0.3f - pad, batteryH, data.rangeKm)

        // --- Bottom row ---
        val botY = h - bottomH - pad
        val gearW = min(150f, mainW * 0.22f)
        drawGearIndicator(canvas, mainX, botY, gearW, bottomH, data.gear)

        val odoX = mainX + gearW + pad
        val odoW = mainW - gearW - pad
        drawOdometer(canvas, odoX, botY, odoW, bottomH,
            data.totalKm, data.sessionKm, data.sessionTime, data.totalHours)
    }

    // --- Sidebar ---
    private fun drawSidebar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, data: FarDriverData) {
        val panelH = (h - 20f - 4f * 2) / 3f

        // Brand label
        textPaint.color = 0xFF6488CC.toInt()
        textPaint.textSize = min(10f, w * 0.11f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("VOLTRON.NL", x + w / 2, y + 12f, textPaint)

        val vColor = when {
            data.voltage < 60 -> ACCENT_RED
            data.voltage < 62 -> ACCENT_ORANGE
            else -> TEXT_PRI
        }
        drawValuePanel(canvas, x, y + 18f, w, panelH,
            formatNumber(data.voltage, 1), "V", vColor)
        drawValuePanel(canvas, x, y + 18f + panelH + 4f, w, panelH,
            formatNumber(data.current, 1), "A", TEXT_PRI)
        drawValuePanel(canvas, x, y + 18f + (panelH + 4f) * 2, w, panelH,
            data.power.toInt().toString(), "W", TEXT_PRI)
    }

    private fun formatNumber(v: Float, decimals: Int): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString()
        else String.format("%.${decimals}f", v)
    }

    // --- Value Panel ---
    private fun drawValuePanel(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               value: String, unit: String, vColor: Int) {
        paint.color = BG_PANEL
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, 6f, 6f, paint)

        textPaint.color = vColor
        textPaint.textSize = min(20f, h * 0.35f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value, x + w / 2, y + h * 0.5f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = min(11f, h * 0.16f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(unit, x + w / 2, y + h * 0.8f, textPaint)
    }

    // --- Big Speed Display ---
    private fun drawBigSpeed(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, speed: Int) {
        val fontSize = max(28f, min(w, h) * 0.42f)

        textPaint.color = TEXT_PRI
        textPaint.textSize = fontSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(speed.toString(), x + w / 2, y + h * 0.5f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = max(10f, fontSize * 0.2f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("km/h", x + w / 2, y + h * 0.5f + fontSize * 0.45f, textPaint)
    }

    // --- Amperage Bar ---
    private fun drawAmperageBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                amps: Float, maxAmps: Float) {
        val r = h / 2f

        // Track background
        paint.color = GAUGE_BG
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)

        // Fill
        val ratio = min(abs(amps) / maxAmps, 1f)
        val fillW = max(w * ratio, w * 0.02f)
        val col = ampColorInt(amps, maxAmps)
        val glow = 0.7f + 0.3f * sin(glowPhase)

        // Gradient fill
        val shader = LinearGradient(x, y, x + fillW, y,
            intArrayOf(adjustAlpha(col, (0.6f * glow)), adjustAlpha(col, glow), col),
            floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
        rectF.set(x, y, x + fillW, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)
        paint.shader = null

        // Glow halo
        if (ratio > 0.03f) {
            val edgeX = x + fillW
            val glowR = h * 0.8f
            val radialGrad = RadialGradient(edgeX, y + h / 2, glowR,
                adjustAlpha(col, (0.4f * glow)), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.shader = radialGrad
            canvas.drawCircle(edgeX, y + h / 2, glowR, paint)
            paint.shader = null
        }
    }

    // --- Arc Gauge (RPM) ---
    private fun drawArcGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                             value: Float, maxVal: Float, unit: String, numTicks: Int, arcWidth: Float) {
        val startAngle = 135f // canvas degrees (0=right, clockwise)
        val sweepAngle = 270f
        val ratio = min(value / maxVal, 1f)

        // Background circle
        paint.color = BG_PANEL
        canvas.drawCircle(cx, cy, radius + arcWidth, paint)

        // Arc background
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc
        val arcCol = when {
            ratio < 0.6f -> ACCENT_GREEN
            ratio < 0.8f -> ACCENT_YELLOW
            else -> ACCENT_RED
        }
        if (ratio > 0) {
            paint.color = arcCol
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Needle
        val needleAngle = Math.toRadians((startAngle + sweepAngle * ratio).toDouble())
        val needleLen = radius * 0.75f
        val nx = cx + needleLen * cos(needleAngle).toFloat()
        val ny = cy + needleLen * sin(needleAngle).toFloat()

        paint.color = NEEDLE_COLOR
        paint.strokeWidth = max(2f, radius * 0.03f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, nx, ny, paint)
        paint.style = Paint.Style.FILL

        // Center dot
        paint.color = NEEDLE_COLOR
        canvas.drawCircle(cx, cy, radius * 0.06f, paint)

        // Value text
        textPaint.color = TEXT_PRI
        textPaint.textSize = max(12f, radius * 0.35f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value.toInt().toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Unit label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(8f, radius * 0.15f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(unit, cx, cy + radius * 0.35f + textPaint.textSize, textPaint)

        // Tick labels
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(7f, radius * 0.1f)
        for (i in 0 until numTicks) {
            val frac = i.toFloat() / (numTicks - 1)
            val a = Math.toRadians((startAngle + sweepAngle * frac).toDouble())
            val lx = cx + (radius + arcWidth * 2.5f) * cos(a).toFloat()
            val ly = cy + (radius + arcWidth * 2.5f) * sin(a).toFloat()
            canvas.drawText((maxVal * frac).toInt().toString(), lx, ly + textPaint.textSize * 0.35f, textPaint)
        }
    }

    // --- Temp Gauge ---
    private fun drawTempGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                              temp: Float, maxTemp: Float, label: String, arcWidth: Float) {
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = min(max(temp, 0f) / maxTemp, 1f)
        val col = tempColorInt(temp.toInt())

        // Arc background
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc
        if (ratio > 0) {
            paint.color = col
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Temp value
        textPaint.color = col
        textPaint.textSize = max(10f, radius * 0.5f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${temp.toInt()}\u00b0", cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(7f, radius * 0.25f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(label, cx, cy + radius * 0.55f, textPaint)
    }

    // --- Usage Gauge ---
    private fun drawUsageGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                               kwh: Float, maxKwh: Float, arcWidth: Float) {
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = min(kwh / maxKwh, 1f)

        val col = when {
            ratio < 0.5f -> ACCENT_GREEN
            ratio < 0.75f -> ACCENT_YELLOW
            else -> ACCENT_ORANGE
        }

        // Arc background
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc
        if (ratio > 0) {
            paint.color = col
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Value
        textPaint.color = col
        textPaint.textSize = max(10f, radius * 0.45f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val kwhStr = if (kwh < 10) String.format("%.2f", kwh) else String.format("%.1f", kwh)
        canvas.drawText(kwhStr, cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(7f, radius * 0.25f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("kWh", cx, cy + radius * 0.55f, textPaint)
    }

    // --- Battery Bar ---
    private fun drawBatteryBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               soc: Int, voltage: Float) {
        val col = socColorInt(soc)
        val battW = w * 0.65f
        val battH = min(50f, h - 8f)
        val battY = y + (h - battH) / 2
        val tipW = 8f
        val tipH = min(22f, battH * 0.5f)
        val border = 2.5f
        val cr = 4f

        // Battery outline
        paint.color = col
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        rectF.set(x, battY, x + battW, battY + battH)
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        // Tip
        val tipX = x + battW + 1f
        val tipY2 = battY + (battH - tipH) / 2
        paint.color = col
        rectF.set(tipX, tipY2, tipX + tipW, tipY2 + tipH)
        canvas.drawRoundRect(rectF, 2f, 2f, paint)

        // Fill inside
        val inset = border + 2f
        val fillMaxW = battW - inset * 2
        val fillW = fillMaxW * soc / 100f
        if (fillW > 0) {
            val shader = LinearGradient(x + inset, battY + inset, x + inset, battY + battH - inset,
                intArrayOf(col, adjustAlpha(col, 0.78f), col),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            paint.shader = shader
            rectF.set(x + inset, battY + inset, x + inset + fillW, battY + battH - inset)
            canvas.drawRoundRect(rectF, 2f, 2f, paint)
            paint.shader = null
        }

        // SOC text
        val textX = x + battW + tipW + 10f
        val remainKwh = String.format("%.1f", soc / 100f * BATTERY_TOTAL_KWH)

        textPaint.color = col
        textPaint.textSize = min(18f, h * 0.35f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("$soc%", textX, battY + battH * 0.35f, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = min(13f, h * 0.22f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("${remainKwh}kWh", textX + 48f, battY + battH * 0.35f, textPaint)

        // Voltage
        textPaint.color = TEXT_DIM
        textPaint.textSize = min(13f, h * 0.22f)
        canvas.drawText(String.format("%.1fV", voltage), textX, battY + battH * 0.72f, textPaint)
    }

    // --- Range Display ---
    private fun drawRangeDisplay(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, rangeKm: Int) {
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = min(22f, h * 0.45f)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        if (rangeKm > 0) {
            textPaint.color = ACCENT_BLUE
            canvas.drawText("~$rangeKm km", x + w / 2, y + h / 2 + textPaint.textSize * 0.35f, textPaint)
        } else {
            textPaint.color = TEXT_DIM
            canvas.drawText("--- km", x + w / 2, y + h / 2 + textPaint.textSize * 0.35f, textPaint)
        }
    }

    // --- Gear Indicator ---
    private fun drawGearIndicator(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, gear: String) {
        val name = when (gear) { "F" -> "SPORT"; "R" -> "REVERSE"; else -> "NEUTRAL" }
        val col = when (gear) { "F" -> ACCENT_GREEN; "R" -> ACCENT_RED; else -> ACCENT_BLUE }

        paint.color = col
        rectF.set(x + 2, y + 2, x + w - 2, y + h - 2)
        canvas.drawRoundRect(rectF, 8f, 8f, paint)

        textPaint.color = Color.WHITE
        textPaint.textSize = min(20f, h * 0.42f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(name, x + w / 2, y + h / 2 + textPaint.textSize * 0.35f, textPaint)
    }

    // --- Odometer ---
    private fun drawOdometer(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                             totalKm: Float, sessionKm: Float, sessionSecs: Int, totalHours: Float) {
        paint.color = BG_PANEL
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, 6f, 6f, paint)

        val colW = w / 4f
        val hrs = sessionSecs / 3600
        val mins = (sessionSecs % 3600) / 60
        val timeStr = "$hrs:${mins.toString().padStart(2, '0')}"

        val cols = arrayOf(
            Triple("Total", String.format("%.1fkm", totalKm), TEXT_PRI),
            Triple("Trip", String.format("%.1fkm", sessionKm), ACCENT_BLUE),
            Triple("Session", timeStr, ACCENT_BLUE),
            Triple("Hours", String.format("%.1fh", totalHours), TEXT_PRI)
        )

        for (i in cols.indices) {
            val cx = x + i * colW + 8f
            textPaint.textAlign = Paint.Align.LEFT

            textPaint.color = TEXT_DIM
            textPaint.textSize = min(9f, h * 0.2f)
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText(cols[i].first, cx, y + 14f, textPaint)

            textPaint.color = cols[i].third
            textPaint.textSize = min(13f, h * 0.3f)
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(cols[i].second, cx, y + h * 0.7f, textPaint)
        }
    }

    // --- Color helpers ---
    private fun ampColorInt(amps: Float, maxAmps: Float): Int {
        val ratio = abs(amps) / maxAmps
        if (amps < 0 || ratio < 0.05f) return ACCENT_BLUE
        if (ratio < 0.3f) return lerpColorInt(ACCENT_BLUE, ACCENT_GREEN, ratio / 0.3f)
        if (ratio < 0.6f) return lerpColorInt(ACCENT_GREEN, ACCENT_YELLOW, (ratio - 0.3f) / 0.3f)
        return lerpColorInt(ACCENT_YELLOW, ACCENT_RED, min((ratio - 0.6f) / 0.4f, 1f))
    }

    private fun socColorInt(soc: Int): Int = when {
        soc > 50 -> ACCENT_GREEN
        soc > 25 -> ACCENT_YELLOW
        soc > 10 -> ACCENT_ORANGE
        else -> ACCENT_RED
    }

    private fun tempColorInt(t: Int): Int = when {
        t >= 85 -> ACCENT_RED
        t >= 70 -> ACCENT_ORANGE
        else -> ACCENT_GREEN
    }

    private fun lerpColorInt(c1: Int, c2: Int, t: Float): Int {
        val r = ((Color.red(c1) + t * (Color.red(c2) - Color.red(c1))).toInt()).coerceIn(0, 255)
        val g = ((Color.green(c1) + t * (Color.green(c2) - Color.green(c1))).toInt()).coerceIn(0, 255)
        val b = ((Color.blue(c1) + t * (Color.blue(c2) - Color.blue(c1))).toInt()).coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
