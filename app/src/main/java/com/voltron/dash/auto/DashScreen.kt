package com.voltron.dash.auto

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.voltron.dash.render.DashboardRenderer
import java.text.SimpleDateFormat
import java.util.*

class DashScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        private const val TAG = "DashScreen"
        private const val FRAME_INTERVAL_MS = 100L
    }

    private var surfaceContainer: SurfaceContainer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var rendering = false
    private var locationManager: LocationManager? = null
    private val utcFormat = SimpleDateFormat("HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            DashboardRenderer.gpsSpeed = location.speed * 3.6f
            DashboardRenderer.gpsBearing = if (location.hasBearing()) location.bearing else 0f
            DashboardRenderer.gpsTime = utcFormat.format(Date(location.time)) + " UTC"
            DashboardRenderer.gpsActive = true
        }
        override fun onProviderDisabled(provider: String) {
            DashboardRenderer.gpsActive = false
        }
        override fun onProviderEnabled(provider: String) {}
    }

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
                locationManager?.removeUpdates(locationListener)
            }
        })
        startGPS()
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

    private fun startGPS() {
        if (ContextCompat.checkSelfPermission(carContext,
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "GPS: no location permission")
            return
        }
        val lm = carContext.getSystemService(LocationManager::class.java) ?: return
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            Log.d(TAG, "GPS: provider not enabled")
            return
        }
        locationManager = lm
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper())
        Log.d(TAG, "GPS: location updates started")
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
                // Use shared data from MainActivity's BLE connections
                DashboardRenderer.draw(canvas, w, h,
                    DashboardRenderer.latestData, DashboardRenderer.latestBmsData)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Draw error: ${e.message}")
        }
    }

}
