package com.voltron.dash

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.voltron.dash.ble.FarDriverParser
import com.voltron.dash.render.DashboardRenderer
import android.graphics.*
import android.util.Log
import kotlin.math.*

class CalibrationActivity : AppCompatActivity() {

    companion object {
        const val PREFS_NAME = "voltron_dash"
        const val KEY_WHEEL_DIAMETER = "wheel_diameter_inch"
        const val KEY_MAX_RPM = "max_rpm"
        const val KEY_MAX_AMPS = "max_amps"
        const val KEY_TOTAL_KM = "total_km"
        const val KEY_TOTAL_HOURS = "total_hours"
        const val DIAMETER_DEFAULT = 12.0f
        const val DIAMETER_MIN = 8.0f
        const val DIAMETER_MAX = 24.0f
        const val KEY_DIFF_RATIO = "diff_ratio"
        const val DIFF_DEFAULT = 1.0f
        const val DIFF_MIN = 1.0f
        const val DIFF_MAX = 15.0f
        const val RPM_DEFAULT = 5000f
        const val RPM_MIN = 1000f
        const val RPM_MAX = 10000f
        const val AMPS_DEFAULT = 150f
        const val AMPS_MIN = 50f
        const val AMPS_MAX = 500f
        const val KEY_BMS_MAC = "bms_mac"
        const val KEY_BMS_NAME = "bms_name"
        const val DEFAULT_BMS_MAC = "C8:47:80:4B:70:72"
        const val DEFAULT_BMS_NAME = "JK-BD4A24S10P"
        const val KEY_BMS_ENABLED = "bms_enabled"
        const val KEY_CONTROLLER_TYPE = "controller_type"
        const val CTRL_FARDRIVER = "fardriver"
        const val CTRL_VOTOL = "votol"
    }

    private var wheelDiameter = DIAMETER_DEFAULT
    private var diffRatio = DIFF_DEFAULT
    private var maxRpm = RPM_DEFAULT
    private var maxAmps = AMPS_DEFAULT
    private var savedBmsMac: String? = null
    private var savedBmsName: String? = null
    private var bmsEnabled = false
    private var controllerType = CTRL_FARDRIVER
    private var bmsScanning = false
    private val bmsScanResults = mutableListOf<Pair<String, String>>() // name, mac
    private var bleScanner: BluetoothLeScanner? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wheelDiameter = prefs.getFloat(KEY_WHEEL_DIAMETER, DIAMETER_DEFAULT)
        diffRatio = prefs.getFloat(KEY_DIFF_RATIO, DIFF_DEFAULT)
        maxRpm = prefs.getFloat(KEY_MAX_RPM, RPM_DEFAULT)
        maxAmps = prefs.getFloat(KEY_MAX_AMPS, AMPS_DEFAULT)
        savedBmsMac = prefs.getString(KEY_BMS_MAC, null)
        savedBmsName = prefs.getString(KEY_BMS_NAME, null)
        bmsEnabled = prefs.getBoolean(KEY_BMS_ENABLED, false)
        controllerType = prefs.getString(KEY_CONTROLLER_TYPE, CTRL_FARDRIVER) ?: CTRL_FARDRIVER

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bleScanner = manager.adapter?.bluetoothLeScanner

