package com.voltron.dash.render

import android.content.Context
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import androidx.core.content.ContextCompat
import com.voltron.dash.R
import com.voltron.dash.ble.FarDriverData
import com.voltron.dash.ble.JkBmsData
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

/**
 * Shared renderer — draws the entire dashboard to any Canvas.
 * Used by both standalone DashboardView and Android Auto SurfaceCallback.
 */
object DashboardRenderer {

    // Modern dark theme colors
    private const val BG_DARK = 0xFF0A1628.toInt()
    private const val BG_CARD = 0xFF162040.toInt()
    private const val BG_CARD_LIGHT = 0xFF1C2D54.toInt()
    private const val TEXT_PRI = 0xFFE8EDF7.toInt()
    private const val TEXT_DIM = 0xFF7A92BE.toInt()
    private const val ACCENT_BLUE = 0xFF4DB8FF.toInt()
    private const val ACCENT_GREEN = 0xFF2EE866.toInt()
    private const val ACCENT_YELLOW = 0xFFFFD232.toInt()
    private const val ACCENT_RED = 0xFFFF4060.toInt()
    private const val ACCENT_ORANGE = 0xFFFF8C32.toInt()
    private const val GAUGE_BG = 0xFF2C4070.toInt()
    private const val NEEDLE_COLOR = 0xFFFF5A46.toInt()
    private const val CARD_BORDER = 0xFF2A4080.toInt()

    var maxAmps = 150f
    var maxRpm = 5000f
    private const val BATTERY_TOTAL_KWH = 7.992f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    var glowPhase = 0f
    var speedFactor = 0.38f
    var resetTrip = false
    var resetAll = false
    var fdStatus = "FD: --"
    var bmsStatus = "BMS: --"
    private var logoBitmap: Bitmap? = null

    fun init(context: Context) {
        if (logoBitmap == null) {
            val drawable = ContextCompat.getDrawable(context, R.drawable.logo_voltron)
            logoBitmap = (drawable as? BitmapDrawable)?.bitmap
                ?: BitmapFactory.decodeResource(context.resources, R.drawable.logo_voltron)
        }
    }

    var gearButtonRect = RectF()
    var resetButtonRect = RectF()
    var bmsButtonRect = RectF()
    var demoMode = false

    private val demoBmsData = JkBmsData(
        cellVoltages = listOf(3.921f, 3.918f, 3.925f, 3.920f, 3.917f, 3.923f, 3.919f, 3.922f,
            3.916f, 3.924f, 3.921f, 3.918f, 3.925f, 3.920f, 3.917f, 3.923f, 3.919f),
        cellCount = 17,
        totalVoltage = 66.7f,
        current = -2.5f,
        power = -166.75f,
        soc = 85,
        temp1 = 22.4f,
        temp2 = 21.8f,
        mosfetTemp = 25.1f,
        remainingAh = 102f,
        nominalAh = 120f,
        cycles = 12,
        soh = 98,
        cellDelta = 0.009f,
        minCell = 3.916f,
        maxCell = 3.925f,
        connected = true
    )

