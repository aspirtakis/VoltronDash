package com.voltron.dash.render

import android.graphics.*
import com.voltron.dash.ble.JkBmsData
import kotlin.math.*

/**
 * Renders the BMS detail screen — cell chart, SOC gauge, pack info.
 */
object BmsRenderer {

    // Same dark theme as DashboardRenderer
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
    private const val CARD_BORDER = 0xFF2A4080.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectF = RectF()

    var backButtonRect = RectF()
    private var glowPhase = 0f

    fun draw(canvas: Canvas, w: Int, h: Int, data: JkBmsData) {
        canvas.drawColor(BG_DARK)
        glowPhase += 0.05f
        if (glowPhase > Math.PI.toFloat() * 2) glowPhase -= Math.PI.toFloat() * 2

        val wf = w.toFloat()
        val hf = h.toFloat()
        val pad = 8f
        val cr = 10f
        val headerH = 48f

        // Header
        drawHeader(canvas, wf, headerH)

        // Content area
        val contentY = headerH + pad
        val contentH = hf - contentY - pad

        // Three columns: SOC gauge | Cell chart | Info cards
        val socColW = wf * 0.18f
        val infoColW = wf * 0.24f
        val chartColW = wf - socColW - infoColW - pad * 4

        val socX = pad
        val chartX = socX + socColW + pad
        val infoX = chartX + chartColW + pad

        // SOC gauge
        drawSocGauge(canvas, socX, contentY, socColW, contentH, data, cr)

        // Cell voltage chart
        drawCellChart(canvas, chartX, contentY, chartColW, contentH, data, cr)

        // Info cards
        drawInfoCards(canvas, infoX, contentY, infoColW, contentH, data, cr)
    }