        setContentView(CalibrationView(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBmsScan()
    }

    private fun saveAll() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_WHEEL_DIAMETER, wheelDiameter)
            .putFloat(KEY_DIFF_RATIO, diffRatio)
            .putFloat(KEY_MAX_RPM, maxRpm)
            .putFloat(KEY_MAX_AMPS, maxAmps)
            .apply()
        FarDriverParser.wheelDiameterInch = wheelDiameter
        FarDriverParser.diffRatio = diffRatio
        DashboardRenderer.maxRpm = maxRpm
        DashboardRenderer.maxAmps = maxAmps
    }

    private fun resetTrip() {
        DashboardRenderer.resetTrip = true
    }

    @SuppressLint("MissingPermission")
    private fun startBmsScan() {
        bmsScanResults.clear()
        bmsScanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        bleScanner?.startScan(null, settings, bmsScanCallback)
        // Stop after 10 seconds
        handler.postDelayed({ stopBmsScan() }, 10000L)
    }

    @SuppressLint("MissingPermission")
    private fun stopBmsScan() {
        bmsScanning = false
        try { bleScanner?.stopScan(bmsScanCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private val bmsScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: return
            val mac = result.device.address
            if (bmsScanResults.none { it.second == mac }) {
                // Show JK devices first, but accept all named devices
                if (name.uppercase().contains("JK")) {
                    bmsScanResults.add(0, name to mac)  // JK devices at top
                } else {
                    bmsScanResults.add(name to mac)
                }
                Log.i("CalibrationActivity", "Found BLE: $name @ $mac")
            }
        }
    }

    private fun saveBmsDevice(name: String, mac: String) {
        savedBmsMac = mac
        savedBmsName = name
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_BMS_MAC, mac)
            .putString(KEY_BMS_NAME, name)
            .apply()
        stopBmsScan()
    }

    private fun clearBmsDevice() {
        savedBmsMac = null
        savedBmsName = null
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_BMS_MAC)
            .remove(KEY_BMS_NAME)
            .apply()
    }

    private fun resetAll() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_TOTAL_KM, 0f)
            .putFloat(KEY_TOTAL_HOURS, 0f)
            .apply()
        DashboardRenderer.resetAll = true
    }

    inner class CalibrationView(context: Context) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
        }
        private val rectF = RectF()

        private val BG_DARK = 0xFF0A1628.toInt()
        private val BG_CARD = 0xFF162040.toInt()
        private val BG_CARD_LIGHT = 0xFF1C2D54.toInt()
        private val TEXT_PRI = 0xFFE8EDF7.toInt()
        private val TEXT_DIM = 0xFF7A92BE.toInt()
        private val ACCENT_BLUE = 0xFF4DB8FF.toInt()
        private val ACCENT_GREEN = 0xFF2EE866.toInt()
        private val ACCENT_RED = 0xFFFF4060.toInt()
        private val ACCENT_ORANGE = 0xFFFF8C32.toInt()
        private val GAUGE_BG = 0xFF253660.toInt()
        private val CARD_BORDER = 0xFF2A4080.toInt()

        private var draggingSlider = -1  // 0=wheel, 1=diff, 2=rpm, 3=amps

        // Layout constants
        private val headerH = 56f
        private val margin = 24f
        private val sliderH = 14f
        private val thumbR = 18f

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(BG_DARK)

            // Header bar
            val grad = LinearGradient(0f, 0f, 0f, headerH,
                intArrayOf(BG_CARD_LIGHT, BG_CARD), null, Shader.TileMode.CLAMP)
            paint.shader = grad
            canvas.drawRect(0f, 0f, w, headerH, paint)
            paint.shader = null

            textPaint.color = ACCENT_BLUE
            textPaint.textSize = 16f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("< Back", 16f, 36f, textPaint)

            textPaint.color = TEXT_PRI
            textPaint.textSize = 18f
            textPaint.textAlign = Paint.Align.CENTER
            canvas.drawText("Settings", w / 2, 36f, textPaint)

            // Two columns
            val colW = w / 2
            val leftX = margin
            val leftW = colW - margin * 1.5f
            val rightX = colW + margin * 0.5f
            val rightW = colW - margin * 1.5f

            // Separator
            paint.color = CARD_BORDER
            canvas.drawRect(colW - 0.5f, headerH + 10f, colW + 0.5f, h - 10f, paint)

            // === LEFT COLUMN: 3 sliders ===
            var sy = headerH + 16f

            // Wheel Diameter slider
            sy = drawSlider(canvas, leftX, sy, leftW,
                "Wheel Diameter", String.format("%.1f\"", wheelDiameter),
                "(total incl. tire)", wheelDiameter, DIAMETER_MIN, DIAMETER_MAX, ACCENT_BLUE)

            sy += 8f

            // Differential Ratio slider
            sy = drawSlider(canvas, leftX, sy, leftW,
                "Diff Ratio", String.format("%.1f:1", diffRatio),
                "(motor:wheel, 1=hub)", diffRatio, DIFF_MIN, DIFF_MAX, ACCENT_BLUE)

            sy += 8f

            // Max RPM slider
            sy = drawSlider(canvas, leftX, sy, leftW,
                "Max RPM", "${maxRpm.toInt()}", null,
                maxRpm, RPM_MIN, RPM_MAX, ACCENT_GREEN)

            sy += 8f

            // Max Amps slider
            sy = drawSlider(canvas, leftX, sy, leftW,
                "Max Amps", "${maxAmps.toInt()}A", null,
                maxAmps, AMPS_MIN, AMPS_MAX, ACCENT_ORANGE)

            // Save button (bottom of left column)
            val saveBtnH = 48f
            val saveBtnY = h - saveBtnH - 16f
            paint.color = ACCENT_GREEN
            rectF.set(leftX, saveBtnY, leftX + leftW, saveBtnY + saveBtnH)
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            textPaint.color = BG_DARK
            textPaint.textSize = 16f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("Save & Close", leftX + leftW / 2, saveBtnY + saveBtnH / 2 + 6f, textPaint)

            // === RIGHT COLUMN ===
            var rightY = headerH + 16f

            // Controller type toggle
            textPaint.color = ACCENT_BLUE
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("Controller", rightX + rightW / 2, rightY + 14f, textPaint)
            rightY += 22f

            val ctrlToggleH = 40f
            val isVotol = controllerType == CalibrationActivity.CTRL_VOTOL
            val ctrlColor = if (isVotol) ACCENT_ORANGE else ACCENT_BLUE
            val ctrlLabel = if (isVotol) "Votol" else "FarDriver"
            paint.color = BG_CARD
            rectF.set(rightX, rightY, rightX + rightW, rightY + ctrlToggleH)
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            paint.color = ctrlColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            paint.style = Paint.Style.FILL

            val ctrlDotX = if (isVotol) rightX + rightW - 20f else rightX + 20f
            paint.color = ctrlColor
            canvas.drawCircle(ctrlDotX, rightY + ctrlToggleH / 2, 8f, paint)

            textPaint.color = ctrlColor
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(ctrlLabel, rightX + rightW / 2, rightY + ctrlToggleH / 2 + 5f, textPaint)
            rightY += ctrlToggleH + 16f

            // Data section
            textPaint.color = ACCENT_BLUE
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("Data", rightX + rightW / 2, rightY + 14f, textPaint)
            rightY += 22f

            val btnH = 54f
            val tripBtnY = rightY
            drawButton(canvas, rightX, tripBtnY, rightW, btnH,
                "Reset Trip", "Resets session km & kWh", ACCENT_ORANGE)

            val allBtnY = tripBtnY + btnH + 28f
            drawButton(canvas, rightX, allBtnY, rightW, btnH,
                "Reset All Data", "Resets total km, hours & trip", ACCENT_RED)

            // === BMS Configuration section ===
            var bmsY = allBtnY + btnH + 36f

            textPaint.color = ACCENT_BLUE
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText("BMS Configuration", rightX + rightW / 2, bmsY, textPaint)
            bmsY += 12f

            // BMS Enable/Disable toggle
            val toggleW = rightW
            val toggleH = 40f
            val toggleColor = if (bmsEnabled) ACCENT_GREEN else ACCENT_RED
            val toggleLabel = if (bmsEnabled) "BMS: ON" else "BMS: OFF"
            paint.color = BG_CARD
            rectF.set(rightX, bmsY, rightX + toggleW, bmsY + toggleH)
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            paint.color = toggleColor
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            paint.style = Paint.Style.FILL

            // Toggle indicator
            val dotR = 8f
            val dotX = if (bmsEnabled) rightX + toggleW - 20f else rightX + 20f
            paint.color = toggleColor
            canvas.drawCircle(dotX, bmsY + toggleH / 2, dotR, paint)

            textPaint.color = toggleColor
            textPaint.textSize = 14f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(toggleLabel, rightX + toggleW / 2, bmsY + toggleH / 2 + 5f, textPaint)
            bmsY += toggleH + 10f

            if (savedBmsMac != null) {
                // Show saved device
                textPaint.color = ACCENT_GREEN
                textPaint.textSize = 12f
                textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                textPaint.textAlign = Paint.Align.LEFT
                canvas.drawText("${savedBmsName ?: "BMS"}", rightX, bmsY + 14f, textPaint)

                textPaint.color = TEXT_DIM
                textPaint.textSize = 9f
                textPaint.typeface = Typeface.MONOSPACE
                canvas.drawText(savedBmsMac!!, rightX, bmsY + 28f, textPaint)

                // Clear button
                val clearBtnY = bmsY + 36f
                drawButton(canvas, rightX, clearBtnY, rightW, 40f,
                    "Clear BMS", "", ACCENT_ORANGE)
                bmsY = clearBtnY + 40f + 12f
            } else {
                bmsY += 4f
            }

            // Scan button
            val scanLabel = if (bmsScanning) "Scanning..." else "Scan for BMS"
            val scanColor = if (bmsScanning) TEXT_DIM else ACCENT_BLUE
            drawButton(canvas, rightX, bmsY, rightW, 44f, scanLabel, "", scanColor)
            bmsY += 56f

            // Scan results
            for ((i, result) in bmsScanResults.withIndex()) {
                val (name, mac) = result
                val resultY = bmsY + i * 38f

                // Result background
                paint.color = BG_CARD
                rectF.set(rightX, resultY, rightX + rightW, resultY + 32f)
                canvas.drawRoundRect(rectF, 6f, 6f, paint)

                paint.color = ACCENT_BLUE
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                canvas.drawRoundRect(rectF, 6f, 6f, paint)
                paint.style = Paint.Style.FILL

                textPaint.color = TEXT_PRI
                textPaint.textSize = 11f
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                canvas.drawText(name, rightX + 8f, resultY + 14f, textPaint)

                textPaint.color = TEXT_DIM
                textPaint.textSize = 8f
                textPaint.typeface = Typeface.MONOSPACE
                canvas.drawText(mac, rightX + 8f, resultY + 26f, textPaint)
            }

            if (bmsScanning) {
                invalidate() // Keep refreshing during scan
            }
        }

        private fun drawSlider(canvas: Canvas, x: Float, y: Float, w: Float,
                               title: String, valueStr: String, subtitle: String?,
                               value: Float, minVal: Float, maxVal: Float, color: Int): Float {
            // Title
            textPaint.color = color
            textPaint.textSize = 13f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(title, x, y + 14f, textPaint)

            // Value
            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = 13f
            canvas.drawText(valueStr, x + w, y + 14f, textPaint)

            var curY = y + 20f

            if (subtitle != null) {
                textPaint.color = TEXT_DIM
                textPaint.textSize = 9f
                textPaint.textAlign = Paint.Align.LEFT
                textPaint.typeface = Typeface.MONOSPACE
                canvas.drawText(subtitle, x, curY + 10f, textPaint)
                curY += 14f
            }

            // Track
            val trackY = curY + 10f
            paint.color = GAUGE_BG
            rectF.set(x, trackY, x + w, trackY + sliderH)
            canvas.drawRoundRect(rectF, 7f, 7f, paint)

            // Fill
            val frac = ((value - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
            val fillW = w * frac
            paint.color = color
            rectF.set(x, trackY, x + fillW, trackY + sliderH)
            canvas.drawRoundRect(rectF, 7f, 7f, paint)

            // Thumb
            val thumbX = x + fillW
            val thumbCy = trackY + sliderH / 2
            paint.color = color
            canvas.drawCircle(thumbX, thumbCy, thumbR, paint)
            paint.color = TEXT_PRI
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawCircle(thumbX, thumbCy, thumbR, paint)
            paint.style = Paint.Style.FILL

            // Min/max labels
            textPaint.color = TEXT_DIM
            textPaint.textSize = 9f
            textPaint.typeface = Typeface.MONOSPACE
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(formatVal(minVal), x, trackY + sliderH + 16f, textPaint)
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText(formatVal(maxVal), x + w, trackY + sliderH + 16f, textPaint)

            return trackY + sliderH + 24f
        }

        private fun formatVal(v: Float): String {
            return if (v == v.toInt().toFloat()) v.toInt().toString() else String.format("%.2f", v)
        }

        private fun drawButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float,
                               label: String, desc: String, color: Int) {
            // Card background
            paint.color = BG_CARD
            rectF.set(x, y, x + w, y + h)
            canvas.drawRoundRect(rectF, 10f, 10f, paint)

            // Border
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            canvas.drawRoundRect(rectF, 10f, 10f, paint)
            paint.style = Paint.Style.FILL

            // Label
            textPaint.color = color
            textPaint.textSize = 15f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            canvas.drawText(label, x + w / 2, y + h / 2 + 5f, textPaint)

            // Description
            textPaint.color = TEXT_DIM
            textPaint.textSize = 9f
            textPaint.typeface = Typeface.MONOSPACE
            canvas.drawText(desc, x + w / 2, y + h + 14f, textPaint)
        }

        // Slider Y ranges for hit detection
        private fun getSliderYRanges(): List<Triple<Float, Float, Float>> {
            // Returns list of (trackY, trackW, sliderIndex) for each slider
            val colW = width.toFloat() / 2
            val leftW = colW - margin * 1.5f

            var sy = headerH + 16f
            val ranges = mutableListOf<Triple<Float, Float, Float>>()

            // Wheel diameter (has subtitle)
            val track1Y = sy + 20f + 14f + 10f // after title + subtitle + gap
            ranges.add(Triple(track1Y, leftW, 0f))

            // Diff ratio (has subtitle)
            val track2Start = track1Y + sliderH + 24f + 8f
            val track2Y = track2Start + 20f + 14f + 10f // after title + subtitle + gap
            ranges.add(Triple(track2Y, leftW, 1f))

            // Max RPM (no subtitle)
            val track3Start = track2Y + sliderH + 24f + 8f
            val track3Y = track3Start + 20f + 10f // after title + gap
            ranges.add(Triple(track3Y, leftW, 2f))

            // Max Amps (no subtitle)
            val track4Start = track3Y + sliderH + 24f + 8f
            val track4Y = track4Start + 20f + 10f
            ranges.add(Triple(track4Y, leftW, 3f))

            return ranges
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val w = width.toFloat()
            val h = height.toFloat()
            val x = event.x
            val y = event.y
            val colW = w / 2
            val leftW = colW - margin * 1.5f

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Back button
                    if (y < headerH && x < 120f) {
                        this@CalibrationActivity.finish()
                        return true
                    }

                    // Check sliders (left column)
                    if (x < colW) {
                        val ranges = getSliderYRanges()
                        for ((trackY, trackW, idx) in ranges) {
                            if (y in (trackY - thumbR * 2)..(trackY + sliderH + thumbR * 2)) {
                                draggingSlider = idx.toInt()
                                updateSliderValue(x, margin, leftW, draggingSlider)
                                invalidate()
                                return true
                            }
                        }

                        // Save button
                        val saveBtnH = 48f
                        val saveBtnY = h - saveBtnH - 16f
                        if (y in saveBtnY..(saveBtnY + saveBtnH) && x in margin..(margin + leftW)) {
                            saveAll()
                            this@CalibrationActivity.finish()
                            return true
                        }
                    }

                    // Right column buttons
                    if (x > colW) {
                        val rightX = colW + margin * 0.5f
                        val rightW = colW - margin * 1.5f

                        // Controller toggle (matches draw layout)
                        var rightY = headerH + 16f + 22f  // after "Controller" label
                        val ctrlToggleH = 40f
                        if (y in rightY..(rightY + ctrlToggleH) && x in rightX..(rightX + rightW)) {
                            controllerType = if (controllerType == CTRL_VOTOL) CTRL_FARDRIVER else CTRL_VOTOL
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putString(KEY_CONTROLLER_TYPE, controllerType).apply()
                            invalidate()
                            return true
                        }
                        rightY += ctrlToggleH + 16f + 22f  // after Data label

                        val btnH = 54f
                        val tripBtnY = rightY
                        val allBtnY = tripBtnY + btnH + 28f

                        if (y in tripBtnY..(tripBtnY + btnH) && x in rightX..(rightX + rightW)) {
                            resetTrip()
                            this@CalibrationActivity.finish()
                            return true
                        }
                        if (y in allBtnY..(allBtnY + btnH) && x in rightX..(rightX + rightW)) {
                            resetAll()
                            this@CalibrationActivity.finish()
                            return true
                        }

                        // BMS toggle
                        var bmsY = allBtnY + btnH + 36f + 12f
                        val toggleH = 40f
                        if (y in bmsY..(bmsY + toggleH) && x in rightX..(rightX + rightW)) {
                            bmsEnabled = !bmsEnabled
                            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                                .putBoolean(KEY_BMS_ENABLED, bmsEnabled).apply()
                            invalidate()
                            return true
                        }
                        bmsY += toggleH + 10f

                        // BMS section touch handling
                        if (savedBmsMac != null) {
                            // Clear button
                            val clearBtnY = bmsY + 36f
                            if (y in clearBtnY..(clearBtnY + 40f) && x in rightX..(rightX + rightW)) {
                                clearBmsDevice()
                                invalidate()
                                return true
                            }
                            bmsY = clearBtnY + 40f + 12f
                        } else {
                            bmsY += 4f
                        }

                        // Scan button
                        if (y in bmsY..(bmsY + 44f) && x in rightX..(rightX + rightW)) {
                            if (!bmsScanning) {
                                startBmsScan()
                                invalidate()
                            }
                            return true
                        }
                        bmsY += 56f

                        // Scan result taps
                        for ((i, result) in bmsScanResults.withIndex()) {
                            val resultY = bmsY + i * 38f
                            if (y in resultY..(resultY + 32f) && x in rightX..(rightX + rightW)) {
                                saveBmsDevice(result.first, result.second)
                                invalidate()
                                return true
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingSlider >= 0) {
                        updateSliderValue(x, margin, leftW, draggingSlider)
                        invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    draggingSlider = -1
                }
            }
            return true
        }

        private fun updateSliderValue(x: Float, marginX: Float, trackW: Float, slider: Int) {
            val frac = ((x - marginX) / trackW).coerceIn(0f, 1f)
            when (slider) {
                0 -> {
                    val raw = DIAMETER_MIN + frac * (DIAMETER_MAX - DIAMETER_MIN)
                    wheelDiameter = (raw * 2).toInt() / 2f // snap to 0.5" increments
                }
                1 -> {
                    val raw = DIFF_MIN + frac * (DIFF_MAX - DIFF_MIN)
                    diffRatio = (raw * 10).toInt() / 10f // snap to 0.1 increments
                }
                2 -> {
                    val raw = RPM_MIN + frac * (RPM_MAX - RPM_MIN)
                    maxRpm = (raw / 100).toInt() * 100f // snap to 100s
                }
                3 -> {
                    val raw = AMPS_MIN + frac * (AMPS_MAX - AMPS_MIN)
                    maxAmps = (raw / 10).toInt() * 10f // snap to 10s
                }
            }
        }
    }
}