    fun draw(canvas: Canvas, w: Int, h: Int, data: FarDriverData, bmsData: JkBmsData = JkBmsData()) {
        val effectiveBms = if (demoMode && !bmsData.connected) demoBmsData else bmsData
        canvas.drawColor(BG_DARK)

        glowPhase += 0.05f
        if (glowPhase > Math.PI.toFloat() * 2) glowPhase -= Math.PI.toFloat() * 2

        val pad = 5f
        val cr = 10f
        val wf = w.toFloat()
        val hf = h.toFloat()

        // Column widths (proportional)
        val ampBarW = wf * 0.04f
        val rangeBarW = ampBarW
        val sidebarW = wf * 0.10f
        val gaugeColW = wf * 0.12f  // right column

        // Row heights — single bottom strip
        val bottomH = hf * 0.14f
        val topH = hf - bottomH - pad * 3

        // X positions
        val sidebarX = ampBarW + pad * 2
        val mainX = sidebarX + sidebarW + pad
        val gaugeColX = wf - rangeBarW - pad * 2 - gaugeColW
        val centerW = gaugeColX - mainX - pad
        val speedW = centerW * 0.48f
        val dualW = centerW - speedW - pad
        val dualX = mainX + speedW + pad
        val fullRowX = mainX
        val fullRowW = wf - mainX - rangeBarW - pad * 2

        // === VERTICAL BARS (full height) ===
        drawVerticalAmperageBar(canvas, pad, pad, ampBarW, hf - pad * 2, data.current, maxAmps)
        drawVerticalRangeBar(canvas, wf - rangeBarW - pad, pad, rangeBarW, hf - pad * 2, data.rangeKm, data.soc)

        // === SIDEBAR (V / A / W) — full height ===
        drawSidebar(canvas, sidebarX, pad, sidebarW, hf - pad * 2, data, cr)

        // === BIG SPEED (left of center) ===
        val adjustedSpeed = (data.speedKmh * speedFactor).toInt()
        drawBigSpeed(canvas, mainX, pad, speedW, topH, adjustedSpeed, data.totalKm, data.totalHours, data.kwhUsed, cr)

        // === DUAL RPM+AMP GAUGE (right of center) ===
        drawDualGauge(canvas, dualX, pad, dualW, topH, data.rpm.toFloat(), data.current, data.rangeKm, cr)

        // === RIGHT GAUGE COLUMN (Ctrl° / Mot° / BAT° / kWh bar gauges) ===
        drawGaugeColumn(canvas, gaugeColX, pad, gaugeColW, topH, data, effectiveBms, cr)

        // === BOTTOM ROW (battery + gear + trip + BMS + logo) ===
        val botY = pad + topH + pad
        drawBottomRow(canvas, fullRowX, botY, fullRowW, bottomH, data, effectiveBms, cr)

        // === BLE STATUS (small debug text, top-left) ===
        textPaint.textSize = 10f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.MONOSPACE
        val fdCol = if (fdStatus.contains("Live")) ACCENT_GREEN else ACCENT_YELLOW
        val bmsCol = if (bmsStatus.contains("Live")) ACCENT_GREEN else ACCENT_YELLOW
        textPaint.color = fdCol
        canvas.drawText(fdStatus, sidebarX, hf - 4f, textPaint)
        textPaint.color = bmsCol
        canvas.drawText(bmsStatus, sidebarX + wf * 0.15f, hf - 4f, textPaint)
    }

