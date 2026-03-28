package com.voltron.dash.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class VotolBLE(
    private val context: Context,
    private val onData: (FarDriverData) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "VotolBLE"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val SCAN_TIMEOUT_MS = 15000L
    }

    private val serviceUuid = UUID.fromString(VotolParser.SERVICE_UUID)
    private val charUuid = UUID.fromString(VotolParser.CHAR_UUID)

    private val handler = Handler(Looper.getMainLooper())
    private val state = VotolParser.MutableState()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var running = false

    fun start() {
        running = true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE not available")
            return
        }
        startScan()
    }

    fun stop() {
        running = false
        stopScan()
        gatt?.close()
        gatt = null
    }

    private fun startScan() {
        if (!running) return
        onStatus("Scanning...")
        Log.i(TAG, "Scanning for ${VotolParser.DEVICE_NAME}...")

        val filters = listOf(
            ScanFilter.Builder()
                .setDeviceName(VotolParser.DEVICE_NAME)
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(filters, settings, scanCallback)

        // Timeout
        handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeoutRunnable)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private val scanTimeoutRunnable = Runnable {
        stopScan()
        if (running) {
            onStatus("Not found, retrying...")
            Log.w(TAG, "Scan timeout, retrying in ${RECONNECT_DELAY_MS}ms")
            handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: return
            if (name == VotolParser.DEVICE_NAME) {
                Log.i(TAG, "Found $name @ ${device.address}")
                stopScan()
                connectDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            onStatus("Scan failed")
            if (running) {
                handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice) {
        onStatus("Connecting...")
        Log.i(TAG, "Connecting to ${device.address}")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected, requesting MTU 48")
                    handler.post { onStatus("Connected") }
                    g.requestMtu(48) // need >27 for 24-byte Votol frames
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    handler.post { onStatus("Disconnected") }
                    g.close()
                    gatt = null
                    if (running) {
                        handler.postDelayed({ startScan() }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                g.disconnect()
                return
            }

            val service = g.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "Service $serviceUuid not found")
                g.disconnect()
                return
            }

            val char = service.getCharacteristic(charUuid)
            if (char == null) {
                Log.e(TAG, "Characteristic $charUuid not found")
                g.disconnect()
                return
            }

            g.setCharacteristicNotification(char, true)
            val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }

            Log.i(TAG, "Subscribed to notifications")
            handler.post { onStatus("Live") }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            Log.d(TAG, "Frame: ${data.size} bytes [${data.take(4).joinToString(" ") { "%02X".format(it) }}...]")

            // Check reset flags from settings
            if (com.voltron.dash.render.DashboardRenderer.resetTrip) {
                com.voltron.dash.render.DashboardRenderer.resetTrip = false
                state.sessionKm = 0f
                state.kwhUsed = 0f
                state.sessionStartTime = System.currentTimeMillis()
            }
            if (com.voltron.dash.render.DashboardRenderer.resetAll) {
                com.voltron.dash.render.DashboardRenderer.resetAll = false
                state.sessionKm = 0f
                state.kwhUsed = 0f
                state.totalKm = 0f
                state.totalHours = 0f
                state.sessionStartTime = System.currentTimeMillis()
            }

            val changed = VotolParser.parseFrame(data, state)
            if (changed) {
                val snapshot = state.toData()
                handler.post { onData(snapshot) }
            }
        }
    }
}
