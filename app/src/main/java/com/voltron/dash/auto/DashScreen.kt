package com.voltron.dash.auto

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.voltron.dash.ble.FarDriverBLE
import com.voltron.dash.ble.FarDriverData
import com.voltron.dash.render.DashboardRenderer

class DashScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "DashScreen"
        private const val FRAME_INTERVAL_MS = 100L
    }

    private var surfaceContainer: SurfaceContainer? = null
    private var data: FarDriverData = FarDriverData()
    private var ble: FarDriverBLE? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rendering = false

    private val renderRunnable = object : Runnable {
        override fun run() {
            drawFrame()
            if (rendering) {
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) {
            surfaceContainer = container
            startRendering()
        }

        override fun onSurfaceDestroyed(container: SurfaceContainer) {
            stopRendering()
            surfaceContainer = null
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {}
        override fun onStableAreaChanged(stableArea: Rect) {}
    }

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                stopRendering()
                ble?.stop()
            }
        })
        startBLE()
    }

    override fun onGetTemplate(): Template {
        return NavigationTemplate.Builder()
            .setActionStrip(
                androidx.car.app.model.ActionStrip.Builder()
                    .addAction(Action.PAN)
                    .build()
            )
            .build()
    }

    private fun startBLE() {
        ble = FarDriverBLE(
            context = carContext,
            onData = { newData ->
                data = newData
            },
            onStatus = { status ->
                Log.d(TAG, "BLE: $status")
            }
        )
        ble?.start()
    }

    private fun startRendering() {
        if (!rendering) {
            rendering = true
            handler.post(renderRunnable)
        }
    }

    private fun stopRendering() {
        rendering = false
        handler.removeCallbacks(renderRunnable)
    }

    private fun drawFrame() {
        val container = surfaceContainer ?: return
        val surface = container.surface ?: return
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return

        try {
            val canvas: Canvas = surface.lockCanvas(null) ?: return
            try {
                DashboardRenderer.draw(canvas, w, h, data)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Draw error: ${e.message}")
        }
    }

}