    // ============================================================
    // SIDEBAR — V / A / W cards
    // ============================================================
    private fun drawSidebar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                            data: FarDriverData, cr: Float) {
        val gap = 4f
        val panelH = (h - gap * 2) / 3f

        val vColor = when {
            data.voltage < 60 -> ACCENT_RED
            data.voltage < 62 -> ACCENT_ORANGE
            else -> TEXT_PRI
        }
        drawValueCard(canvas, x, y, w, panelH, formatNumber(data.voltage, 1), "Volts", "\u26A1", vColor, ACCENT_YELLOW, cr)
        drawValueCard(canvas, x, y + panelH + gap, w, panelH, formatNumber(data.current, 1), "Amps", "\u2301", TEXT_PRI, ACCENT_BLUE, cr)
        drawValueCard(canvas, x, y + (panelH + gap) * 2, w, panelH, data.power.toInt().toString(), "Watts", "\u2622", TEXT_PRI, ACCENT_ORANGE, cr)
    }

    private fun drawValueCard(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              value: String, label: String, icon: String,
                              vColor: Int, iconColor: Int, cr: Float) {
        // Card background with subtle gradient
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        // Subtle border
        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        // Icon (top)
        textPaint.color = iconColor
        textPaint.textSize = min(30f, h * 0.28f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT
        canvas.drawText(icon, x + w / 2, y + h * 0.24f, textPaint)

        // Value (center, bigger)
        textPaint.color = vColor
        textPaint.textSize = min(48f, h * 0.50f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value, x + w / 2, y + h * 0.62f, textPaint)

        // Label (bottom)
        textPaint.color = TEXT_DIM
        textPaint.textSize = min(16f, h * 0.20f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(label, x + w / 2, y + h * 0.85f, textPaint)
    }

    // ============================================================
    // BIG SPEED + odometer
    // ============================================================
    private fun drawBigSpeed(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                             speed: Int, totalKm: Float, totalHours: Float, kwhUsed: Float, cr: Float) {
        // Card background
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        // Speed digits
        val fontSize = max(36f, min(w * 0.45f, h * 0.48f))
        textPaint.color = TEXT_PRI
        textPaint.textSize = fontSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(speed.toString(), x + w / 2, y + h * 0.48f, textPaint)

        // km/h label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(11f, fontSize * 0.18f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("km/h", x + w / 2, y + h * 0.48f + fontSize * 0.38f, textPaint)

        // Odometer line
        val odoSize = max(9f, fontSize * 0.13f)
        textPaint.color = 0xFF4A6A9E.toInt()
        textPaint.textSize = odoSize
        textPaint.typeface = Typeface.MONOSPACE
        val odoStr = String.format("%.1f km  %.1f h  %.2f kWh", totalKm, totalHours, kwhUsed)
        canvas.drawText(odoStr, x + w / 2, y + h * 0.88f, textPaint)
    }

    // ============================================================
    // DUAL GAUGE — RPM (outer) + Amperage (inner)
    // ============================================================
    private fun drawDualGauge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              rpm: Float, amps: Float, rangeKm: Int, cr: Float) {
        // Card
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        // RPM label (above gauge)
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(10f, h * 0.06f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val labelH = textPaint.textSize + 6f
        canvas.drawText("RPM", x + w / 2, y + labelH - 2f, textPaint)

        val cx = x + w / 2
        val cy = y + labelH + (h - labelH) * 0.46f
        val outerR = min(w, h - labelH) * 0.32f
        val startAngle = 135f
        val sweepAngle = 270f

        // --- Outer arc: RPM ---
        val outerArcW = outerR * 0.10f
        val rpmRatio = min(rpm / maxRpm, 1f)
        val rpmCol = when {
            rpmRatio < 0.6f -> ACCENT_GREEN
            rpmRatio < 0.8f -> ACCENT_YELLOW
            else -> ACCENT_RED
        }

        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerArcW
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)
        if (rpmRatio > 0) {
            paint.color = rpmCol
            canvas.drawArc(rectF, startAngle, sweepAngle * rpmRatio, false, paint)
        }

        // --- Inner arc: Amperage (visual only) ---
        val innerR = outerR * 0.72f
        val innerArcW = outerR * 0.10f
        val ampRatio = min(abs(amps) / maxAmps, 1f)
        val ampCol = ampColorInt(amps, maxAmps)

        paint.color = GAUGE_BG
        paint.strokeWidth = innerArcW
        rectF.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)
        if (ampRatio > 0) {
            paint.color = ampCol
            canvas.drawArc(rectF, startAngle, sweepAngle * ampRatio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // --- Needle (follows RPM) ---
        val needleAngle = Math.toRadians((startAngle + sweepAngle * rpmRatio).toDouble())
        val needleLen = outerR * 0.60f
        val nx = cx + needleLen * cos(needleAngle).toFloat()
        val ny = cy + needleLen * sin(needleAngle).toFloat()
        paint.color = NEEDLE_COLOR
        paint.strokeWidth = max(2f, outerR * 0.03f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, nx, ny, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, outerR * 0.05f, paint)

        // --- RPM value (center of gauge) ---
        textPaint.color = TEXT_PRI
        textPaint.textSize = max(14f, outerR * 0.34f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(rpm.toInt().toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)

        // --- Tick labels (RPM outer) ---
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, outerR * 0.10f)
        textPaint.typeface = Typeface.MONOSPACE
        for (i in 0..5) {
            val frac = i.toFloat() / 5f
            val a = Math.toRadians((startAngle + sweepAngle * frac).toDouble())
            val lx = cx + (outerR + outerArcW * 2.2f) * cos(a).toFloat()
            val ly = cy + (outerR + outerArcW * 2.2f) * sin(a).toFloat()
            canvas.drawText((maxRpm * frac).toInt().toString(), lx, ly + textPaint.textSize * 0.35f, textPaint)
        }

        // --- Range km (bottom of card, same line as odometer in speed card) ---
        val rangeStr = if (rangeKm > 0) "~${rangeKm}" else "---"
        val rangeCol = when {
            rangeKm <= 0 -> TEXT_DIM
            rangeKm < 15 -> ACCENT_RED
            rangeKm < 30 -> ACCENT_ORANGE
            else -> ACCENT_GREEN
        }
        textPaint.color = rangeCol
        textPaint.textSize = max(11f, outerR * 0.22f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(rangeStr, cx, y + h * 0.85f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = max(7f, outerR * 0.13f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("km remain", cx, y + h * 0.94f, textPaint)
    }

    // ============================================================
    // RIGHT GAUGE COLUMN — Ctrl° / Mot° / kWh as ring/donut gauges
    // ============================================================
    private fun drawGaugeColumn(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                data: FarDriverData, bmsData: JkBmsData, cr: Float) {
        // Card background
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        val innerPad = 6f
        val gap = 4f

        // Time & date at top
        val now = Calendar.getInstance()
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now.time)
        val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(now.time)

        val timeSize = max(12f, w * 0.18f)
        val dateSize = max(6f, w * 0.09f)
        val clockH = timeSize + dateSize + 8f

        textPaint.color = TEXT_PRI
        textPaint.textSize = timeSize
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(timeStr, x + w / 2, y + innerPad + timeSize, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = dateSize
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(dateStr, x + w / 2, y + innerPad + timeSize + dateSize + 4f, textPaint)

        // Gauges below time
        val gaugeY = y + innerPad + clockH + 4f
        val gaugeH = h - innerPad * 2 - clockH - 4f
        val slotCount = if (bmsData.connected) 4 else 3
        val slotH = (gaugeH - gap * (slotCount - 1)) / slotCount

        var slotIdx = 0

        // ECU temp
        drawRingGauge(canvas, x, gaugeY + slotIdx * (slotH + gap), w, slotH,
            "ECU", "${data.controllerTemp}\u00b0", data.controllerTemp / 120f,
            tempColorInt(data.controllerTemp))
        slotIdx++

        // Motor temp
        drawRingGauge(canvas, x, gaugeY + slotIdx * (slotH + gap), w, slotH,
            "MOT", "${data.motorTemp}\u00b0", data.motorTemp / 120f,
            tempColorInt(data.motorTemp))
        slotIdx++

        // BAT temp (only when BMS connected)
        if (bmsData.connected) {
            val avgBatTemp = ((bmsData.temp1 + bmsData.temp2) / 2f)
            drawRingGauge(canvas, x, gaugeY + slotIdx * (slotH + gap), w, slotH,
                "BAT", "${avgBatTemp.toInt()}\u00b0", avgBatTemp / 60f,
                tempColorInt(avgBatTemp.toInt()))
            slotIdx++
        }

        // kWh dual gauge — outer: remaining, inner: used
        drawDualKwhGauge(canvas, x, gaugeY + slotIdx * (slotH + gap), w, slotH,
            data.soc, data.kwhUsed)
    }

    // Dual ring kWh gauge — outer=remaining, inner=used
    private fun drawDualKwhGauge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                  soc: Int, kwhUsed: Float) {
        val labelSpace = max(10f, h * 0.18f)
        val ringArea = h - labelSpace
        val outerR = min(w * 0.28f, ringArea * 0.38f)
        val outerW = max(3f, outerR * 0.16f)
        val innerR = outerR * 0.68f
        val innerW = max(2f, outerR * 0.14f)
        val cx = x + w / 2
        val cy = y + ringArea * 0.50f
        val startAngle = 135f
        val sweepAngle = 270f

        val remainKwh = soc / 100f * BATTERY_TOTAL_KWH
        val remainRatio = (soc / 100f).coerceIn(0f, 1f)
        val usedRatio = (kwhUsed / BATTERY_TOTAL_KWH).coerceIn(0f, 1f)
        val glow = 0.7f + 0.3f * sin(glowPhase)

        // --- Outer ring: remaining kWh ---
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = outerW
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        if (remainRatio > 0) {
            paint.color = adjustAlpha(ACCENT_BLUE, glow)
            canvas.drawArc(rectF, startAngle, sweepAngle * remainRatio, false, paint)
        }

        // --- Inner ring: used kWh ---
        paint.color = GAUGE_BG
        paint.strokeWidth = innerW
        rectF.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        if (usedRatio > 0) {
            val usedCol = when {
                usedRatio < 0.5f -> ACCENT_GREEN
                usedRatio < 0.75f -> ACCENT_YELLOW
                else -> ACCENT_ORANGE
            }
            paint.color = adjustAlpha(usedCol, glow)
            canvas.drawArc(rectF, startAngle, sweepAngle * usedRatio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Remaining value (center)
        textPaint.color = ACCENT_BLUE
        textPaint.textSize = max(7f, outerR * 0.40f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(String.format("%.1f", remainKwh), cx, cy - outerR * 0.04f, textPaint)

        // Used value (below remaining)
        val usedCol = when {
            usedRatio < 0.5f -> ACCENT_GREEN
            usedRatio < 0.75f -> ACCENT_YELLOW
            else -> ACCENT_ORANGE
        }
        textPaint.color = usedCol
        textPaint.textSize = max(5f, outerR * 0.26f)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val usedStr = if (kwhUsed < 10) String.format("-%.1f", kwhUsed) else String.format("-%.0f", kwhUsed)
        canvas.drawText(usedStr, cx, cy + outerR * 0.28f, textPaint)

        // Label (just below rings)
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, min(labelSpace * 0.65f, outerR * 0.24f))
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("kWh", cx, cy + outerR + outerW + textPaint.textSize + 4f, textPaint)
    }

    // Ring/donut gauge — centered, label below
    private fun drawRingGauge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              label: String, valueStr: String, ratio: Float, color: Int) {
        val clamped = ratio.coerceIn(0f, 1f)
        val labelSpace = max(10f, h * 0.18f)
        val ringArea = h - labelSpace
        val ringR = min(w * 0.28f, ringArea * 0.38f)
        val ringW = max(3f, ringR * 0.20f)
        val cx = x + w / 2
        val cy = y + ringArea * 0.50f
        val startAngle = 135f
        val sweepAngle = 270f

        // Background arc
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ringW
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc with glow
        if (clamped > 0) {
            val glow = 0.7f + 0.3f * sin(glowPhase)
            paint.color = adjustAlpha(color, glow)
            canvas.drawArc(rectF, startAngle, sweepAngle * clamped, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Value text (inside ring)
        textPaint.color = color
        textPaint.textSize = max(7f, ringR * 0.48f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(valueStr, cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label (just below ring)
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, min(labelSpace * 0.65f, ringR * 0.28f))
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(label, cx, cy + ringR + ringW + textPaint.textSize + 4f, textPaint)
    }

    // ============================================================
    // BOTTOM ROW — single strip: Gear | Battery+SOC | Trip/Session | Logo | Settings
    // ============================================================
    private fun drawBottomRow(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              data: FarDriverData, bmsData: JkBmsData, cr: Float) {
        // Full row card background
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        val innerPad = 6f
        val gap = 6f
        val col = socColorInt(data.soc)

        // --- Gear indicator (left) ---
        val gearW = w * 0.12f
        drawGearIndicator(canvas, x + innerPad, y + 2f, gearW, h - 4f, data.gear, cr)

        // --- Settings button (right end) ---
        val settingsW = h - 4f
        val settingsX = x + w - settingsW - innerPad
        drawSettingsButton(canvas, settingsX, y + 2f, settingsW, h - 4f, cr)
        gearButtonRect.set(settingsX, y + 2f, settingsX + settingsW, y + h - 2f)

        // --- Logo (left of settings) ---
        var contentEndX = settingsX - gap
        logoBitmap?.let { bmp ->
            val logoH = h * 0.65f
            val logoW = logoH * bmp.width / bmp.height
            val logoX = settingsX - gap - logoW
            val logoY = y + (h - logoH) / 2
            rectF.set(logoX, logoY, logoX + logoW, logoY + logoH)
            canvas.drawBitmap(bmp, null, rectF, paint)
            contentEndX = logoX - gap
        }

        // --- Battery icon + SOC% (longer, after gear) ---
        val battSectionX = x + innerPad + gearW + gap
        val battIconW = w * 0.34f
        val battH = h - 8f
        val battY = y + (h - battH) / 2
        val tipW = 7f
        val tipH = min(20f, battH * 0.50f)
        val border = 2f
        val bcr = 4f

        // Battery outline
        paint.color = col
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = border
        rectF.set(battSectionX, battY, battSectionX + battIconW, battY + battH)
        canvas.drawRoundRect(rectF, bcr, bcr, paint)
        paint.style = Paint.Style.FILL

        // Tip
        val tipX2 = battSectionX + battIconW + 1f
        val tipY2 = battY + (battH - tipH) / 2
        paint.color = col
        rectF.set(tipX2, tipY2, tipX2 + tipW, tipY2 + tipH)
        canvas.drawRoundRect(rectF, 2f, 2f, paint)

        // Fill inside
        val inset = border + 2f
        val fillMaxW = battIconW - inset * 2
        val fillW = fillMaxW * data.soc / 100f
        if (fillW > 0) {
            val fillGrad = LinearGradient(battSectionX + inset, battY + inset,
                battSectionX + inset, battY + battH - inset,
                intArrayOf(adjustAlpha(col, 0.95f), adjustAlpha(col, 0.60f), adjustAlpha(col, 0.95f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            paint.shader = fillGrad
            rectF.set(battSectionX + inset, battY + inset,
                battSectionX + inset + fillW, battY + battH - inset)
            canvas.drawRoundRect(rectF, 2f, 2f, paint)
            paint.shader = null
        }

        // SOC %
        val socX = battSectionX + battIconW + tipW + 6f
        textPaint.color = col
        textPaint.textSize = min(20f, h * 0.42f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${data.soc}%", socX, y + h / 2 + textPaint.textSize * 0.35f, textPaint)

        // Battery area = BMS tap target
        val socTextW = textPaint.measureText("${data.soc}%")
        bmsButtonRect.set(battSectionX, y, socX + socTextW, y + h)

        // --- Trip / Session + Reset (center area) ---
        val tripX = socX + socTextW + gap * 2
        val availW = contentEndX - tripX
        val tripW = availW * 0.42f
        val sessionW = availW * 0.42f
        val hrs = data.sessionTime / 3600
        val mins = (data.sessionTime % 3600) / 60

        val labelSize = min(12f, h * 0.28f)
        val valueSize = min(20f, h * 0.46f)

        // Trip
        textPaint.color = TEXT_DIM
        textPaint.textSize = labelSize
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("TRIP", tripX, y + h * 0.36f, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = valueSize
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(String.format("%.1f km", data.sessionKm), tripX, y + h * 0.76f, textPaint)

        // Session
        val sessionX = tripX + tripW
        textPaint.color = TEXT_DIM
        textPaint.textSize = labelSize
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("SESSION", sessionX, y + h * 0.36f, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = valueSize
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("$hrs:${mins.toString().padStart(2, '0')}", sessionX, y + h * 0.76f, textPaint)

        // Reset button (small, right of session)
        val resetBtnSize = h * 0.30f
        val resetBtnX = sessionX + sessionW
        val resetBtnY = y + (h - resetBtnSize) / 2
        drawResetButton(canvas, resetBtnX, resetBtnY, resetBtnSize, resetBtnSize, 4f)
        resetButtonRect.set(resetBtnX, resetBtnY, resetBtnX + resetBtnSize, resetBtnY + resetBtnSize)
    }

    // ============================================================
    // VERTICAL AMPERAGE BAR
    // ============================================================
    private fun drawVerticalAmperageBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                        amps: Float, maxAmps: Float) {
        val r = w / 2f

        // Track
        paint.color = GAUGE_BG
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)

        // Fill
        val ratio = min(abs(amps) / maxAmps, 1f)
        val fillH = max(h * ratio, h * 0.02f)
        val col = ampColorInt(amps, maxAmps)
        val glow = 0.7f + 0.3f * sin(glowPhase)

        val fillTop = y + h - fillH
        val shader = LinearGradient(x, fillTop, x, y + h,
            intArrayOf(col, adjustAlpha(col, glow), adjustAlpha(col, 0.5f * glow)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
        rectF.set(x, fillTop, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)
        paint.shader = null

        // Glow
        if (ratio > 0.03f) {
            val glowR = w * 1.0f
            val radialGrad = RadialGradient(x + w / 2, fillTop, glowR,
                adjustAlpha(col, 0.5f * glow), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.shader = radialGrad
            canvas.drawCircle(x + w / 2, fillTop, glowR, paint)
            paint.shader = null
        }

        // Label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(8f, w * 0.28f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${abs(amps).toInt()}A", x + w / 2, y + 16f, textPaint)
    }

    // ============================================================
    // VERTICAL RANGE BAR
    // ============================================================
    private fun drawVerticalRangeBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                     rangeKm: Int, soc: Int) {
        val r = w / 2f

        paint.color = GAUGE_BG
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)

        val ratio = (soc / 100f).coerceIn(0f, 1f)
        val fillH = max(h * ratio, h * 0.02f)
        val col = socColorInt(soc)
        val glow = 0.7f + 0.3f * sin(glowPhase)

        val fillTop = y + h - fillH
        val shader = LinearGradient(x, fillTop, x, y + h,
            intArrayOf(col, adjustAlpha(col, glow), adjustAlpha(col, 0.5f * glow)),
            floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
        paint.shader = shader
        rectF.set(x, fillTop, x + w, y + h)
        canvas.drawRoundRect(rectF, r, r, paint)
        paint.shader = null

        if (ratio > 0.03f) {
            val glowR = w * 1.0f
            val radialGrad = RadialGradient(x + w / 2, fillTop, glowR,
                adjustAlpha(col, 0.5f * glow), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            paint.shader = radialGrad
            canvas.drawCircle(x + w / 2, fillTop, glowR, paint)
            paint.shader = null
        }

        textPaint.color = TEXT_DIM
        textPaint.textSize = max(8f, w * 0.26f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val rangeStr = if (rangeKm > 0) "~$rangeKm" else "---"
        canvas.drawText(rangeStr, x + w / 2, y + 14f, textPaint)
        textPaint.textSize = max(7f, w * 0.2f)
        canvas.drawText("km", x + w / 2, y + 26f, textPaint)
    }

    // ============================================================
    // ARC GAUGE (RPM)
    // ============================================================
    private fun drawArcGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                             value: Float, maxVal: Float, unit: String, numTicks: Int, arcWidth: Float) {
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = min(value / maxVal, 1f)

        // Arc track
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc with glow
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
        paint.strokeWidth = max(2f, radius * 0.04f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.style = Paint.Style.STROKE
        canvas.drawLine(cx, cy, nx, ny, paint)
        paint.style = Paint.Style.FILL

        // Center dot
        paint.color = NEEDLE_COLOR
        canvas.drawCircle(cx, cy, radius * 0.07f, paint)

        // Value
        textPaint.color = TEXT_PRI
        textPaint.textSize = max(14f, radius * 0.40f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value.toInt().toString(), cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Unit
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(8f, radius * 0.18f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(unit, cx, cy + radius * 0.38f + textPaint.textSize, textPaint)

        // Tick labels
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, radius * 0.12f)
        for (i in 0 until numTicks) {
            val frac = i.toFloat() / (numTicks - 1)
            val a = Math.toRadians((startAngle + sweepAngle * frac).toDouble())
            val lx = cx + (radius + arcWidth * 2.2f) * cos(a).toFloat()
            val ly = cy + (radius + arcWidth * 2.2f) * sin(a).toFloat()
            canvas.drawText((maxVal * frac).toInt().toString(), lx, ly + textPaint.textSize * 0.35f, textPaint)
        }
    }

    // ============================================================
    // TEMP GAUGE (small arc)
    // ============================================================
    private fun drawTempGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                              temp: Float, maxTemp: Float, label: String, arcWidth: Float) {
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = min(max(temp, 0f) / maxTemp, 1f)
        val col = tempColorInt(temp.toInt())

        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        if (ratio > 0) {
            paint.color = col
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        textPaint.color = col
        textPaint.textSize = max(9f, radius * 0.55f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${temp.toInt()}\u00b0", cx, cy + textPaint.textSize * 0.3f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, radius * 0.28f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(label, cx, cy + radius * 0.6f, textPaint)
    }

    // ============================================================
    // USAGE GAUGE (kWh used + remaining)
    // ============================================================
    private fun drawUsageGauge(canvas: Canvas, cx: Float, cy: Float, radius: Float,
                               kwh: Float, maxKwh: Float, soc: Int, arcWidth: Float) {
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = min(kwh / maxKwh, 1f)

        val col = when {
            ratio < 0.5f -> ACCENT_GREEN
            ratio < 0.75f -> ACCENT_YELLOW
            else -> ACCENT_ORANGE
        }

        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcWidth
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        if (ratio > 0) {
            paint.color = col
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // Remaining kWh
        val remainKwh = soc / 100f * BATTERY_TOTAL_KWH
        textPaint.color = ACCENT_BLUE
        textPaint.textSize = max(8f, radius * 0.42f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(String.format("%.1f", remainKwh), cx, cy - radius * 0.02f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = max(6f, radius * 0.22f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("kWh", cx, cy + radius * 0.22f, textPaint)

        // Used
        textPaint.color = col
        textPaint.textSize = max(6f, radius * 0.28f)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val usedStr = if (kwh < 10) String.format("-%.1f", kwh) else String.format("-%.0f", kwh)
        canvas.drawText(usedStr, cx, cy + radius * 0.52f, textPaint)
    }

    // ============================================================
    // GEAR INDICATOR
    // ============================================================
    private fun drawGearIndicator(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                                  gear: String, cr: Float) {
        val name = when (gear) { "F" -> "SPORT"; "R" -> "REVERSE"; else -> "NEUTRAL" }
        val col = when (gear) { "F" -> ACCENT_GREEN; "R" -> ACCENT_RED; else -> ACCENT_BLUE }

        // Gradient fill
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(col, adjustAlpha(col, 0.7f)), null, Shader.TileMode.CLAMP)
        paint.shader = grad
        rectF.set(x + 1, y + 1, x + w - 1, y + h - 1)
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.shader = null

        textPaint.color = Color.WHITE
        textPaint.textSize = min(22f, h * 0.45f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(name, x + w / 2, y + h / 2 + textPaint.textSize * 0.35f, textPaint)
    }

    // ============================================================
    // ODOMETER (Trip + Session)
    // ============================================================
    private fun drawOdometer(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                             sessionKm: Float, sessionSecs: Int, cr: Float) {
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        val hrs = sessionSecs / 3600
        val mins = (sessionSecs % 3600) / 60

        val colW = w / 2f

        // Trip
        textPaint.color = TEXT_DIM
        textPaint.textSize = min(10f, h * 0.22f)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("TRIP", x + 12f, y + h * 0.35f, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = min(16f, h * 0.38f)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(String.format("%.1f km", sessionKm), x + 12f, y + h * 0.78f, textPaint)

        // Session time
        textPaint.color = TEXT_DIM
        textPaint.textSize = min(10f, h * 0.22f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("SESSION", x + colW + 8f, y + h * 0.35f, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = min(16f, h * 0.38f)
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("$hrs:${mins.toString().padStart(2, '0')}", x + colW + 8f, y + h * 0.78f, textPaint)
    }

    // ============================================================
    // RESET BUTTON (small circular arrow)
    // ============================================================
    private fun drawResetButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, cr: Float) {
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x, y, x + w, y + h)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = ACCENT_ORANGE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        canvas.drawRoundRect(rectF, cr, cr, paint)

        // Circular arrow icon
        val cx = x + w / 2
        val cy = y + h / 2
        val r = min(w, h) * 0.22f
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rectF, -60f, 300f, false, paint)

        // Arrowhead
        val tipAngle = Math.toRadians(-60.0)
        val tipX = cx + r * cos(tipAngle).toFloat()
        val tipY = cy + r * sin(tipAngle).toFloat()
        val arrLen = r * 0.5f
        canvas.drawLine(tipX, tipY, tipX - arrLen, tipY, paint)
        canvas.drawLine(tipX, tipY, tipX, tipY + arrLen, paint)
        paint.style = Paint.Style.FILL
    }

    // ============================================================
    // SETTINGS BUTTON
    // ============================================================
    private fun drawSettingsButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, cr: Float) {
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x + 1, y + 1, x + w - 1, y + h - 1)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)

        val cx = x + w / 2
        val cy = y + h / 2
        val r = min(w, h) * 0.22f

        paint.color = ACCENT_BLUE
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, r, paint)
        canvas.drawCircle(cx, cy, r * 0.35f, paint)

        for (angle in intArrayOf(0, 45, 90, 135)) {
            val rad = Math.toRadians(angle.toDouble())
            val x1 = cx + (r * cos(rad)).toFloat()
            val y1 = cy + (r * sin(rad)).toFloat()
            val x2 = cx + (r * 1.5f * cos(rad)).toFloat()
            val y2 = cy + (r * 1.5f * sin(rad)).toFloat()
            canvas.drawLine(x1, y1, x2, y2, paint)
            canvas.drawLine(2 * cx - x1, 2 * cy - y1, 2 * cx - x2, 2 * cy - y2, paint)
        }
        paint.style = Paint.Style.FILL
    }

    // ============================================================
    // BMS BUTTON
    // ============================================================
    private fun drawBmsButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               connected: Boolean, cr: Float) {
        val grad = LinearGradient(x, y, x, y + h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        cardPaint.shader = grad
        rectF.set(x + 1, y + 1, x + w - 1, y + h - 1)
        canvas.drawRoundRect(rectF, cr, cr, cardPaint)
        cardPaint.shader = null

        paint.color = CARD_BORDER
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRoundRect(rectF, cr, cr, paint)
        paint.style = Paint.Style.FILL

        val cx = x + w / 2
        val cy = y + h / 2

        // Battery icon (small)
        val bw = w * 0.40f
        val bh = h * 0.35f
        val bx = cx - bw / 2
        val by = cy - bh / 2 - 3f
        val tipW = 3f
        val tipH = bh * 0.4f

        val col = if (connected) ACCENT_GREEN else TEXT_DIM
        paint.color = col
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        rectF.set(bx, by, bx + bw, by + bh)
        canvas.drawRoundRect(rectF, 2f, 2f, paint)
        paint.style = Paint.Style.FILL

        // Tip
        rectF.set(bx + bw + 1, by + (bh - tipH) / 2, bx + bw + 1 + tipW, by + (bh + tipH) / 2)
        canvas.drawRect(rectF, paint)

        // Status dot
        if (connected) {
            paint.color = ACCENT_GREEN
            canvas.drawCircle(cx, by + bh + 8f, 3f, paint)
        }

        // Label
        textPaint.color = col
        textPaint.textSize = max(7f, min(w * 0.22f, 9f))
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("BMS", cx, y + h - 4f, textPaint)
    }

    // ============================================================
    // COLOR HELPERS
    // ============================================================
    private fun formatNumber(v: Float, decimals: Int): String {
        return if (v == v.toInt().toFloat()) v.toInt().toString()
        else String.format("%.${decimals}f", v)
    }

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
