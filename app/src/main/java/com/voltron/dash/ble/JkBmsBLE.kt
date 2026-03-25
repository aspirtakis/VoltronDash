package com.voltron.dash.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

@SuppressLint("MissingPermission")
class JkBmsBLE(
    private val context: Context,
    private val macAddress: String,
    private val onData: (JkBmsData) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "JkBmsBLE"
        private const val RECONNECT_DELAY_MS = 3000L
        private const val RETRY_DELAY_MS = 10000L  // retry command if no data after 10s
    }

    private val serviceUuid = UUID.fromString(JkBmsParser.SERVICE_UUID)
    private val charUuid = UUID.fromString(JkBmsParser.CHAR_UUID)
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val handler = Handler(Looper.getMainLooper())
    private val state = JkBmsParser.MutableState()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var running = false
    private var notifCount = 0
    private var parsedFrames = 0

    fun start() {
        running = true
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            onStatus("No BLE")
            return
        }
        connectByMac()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        gatt?.close()
        gatt = null
    }

    private fun connectByMac() {
        if (!running) return
        onStatus("Connecting...")
        Log.i(TAG, "Connecting to BMS at $macAddress")

        val device = bluetoothAdapter?.getRemoteDevice(macAddress)
        if (device == null) {
            onStatus("Invalid MAC")
            return
        }
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "State: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "Connected, requesting MTU 247...")
                    handler.post { onStatus("Connected") }
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Disconnected (status=$status)")
                    handler.post { onStatus("Disconnected") }
                    handler.removeCallbacksAndMessages(null)
                    g.close()
                    gatt = null
                    if (running) {
                        handler.postDelayed({ connectByMac() }, RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status=$status)")
            // Proceed to discover services regardless of MTU result
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                handler.post { onStatus("Svc fail") }
                g.disconnect()
                return
            }

            // Log all services and characteristics
            for (svc in g.services) {
                Log.i(TAG, "Service: ${svc.uuid}")
                for (ch in svc.characteristics) {
                    val props = ch.properties
                    val p = mutableListOf<String>()
                    if (props and BluetoothGattCharacteristic.PROPERTY_READ != 0) p.add("R")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) p.add("W")
                    if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) p.add("WnR")
                    if (props and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) p.add("N")
                    if (props and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) p.add("I")
                    Log.i(TAG, "  Char: ${ch.uuid} [${p.joinToString(",")}]")
                }
            }

            val service = g.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "Service not found")
                handler.post { onStatus("No svc") }
                g.disconnect()
                return
            }

            val char = service.getCharacteristic(charUuid)
            if (char == null) {
                Log.e(TAG, "Char $charUuid not found")
                handler.post { onStatus("No char") }
                g.disconnect()
                return
            }

            notifCount = 0
            parsedFrames = 0
            val ok = g.setCharacteristicNotification(char, true)
            Log.i(TAG, "setCharacteristicNotification=$ok")

            val desc = char.getDescriptor(cccdUuid)
            if (desc != null) {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val wOk = g.writeDescriptor(desc)
                Log.i(TAG, "CCCD write initiated=$wOk")
                handler.post { onStatus("Subscribing...") }
            } else {
                Log.i(TAG, "No CCCD, sending commands directly")
                handler.postDelayed({ sendCommands(g) }, 500)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "CCCD success, sending commands after delay (matching RPi flow)")
                // Match RPi: send device info first, then cell info
                handler.postDelayed({ sendCommands(g) }, 1000)
            } else {
                Log.e(TAG, "CCCD failed: $status")
                handler.post { onStatus("CCCD fail") }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Log.i(TAG, "Write callback: status=$status (0=success)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return
            notifCount++

            // Log first 20 notifications and every 50th after that
            if (notifCount <= 20 || notifCount % 50 == 0) {
                val hex = data.joinToString(" ") { "%02X".format(it) }
                Log.i(TAG, "Notif #$notifCount: ${data.size}B [$hex]")
            }
            handler.post { onStatus("RX: $notifCount") }

            val changed = JkBmsParser.feedData(data, state)
            if (changed) {
                parsedFrames++
                val snapshot = state.toData()
                Log.i(TAG, "PARSED frame #$parsedFrames: ${snapshot.cellCount}S V=${snapshot.totalVoltage}V SOC=${snapshot.soc}%")
                handler.post {
                    onStatus("Live ${snapshot.cellCount}S ${snapshot.soc}%")
                    onData(snapshot)
                }
            }
        }
    }

    /**
     * Send commands matching the RPi script flow:
     * 1. CMD_DEVICE_INFO first
     * 2. Wait 2 seconds
     * 3. CMD_CELL_INFO
     * 4. Schedule retry if no data after 10s
     */
    private fun sendCommands(g: BluetoothGatt) {
        if (!running) return
        val currentGatt = gatt ?: return
        if (g !== currentGatt) return  // stale reference

        val service = g.getService(serviceUuid) ?: run {
            Log.e(TAG, "Service gone during sendCommands")
            return
        }
        val char = service.getCharacteristic(charUuid) ?: run {
            Log.e(TAG, "Char gone during sendCommands")
            return
        }

        val props = char.properties
        val writable = props and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

        if (!writable) {
            Log.w(TAG, "Char not writable (props=0x${props.toString(16)}), BMS may auto-stream")
            // Still schedule retry in case BMS auto-streams but we missed the first frame
            scheduleRetry(g)
            return
        }

        char.writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }

        // Step 1: Send CMD_DEVICE_INFO (like RPi does)
        Log.i(TAG, "Sending CMD_DEVICE_INFO...")
        char.value = JkBmsParser.CMD_DEVICE_INFO
        val ok1 = g.writeCharacteristic(char)
        Log.i(TAG, "CMD_DEVICE_INFO sent, success=$ok1")

        // Step 2: Send CMD_CELL_INFO after 2s delay (matching RPi)
        handler.postDelayed({
            if (!running || gatt == null) return@postDelayed
            val svc2 = g.getService(serviceUuid) ?: return@postDelayed
            val ch2 = svc2.getCharacteristic(charUuid) ?: return@postDelayed
            ch2.writeType = char.writeType
            Log.i(TAG, "Sending CMD_CELL_INFO...")
            ch2.value = JkBmsParser.CMD_CELL_INFO
            val ok2 = g.writeCharacteristic(ch2)
            Log.i(TAG, "CMD_CELL_INFO sent, success=$ok2")

            // Step 3: Schedule retry if no parsed data after 10s
            scheduleRetry(g)
        }, 2000)
    }

    private fun scheduleRetry(g: BluetoothGatt) {
        handler.postDelayed({
            if (!running || gatt == null) return@postDelayed
            if (parsedFrames == 0) {
                Log.w(TAG, "No parsed frames after ${RETRY_DELAY_MS}ms, retrying CMD_CELL_INFO...")
                handler.post { onStatus("Retrying...") }
                val svc = g.getService(serviceUuid) ?: return@postDelayed
                val ch = svc.getCharacteristic(charUuid) ?: return@postDelayed
                val props = ch.properties
                ch.writeType = if (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }
                ch.value = JkBmsParser.CMD_CELL_INFO
                g.writeCharacteristic(ch)

                // One more retry after another 10s
                handler.postDelayed({
                    if (!running || gatt == null) return@postDelayed
                    if (parsedFrames == 0) {
                        Log.e(TAG, "Still no data after retry, reconnecting...")
                        handler.post { onStatus("No data, reconnecting...") }
                        g.disconnect()
                    }
                }, RETRY_DELAY_MS)
            }
        }, RETRY_DELAY_MS)
    }
}
