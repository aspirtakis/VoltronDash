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
        private const val SHOW_INTERVAL_MS = 1000L
    }

    private val serviceUuid = UUID.fromString(VotolParser.SERVICE_UUID)
    private val charUuid = UUID.fromString(VotolParser.CHAR_UUID)

    private val handler = Handler(Looper.getMainLooper())
    private val state = VotolParser.MutableState()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var running = false
    private var isDirectBle = false  // true = AT-09, false = ESP32 bridge

    // Frame reassembly buffer for AT-09 (may split across BLE packets)
    private val frameBuf = ByteArray(256)
    private var frameBufLen = 0

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
        handler.removeCallbacks(showCmdRunnable)
        stopScan()
        gatt?.close()
        gatt = null
        writeChar = null
    }

    private fun startScan() {
        if (!running) return
        onStatus("Scanning...")
        Log.i(TAG, "Scanning for Votol devices: ${VotolParser.DEVICE_NAMES}")

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(null, settings, scanCallback)
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
            val name = device.name ?: result.scanRecord?.deviceName ?: return
            if (name in VotolParser.DEVICE_NAMES) {
                Log.i(TAG, "Found Votol device: $name @ ${device.address}")
                isDirectBle = (name != VotolParser.DEVICE_NAME_ESP32)
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
        Log.i(TAG, "Connecting to ${device.address} (direct=$isDirectBle)")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // Periodically send SHOW command when using AT-09 direct
    private val showCmdRunnable = object : Runnable {
        override fun run() {
            if (!running || gatt == null || writeChar == null) return
            sendShowCommand()
            handler.postDelayed(this, SHOW_INTERVAL_MS)
        }
    }

    private var writeQueue = mutableListOf<ByteArray>()
    private var writeInProgress = false

    private fun sendShowCommand() {
        val cmd = VotolParser.SHOW_CMD
        // AT-09/CC2541 max BLE write = 20 bytes, split 24-byte cmd into chunks
        writeQueue.clear()
        writeQueue.add(cmd.copyOfRange(0, 20))
        writeQueue.add(cmd.copyOfRange(20, 24))
        writeInProgress = false
        writeNextChunk()
    }

    private fun writeNextChunk() {
        if (writeInProgress) return
        if (writeQueue.isEmpty()) return
        val g = gatt ?: return
        val c = writeChar ?: return
        val chunk = writeQueue.removeAt(0)
        try {
            c.value = chunk
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            writeInProgress = true
            val ok = g.writeCharacteristic(c)
            Log.d(TAG, "Write chunk ${chunk.size} bytes: $ok")
            if (!ok) writeInProgress = false
        } catch (e: Exception) {
            Log.e(TAG, "Write failed: ${e.message}")
            writeInProgress = false
        }
    }

    private fun startShowPolling() {
        Log.i(TAG, "AT-09 mode: starting SHOW command polling")
        handler.removeCallbacks(showCmdRunnable)
        handler.postDelayed(showCmdRunnable, 1000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected, requesting MTU")
                    handler.post { onStatus("Connected") }
                    g.requestMtu(48)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected")
                    handler.post { onStatus("Disconnected") }
                    handler.removeCallbacks(showCmdRunnable)
                    g.close()
                    gatt = null
                    writeChar = null
                    frameBufLen = 0
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

            writeChar = char
            g.setCharacteristicNotification(char, true)

            // Write CCCD descriptor to enable notifications
            val desc = char.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
                Log.i(TAG, "Writing CCCD descriptor")
                // Wait for onDescriptorWrite before sending commands
            } else {
                // AT-09 may not have CCCD — notifications auto-enabled
                Log.i(TAG, "No CCCD descriptor, notifications auto-enabled")
                handler.post { onStatus("Live") }
                if (isDirectBle) startShowPolling()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "Write complete, status=$status, remaining=${writeQueue.size}")
            writeInProgress = false
            if (writeQueue.isNotEmpty()) {
                // Small delay between chunks so AT-09 can flush to UART
                handler.postDelayed({ writeNextChunk() }, 50)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            Log.i(TAG, "Descriptor write status=$status")
            handler.post { onStatus("Live") }
            if (isDirectBle) startShowPolling()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            Log.d(TAG, "Notify: ${data.size} bytes [${data.joinToString(" ") { "%02X".format(it) }}]")

            if (isDirectBle) {
                processDirectData(data)
            } else {
                processFrame(data)
            }
        }
    }

    /** Reassemble 24-byte Votol frames from AT-09 BLE packets */
    private fun processDirectData(data: ByteArray) {
        val space = frameBuf.size - frameBufLen
        val copyLen = minOf(data.size, space)
        System.arraycopy(data, 0, frameBuf, frameBufLen, copyLen)
        frameBufLen += copyLen

        while (frameBufLen >= 24) {
            // Look for response frame: 0xC0 0x14 ... 0x0D
            var startIdx = -1
            for (i in 0..frameBufLen - 24) {
                if ((frameBuf[i].toInt() and 0xFF) == 0xC0 &&
                    (frameBuf[i + 1].toInt() and 0xFF) == 0x14 &&
                    (frameBuf[i + 23].toInt() and 0xFF) == 0x0D) {
                    startIdx = i
                    break
                }
            }

            if (startIdx < 0) {
                // No valid frame found, keep last 23 bytes
                if (frameBufLen > 23) {
                    System.arraycopy(frameBuf, frameBufLen - 23, frameBuf, 0, 23)
                    frameBufLen = 23
                }
                break
            }

            if (startIdx > 0) {
                System.arraycopy(frameBuf, startIdx, frameBuf, 0, frameBufLen - startIdx)
                frameBufLen -= startIdx
            }

            val frame = ByteArray(24)
            System.arraycopy(frameBuf, 0, frame, 0, 24)
            System.arraycopy(frameBuf, 24, frameBuf, 0, frameBufLen - 24)
            frameBufLen -= 24

            processFrame(frame)
        }
    }

    private fun processFrame(data: ByteArray) {
        Log.d(TAG, "Frame: ${data.size} bytes [${data.take(4).joinToString(" ") { "%02X".format(it) }}...]")

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

        if (com.voltron.dash.render.DashboardRenderer.clearFaults) {
            com.voltron.dash.render.DashboardRenderer.clearFaults = false
            state.faultCleared = true
        }

        val changed = VotolParser.parseFrame(data, state)
        if (changed) {
            val snapshot = state.toData()
            handler.post { onData(snapshot) }
        }
    }
}
