package com.goenc.androiddailymotiontimer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import java.util.UUID

data class HeartRateDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val supportsHeartRate: Boolean,
)

data class SavedHeartRateDevice(
    val name: String,
    val address: String,
)

enum class HeartRateConnectionState(val label: String) {
    Disconnected("未接続"),
    Scanning("スキャン中"),
    Connecting("接続中"),
    Connected("接続済み"),
    Error("エラー"),
}

class HeartRateMonitor(
    context: Context,
    private val bluetoothAdapter: BluetoothAdapter?,
    private val onDevicesChanged: (List<HeartRateDevice>) -> Unit,
    private val onConnectionChanged: (HeartRateConnectionState, String?) -> Unit,
    private val onHeartRateChanged: (Int) -> Unit,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val scannedDevices = linkedMapOf<String, BluetoothDevice>()
    private val deviceInfo = linkedMapOf<String, HeartRateDevice>()
    private var bluetoothGatt: BluetoothGatt? = null

    val savedDevice: SavedHeartRateDevice?
        get() {
            val address = preferences.getString(KEY_ADDRESS, null) ?: return null
            return SavedHeartRateDevice(
                name = preferences.getString(KEY_NAME, null) ?: "名前なし",
                address = address,
            )
        }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = updateDevice(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::updateDevice)
        }

        override fun onScanFailed(errorCode: Int) {
            onConnectionChanged(HeartRateConnectionState.Error, "スキャンエラー: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt(gatt)
                onConnectionChanged(HeartRateConnectionState.Error, "接続エラー: $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    onConnectionChanged(HeartRateConnectionState.Connected, null)
                    gatt.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    closeGatt(gatt)
                    onHeartRateChanged(0)
                    onConnectionChanged(HeartRateConnectionState.Disconnected, null)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "サービス検出エラー: $status")
                return
            }
            val characteristic = gatt.getService(HEART_RATE_SERVICE_UUID)
                ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)
            if (characteristic == null) {
                fail(gatt, "心拍計測サービスが見つかりません")
                return
            }
            enableNotifications(gatt, characteristic)
        }

        @Deprecated("Android 13未満で使用")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            parseHeartRate(characteristic.value)?.let(onHeartRateChanged)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            parseHeartRate(value)?.let(onHeartRateChanged)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail(gatt, "心拍通知の設定に失敗しました: $status")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            onConnectionChanged(HeartRateConnectionState.Error, "Bluetoothを有効にしてください")
            return
        }
        stopScan()
        scannedDevices.clear()
        deviceInfo.clear()
        onDevicesChanged(emptyList())
        onConnectionChanged(HeartRateConnectionState.Scanning, null)
        adapter.bluetoothLeScanner?.startScan(scanCallback)
            ?: onConnectionChanged(HeartRateConnectionState.Error, "BLEスキャンを開始できません")
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val adapter = bluetoothAdapter ?: return
        stopScan()
        disconnect()
        val device = scannedDevices[address] ?: runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            onConnectionChanged(HeartRateConnectionState.Error, "接続機器が見つかりません")
            return
        }
        val info = deviceInfo[address]
        if (info != null) saveDevice(info.name, info.address)
        onDevicesChanged(emptyList())
        onConnectionChanged(HeartRateConnectionState.Connecting, null)
        bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connectSavedDevice() {
        savedDevice?.let { connect(it.address) }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        onHeartRateChanged(0)
        onConnectionChanged(HeartRateConnectionState.Disconnected, null)
    }

    fun forgetDevice() {
        disconnect()
        preferences.edit().remove(KEY_NAME).remove(KEY_ADDRESS).apply()
    }

    @SuppressLint("MissingPermission")
    private fun updateDevice(result: ScanResult) {
        val supportsHeartRate = result.scanRecord?.serviceUuids
            ?.any { it.uuid == HEART_RATE_SERVICE_UUID } == true
        val info = HeartRateDevice(
            name = result.device.name ?: result.scanRecord?.deviceName ?: "名前なし",
            address = result.device.address,
            rssi = result.rssi,
            supportsHeartRate = supportsHeartRate,
        )
        scannedDevices[info.address] = result.device
        deviceInfo[info.address] = info
        onDevicesChanged(
            deviceInfo.values.sortedWith(
                compareByDescending<HeartRateDevice> { it.supportsHeartRate }
                    .thenByDescending { it.rssi },
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            fail(gatt, "心拍通知を有効にできません")
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            fail(gatt, "心拍通知の設定情報が見つかりません")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun saveDevice(name: String, address: String) {
        preferences.edit().putString(KEY_NAME, name).putString(KEY_ADDRESS, address).apply()
    }

    @SuppressLint("MissingPermission")
    private fun fail(gatt: BluetoothGatt, message: String) {
        closeGatt(gatt)
        onHeartRateChanged(0)
        onConnectionChanged(HeartRateConnectionState.Error, message)
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt(gatt: BluetoothGatt) {
        gatt.close()
        if (bluetoothGatt === gatt) bluetoothGatt = null
    }

    companion object {
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val PREFERENCES_NAME = "heart_rate_device"
        private const val KEY_NAME = "device_name"
        private const val KEY_ADDRESS = "device_address"

        internal fun parseHeartRate(value: ByteArray): Int? {
            if (value.size < 2) return null
            val isUInt16 = value[0].toInt() and 0x01 != 0
            return if (isUInt16) {
                if (value.size < 3) null
                else (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            } else {
                value[1].toInt() and 0xFF
            }
        }
    }
}
