package com.voltron.dash

import android.content.Context
import android.graphics.Canvas
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
import com.voltron.dash.ble.JkBmsData
import com.voltron.dash.render.BmsRenderer
import com.voltron.dash.render.DashboardRenderer

class BmsActivity : AppCompatActivity() {

    companion object {
        private const val FRAME_INTERVAL_MS = 100L // ~10 FPS
        @Volatile var latestBmsData: JkBmsData = JkBmsData()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            bmsView?.invalidate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private var bmsView: BmsView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        bmsView = BmsView(this)
        setContentView(bmsView)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private val demoBmsData = JkBmsData(
        cellVoltages = listOf(3.921f, 3.918f, 3.925f, 3.920f, 3.917f, 3.923f, 3.919f, 3.922f,
            3.916f, 3.924f, 3.921f, 3.918f, 3.925f, 3.920f, 3.917f, 3.923f, 3.919f),
        cellCount = 17, totalVoltage = 66.7f, current = -2.5f, power = -166.75f,
        soc = 85, temp1 = 22.4f, temp2 = 21.8f, mosfetTemp = 25.1f,
        remainingAh = 102f, nominalAh = 120f, cycles = 12, soh = 98,
        cellDelta = 0.009f, minCell = 3.916f, maxCell = 3.925f, connected = true
    )

    inner class BmsView(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val data = if (DashboardRenderer.demoMode && !latestBmsData.connected) demoBmsData else latestBmsData
            BmsRenderer.draw(canvas, width, height, data)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_DOWN) {
                if (BmsRenderer.backButtonRect.contains(event.x, event.y)) {
                    this@BmsActivity.finish()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
