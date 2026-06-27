package com.example.galaxybridge

import android.Manifest
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class BleDiscoveryAdvertiser(
    private val context: Context,
    private val port: Int
) {
    private val appContext = context.applicationContext
    private val bluetoothManager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null

    fun start() {
        if (gattServer != null || advertiseCallback != null) return
        if (!hasBluetoothPermissions()) {
            Log.w(TAG, "BLE discovery skipped: missing Bluetooth permissions")
            return
        }

        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            Log.w(TAG, "BLE discovery skipped: adapter unavailable or disabled")
            return
        }

        val advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "BLE discovery skipped: advertiser unavailable")
            return
        }

        val service = BluetoothGattService(
            SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        ).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ENDPOINT_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
        }

        runCatching {
            gattServer = bluetoothManager.openGattServer(appContext, gattCallback).apply {
                addService(service)
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    Log.i(TAG, "BLE discovery advertising started")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.w(TAG, "BLE discovery advertising failed: $errorCode")
                    advertiseCallback = null
                }
            }
            advertiseCallback = callback
            advertiser.startAdvertising(settings, data, callback)
        }.onFailure {
            Log.w(TAG, "Failed to start BLE discovery", it)
            stop()
        }
    }

    fun stop() {
        val callback = advertiseCallback
        advertiseCallback = null
        runCatching {
            if (callback != null && hasBluetoothPermissions()) {
                bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback)
            }
        }.onFailure {
            Log.w(TAG, "Failed to stop BLE advertising", it)
        }

        runCatching {
            if (hasBluetoothPermissions()) {
                gattServer?.close()
            }
        }.onFailure {
            Log.w(TAG, "Failed to close BLE GATT server", it)
        }
        gattServer = null
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicReadRequest(
            device: android.bluetooth.BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != ENDPOINT_CHARACTERISTIC_UUID) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                return
            }

            val bytes = endpointJson().toByteArray(Charsets.UTF_8)
            val value = if (offset < bytes.size) bytes.copyOfRange(offset, bytes.size) else byteArrayOf()
            runCatching {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }.onFailure {
                Log.w(TAG, "Failed to send BLE endpoint response", it)
            }
        }
    }

    private fun endpointJson(): String =
        JSONObject()
            .put("version", 1)
            .put("name", "GalaxyBridge")
            .put("host", getLocalIPAddress())
            .put("port", port)
            .put("path", "/sync/sleep")
            .put("health", "/health")
            .put("tokenHeader", "X-Bridge-Token")
            .toString()

    private fun hasBluetoothPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getLocalIPAddress(): String {
        runCatching {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        }

        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: ""
                    }
                }
            }
        }

        return ""
    }

    companion object {
        private const val TAG = "BleDiscovery"
        val SERVICE_UUID: UUID = UUID.fromString("8e4b7f20-0f74-4db7-9c71-fb7a2d22c001")
        val ENDPOINT_CHARACTERISTIC_UUID: UUID =
            UUID.fromString("8e4b7f21-0f74-4db7-9c71-fb7a2d22c001")
    }
}