    private fun drawHeader(canvas: Canvas, w: Float, h: Float) {
        val grad = LinearGradient(0f, 0f, 0f, h,
            intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
        paint.shader = grad
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Back button
        textPaint.color = ACCENT_BLUE
        textPaint.textSize = 16f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("< Back", 16f, 32f, textPaint)
        backButtonRect.set(0f, 0f, 120f, h)

        // Title
        textPaint.color = TEXT_PRI
        textPaint.textSize = 18f
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("Battery Management System", w / 2, 32f, textPaint)
    }

    private fun drawSocGauge(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              data: JkBmsData, cr: Float) {
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

        // SOC arc gauge
        val cx = x + w / 2
        val cy = y + h * 0.38f
        val radius = min(w * 0.36f, h * 0.24f)
        val arcW = max(6f, radius * 0.22f)
        val startAngle = 135f
        val sweepAngle = 270f
        val ratio = data.soc / 100f

        val col = when {
            data.soc > 50 -> ACCENT_GREEN
            data.soc > 25 -> ACCENT_YELLOW
            data.soc > 10 -> ACCENT_ORANGE
            else -> ACCENT_RED
        }

        // Background arc
        paint.color = GAUGE_BG
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = arcW
        paint.strokeCap = Paint.Cap.ROUND
        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rectF, startAngle, sweepAngle, false, paint)

        // Value arc
        if (ratio > 0) {
            val glow = 0.7f + 0.3f * sin(glowPhase)
            paint.color = adjustAlpha(col, glow)
            canvas.drawArc(rectF, startAngle, sweepAngle * ratio, false, paint)
        }
        paint.style = Paint.Style.FILL

        // SOC text
        textPaint.color = col
        textPaint.textSize = max(18f, radius * 0.65f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("${data.soc}%", cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Label
        textPaint.color = TEXT_DIM
        textPaint.textSize = max(10f, radius * 0.28f)
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("SOC", cx, cy + radius + arcW + 16f, textPaint)

        // Capacity info below
        val capY = y + h * 0.68f
        textPaint.color = TEXT_DIM
        textPaint.textSize = 11f
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("CAPACITY", cx, capY, textPaint)

        textPaint.color = ACCENT_BLUE
        textPaint.textSize = 14f
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val capStr = String.format("%.0f/%.0f", data.remainingAh, data.nominalAh)
        canvas.drawText(capStr, cx, capY + 18f, textPaint)

        textPaint.color = TEXT_DIM
        textPaint.textSize = 10f
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText("Ah", cx, capY + 32f, textPaint)
    }

    private fun drawCellChart(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               data: JkBmsData, cr: Float) {
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

        // Title
        textPaint.color = TEXT_PRI
        textPaint.textSize = 13f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("CELL VOLTAGES", x + 12f, y + 20f, textPaint)

        // Delta
        textPaint.color = if (data.cellDelta < 0.010f) ACCENT_GREEN
                           else if (data.cellDelta < 0.030f) ACCENT_YELLOW
                           else ACCENT_RED
        textPaint.textSize = 12f
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(String.format("\u0394 %.3fV", data.cellDelta), x + w - 12f, y + 20f, textPaint)

        if (data.cellCount == 0) {
            textPaint.color = TEXT_DIM
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText("No cell data", x + w / 2, y + h / 2, textPaint)
            return
        }

        val chartPad = 14f
        val chartX = x + chartPad
        val chartY = y + 32f
        val chartW = w - chartPad * 2
        val chartH = h - 62f
        val labelH = 18f
        val barAreaH = chartH - labelH

        val avg = data.cellVoltages.filter { it > 0 }.average().toFloat()
        val minV = data.minCell
        val maxV = data.maxCell

        // Scale: center on average, show deviation
        val range = max(maxV - minV, 0.010f)  // at least 10mV range
        val scaleMin = avg - range * 1.2f
        val scaleMax = avg + range * 1.2f

        val n = data.cellCount
        val gap = 3f
        val barW = (chartW - gap * (n - 1)) / n

        // Average line
        val avgBarH = ((avg - scaleMin) / (scaleMax - scaleMin) * barAreaH).coerceIn(0f, barAreaH)
        val avgLineY = chartY + barAreaH - avgBarH
        paint.color = adjustAlpha(ACCENT_BLUE, 0.4f)
        paint.strokeWidth = 1f
        paint.style = Paint.Style.STROKE
        paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
        canvas.drawLine(chartX, avgLineY, chartX + chartW, avgLineY, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL

        // Average label
        textPaint.color = ACCENT_BLUE
        textPaint.textSize = 9f
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(String.format("%.3f", avg), chartX - 2f, avgLineY + 4f, textPaint)

        // Bars
        for (i in 0 until n) {
            val v = data.cellVoltages[i]
            val barH = ((v - scaleMin) / (scaleMax - scaleMin) * barAreaH).coerceIn(2f, barAreaH)
            val bx = chartX + i * (barW + gap)
            val by = chartY + barAreaH - barH

            // Color by deviation from average
            val dev = abs(v - avg)
            val barCol = when {
                dev < 0.005f -> ACCENT_GREEN
                dev < 0.015f -> ACCENT_YELLOW
                else -> ACCENT_RED
            }

            // Min/max markers
            val isMin = v == minV && v > 0
            val isMax = v == maxV

            val barGrad = LinearGradient(bx, by, bx, chartY + barAreaH,
                intArrayOf(barCol, adjustAlpha(barCol, 0.6f)), null, Shader.TileMode.CLAMP)
            paint.shader = barGrad
            rectF.set(bx, by, bx + barW, chartY + barAreaH)
            canvas.drawRoundRect(rectF, 2f, 2f, paint)
            paint.shader = null

            // Min/max indicator
            if (isMin || isMax) {
                val markerCol = if (isMin) ACCENT_RED else ACCENT_GREEN
                paint.color = markerCol
                canvas.drawCircle(bx + barW / 2, by - 5f, 3f, paint)
            }

            // Cell label
            textPaint.color = TEXT_DIM
            textPaint.textSize = max(7f, min(barW * 0.7f, 10f))
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText("${i + 1}", bx + barW / 2, chartY + barAreaH + labelH - 2f, textPaint)
        }

        // Min/Max labels at bottom
        textPaint.textSize = 10f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = ACCENT_RED
        canvas.drawText(String.format("Min: %.3fV", minV), chartX, y + h - 6f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = ACCENT_GREEN
        canvas.drawText(String.format("Max: %.3fV", maxV), chartX + chartW, y + h - 6f, textPaint)
    }

    private fun drawInfoCards(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               data: JkBmsData, cr: Float) {
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

        val innerPad = 10f
        val itemH = (h - innerPad * 2) / 9f
        var iy = y + innerPad

        // Pack info items
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "PACK", String.format("%.1fV", data.totalVoltage), TEXT_PRI)
        iy += itemH

        val curColor = if (data.current < 0) ACCENT_GREEN else if (data.current > 0) ACCENT_ORANGE else TEXT_PRI
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "CURRENT", String.format("%.1fA", data.current), curColor)
        iy += itemH

        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "POWER", String.format("%.0fW", abs(data.power)), TEXT_PRI)
        iy += itemH

        // Separator
        paint.color = CARD_BORDER
        canvas.drawRect(x + innerPad, iy, x + w - innerPad, iy + 1f, paint)
        iy += itemH * 0.3f

        // Temperatures
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "T1", String.format("%.1f\u00b0C", data.temp1), tempColor(data.temp1))
        iy += itemH

        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "T2", String.format("%.1f\u00b0C", data.temp2), tempColor(data.temp2))
        iy += itemH

        val mosStr = if (data.mosfetTemp > 0) String.format("%.1f\u00b0C", data.mosfetTemp) else "--"
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "MOS", mosStr, if (data.mosfetTemp > 0) tempColor(data.mosfetTemp) else TEXT_DIM)
        iy += itemH

        // Separator
        paint.color = CARD_BORDER
        canvas.drawRect(x + innerPad, iy, x + w - innerPad, iy + 1f, paint)
        iy += itemH * 0.3f

        // Capacity / cycles / SOH
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "CYCLES", "${data.cycles}", ACCENT_BLUE)
        iy += itemH

        val sohColor = when {
            data.soh > 80 -> ACCENT_GREEN
            data.soh > 50 -> ACCENT_YELLOW
            else -> ACCENT_RED
        }
        drawInfoItem(canvas, x + innerPad, iy, w - innerPad * 2, itemH,
            "SOH", "${data.soh}%", sohColor)
    }

    private fun drawInfoItem(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                              label: String, value: String, valueColor: Int) {
        textPaint.color = TEXT_DIM
        textPaint.textSize = 11f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Typeface.MONOSPACE
        canvas.drawText(label, x, y + h * 0.65f, textPaint)

        textPaint.color = valueColor
        textPaint.textSize = 14f
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(value, x + w, y + h * 0.65f, textPaint)
    }

    private fun tempColor(t: Float): Int = when {
        t >= 50 -> ACCENT_RED
        t >= 40 -> ACCENT_ORANGE
        else -> ACCENT_GREEN
    }

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }
}
