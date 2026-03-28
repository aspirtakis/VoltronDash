package com.voltron.dash

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.util.Log
import com.voltron.dash.ble.FarDriverBLE
import com.voltron.dash.ble.FarDriverParser
import com.voltron.dash.ble.JkBmsBLE
import com.voltron.dash.ble.VotolBLE
import com.voltron.dash.render.DashboardRenderer

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST = 1
        private const val FRAME_INTERVAL_MS = 100L // ~10 FPS
    }

    private lateinit var dashView: DashboardView
    private var farDriverBle: FarDriverBLE? = null
    private var votolBle: VotolBLE? = null
    private var bmsBle: JkBmsBLE? = null
    private var currentBmsMac: String? = null
    private var currentControllerType: String = CalibrationActivity.CTRL_FARDRIVER
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            dashView.invalidate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Load speed factor and logo
        loadSettings()
        DashboardRenderer.init(this)

        // Pre-save default BMS MAC if not configured
        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(CalibrationActivity.KEY_BMS_MAC, null) == null) {
            prefs.edit()
                .putString(CalibrationActivity.KEY_BMS_MAC, CalibrationActivity.DEFAULT_BMS_MAC)
                .putString(CalibrationActivity.KEY_BMS_NAME, CalibrationActivity.DEFAULT_BMS_NAME)
                .apply()
        }

        dashView = DashboardView(this)
        dashView.onSettingsTap = {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }
        dashView.onResetTap = {
            DashboardRenderer.resetTrip = true
        }
        dashView.onBmsTap = {
            startActivity(Intent(this, BmsActivity::class.java))
        }
        setContentView(dashView)

        if (hasPermissions()) {
            startBLE()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        handler.post(refreshRunnable)

        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val savedType = prefs.getString(CalibrationActivity.KEY_CONTROLLER_TYPE,
            CalibrationActivity.CTRL_FARDRIVER) ?: CalibrationActivity.CTRL_FARDRIVER

        // If controller type changed, restart BLE
        if (savedType != currentControllerType) {
            stopControllerBLE()
            currentControllerType = savedType
            controllerConnected = false
            startBLE()
        }

        // Reload BMS settings — start/stop based on toggle
        if (controllerConnected) {
            val bmsOn = prefs.getBoolean(CalibrationActivity.KEY_BMS_ENABLED, false)
            val savedMac = prefs.getString(CalibrationActivity.KEY_BMS_MAC, null)

            if (!bmsOn) {
                // BMS disabled — stop if running
                if (bmsBle != null) {
                    bmsBle?.stop()
                    bmsBle = null
                    currentBmsMac = null
                    DashboardRenderer.bmsStatus = "BMS: OFF"
                }
            } else if (savedMac != null && savedMac != currentBmsMac) {
                // BMS enabled and MAC changed — restart
                bmsBle?.stop()
                bmsBle = null
                currentBmsMac = savedMac
                startBMS(savedMac)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopControllerBLE()
        bmsBle?.stop()
    }

    private fun stopControllerBLE() {
        farDriverBle?.stop()
        farDriverBle = null
        votolBle?.stop()
        votolBle = null
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        FarDriverParser.wheelDiameterInch = prefs.getFloat(
            CalibrationActivity.KEY_WHEEL_DIAMETER, CalibrationActivity.DIAMETER_DEFAULT)
        FarDriverParser.diffRatio = prefs.getFloat(
            CalibrationActivity.KEY_DIFF_RATIO, CalibrationActivity.DIFF_DEFAULT)

        DashboardRenderer.maxRpm = prefs.getFloat(
            CalibrationActivity.KEY_MAX_RPM,
            CalibrationActivity.RPM_DEFAULT
        )
        DashboardRenderer.maxAmps = prefs.getFloat(
            CalibrationActivity.KEY_MAX_AMPS,
            CalibrationActivity.AMPS_DEFAULT
        )
    }

    private fun hasPermissions(): Boolean {
        val perms = getRequiredPermissions()
        return perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: only BLE permissions, no location needed
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // Android 11 and below: location required for BLE scan
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBLE()
        }
    }

    private var controllerConnected = false

    private fun startBLE() {
        val prefs = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        currentControllerType = prefs.getString(CalibrationActivity.KEY_CONTROLLER_TYPE,
            CalibrationActivity.CTRL_FARDRIVER) ?: CalibrationActivity.CTRL_FARDRIVER

        val onData: (com.voltron.dash.ble.FarDriverData) -> Unit = { data ->
            dashView.data = data
            val tag = if (currentControllerType == CalibrationActivity.CTRL_VOTOL) "VT" else "FD"
            DashboardRenderer.fdStatus = "$tag: Live"
            // Start BMS only after controller is streaming and if enabled
            if (!controllerConnected) {
                controllerConnected = true
                val p = getSharedPreferences(CalibrationActivity.PREFS_NAME, Context.MODE_PRIVATE)
                val bmsOn = p.getBoolean(CalibrationActivity.KEY_BMS_ENABLED, false)
                val bmsMac = p.getString(CalibrationActivity.KEY_BMS_MAC, null)
                if (bmsOn && bmsMac != null && bmsBle == null) {
                    currentBmsMac = bmsMac
                    Log.i("VoltronDash", "Controller connected, starting BMS in 2s...")
                    handler.postDelayed({ startBMS(bmsMac) }, 2000)
                }
            }
        }

        val onStatus: (String) -> Unit = { status ->
            val tag = if (currentControllerType == CalibrationActivity.CTRL_VOTOL) "VT" else "FD"
            Log.i("VoltronDash", "$tag: $status")
            DashboardRenderer.fdStatus = "$tag: $status"
        }

        if (currentControllerType == CalibrationActivity.CTRL_VOTOL) {
            Log.i("VoltronDash", "Starting Votol BLE...")
            votolBle = VotolBLE(context = this, onData = onData, onStatus = onStatus)
            votolBle?.start()
        } else {
            Log.i("VoltronDash", "Starting FarDriver BLE...")
            farDriverBle = FarDriverBLE(context = this, onData = onData, onStatus = onStatus)
            farDriverBle?.start()
        }
    }

    private fun startBMS(mac: String) {
        Log.i("VoltronDash", "Starting BMS BLE for $mac...")
        bmsBle = JkBmsBLE(
            context = this,
            macAddress = mac,
            onData = { data ->
                Log.i("VoltronDash", "BMS data: V=${data.totalVoltage} SOC=${data.soc}% cells=${data.cellCount}")
                dashView.bmsData = data
                BmsActivity.latestBmsData = data
            },
            onStatus = { status ->
                Log.i("VoltronDash", "BMS: $status")
                DashboardRenderer.bmsStatus = "BMS: $status"
            }
        )
        bmsBle?.start()
    }
}
