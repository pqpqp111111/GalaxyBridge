package com.example.galaxybridge

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var reader: HealthConnectReader
    private lateinit var statusText: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var ipText: TextView
    private var healthConnectClient: HealthConnectClient? = null

    private val requestPerms = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        appendStatus("权限回调: 本次返回授权 ${granted.size} 个")
        lifecycleScope.launch {
            val missing = reader.missingPermissions()
            if (missing.isEmpty()) {
                appendStatus("✓ 所有权限已授权")
                runDiagnostics()
            } else {
                appendStatus("✗ 仍缺少 ${missing.size} 个权限")
                appendStatus("请在 Health Connect 设置中手动授权，或减少请求的数据类型")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        scrollView = findViewById(R.id.scrollView)
        ipText = findViewById(R.id.ipText)
        reader = HealthConnectReader(this)

        updateIPAddress()
        requestBluetoothPermissionsIfNeeded()

        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            appendStatus("--- 诊断 ---")
            checkHealthConnect()
        }

        findViewById<Button>(R.id.btnUpdateHC).setOnClickListener {
            appendStatus("--- 打开 Health Connect ---")
            openHealthConnect()
        }

        findViewById<Button>(R.id.btnStartServer).setOnClickListener {
            appendStatus("--- 启动 Server ---")
            ensureKeepAlivePermissions()
            BridgeForegroundService.start(this)
            appendStatus("Server 启动中... 端口 8787，保活已开启")
        }

        findViewById<Button>(R.id.btnStopServer).setOnClickListener {
            appendStatus("--- 停止 Server ---")
            BridgeForegroundService.stop(this)
            appendStatus("Server 已停止，保活已关闭")
        }

        appendStatus("App 启动")
        checkHealthConnect()
    }

    override fun onResume() {
        super.onResume()
        updateIPAddress()
    }

    private fun checkHealthConnect() {
        val status = HealthConnectReader.availability(this)
        appendStatus("Health Connect 状态: ${healthConnectStatusName(status)}")

        when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Unit
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                appendStatus("Health Connect 需要安装或更新，请点“更新HC”")
                return
            }
            else -> {
                appendStatus("Health Connect 不支持当前设备或当前用户资料")
                return
            }
        }

        if (!isPackageManagerReadyForHealthConnect()) {
            appendStatus("Health Connect 权限入口未就绪，请点“更新HC”确认安装状态")
            return
        }

        healthConnectClient = HealthConnectClient.getOrCreate(this)
        appendStatus("HealthConnectClient 已创建")

        lifecycleScope.launch {
            val missing = reader.missingPermissions()
            appendStatus("缺少权限: ${missing.size} / ${reader.readPermissions.size}")

            if (missing.isEmpty()) {
                appendStatus("✓ 权限已授权")
                runDiagnostics()
            } else {
                appendStatus("请求权限...")
                try {
                    requestPerms.launch(missing)
                } catch (e: Exception) {
                    appendStatus("请求失败: ${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
                    appendStatus("可点“更新HC”进入 Health Connect/安装页手动处理")
                }
            }
        }
    }

    private fun openHealthConnect() {
        val status = HealthConnectReader.availability(this)
        val intent = if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
                putExtra("overlay", true)
                putExtra("callerId", packageName)
            }
        } else {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
        }

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
            }
            try {
                startActivity(fallback)
            } catch (fallbackError: ActivityNotFoundException) {
                appendStatus("无法打开 Health Connect: ${fallbackError.message ?: "没有可用应用"}")
            }
        }
    }

    private fun ensureKeepAlivePermissions() {
        requestBluetoothPermissionsIfNeeded()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_POST_NOTIFICATIONS
            )
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !powerManager.isIgnoringBatteryOptimizations(packageName)
        ) {
            appendStatus("请允许忽略电池优化，否则三星系统可能会杀掉后台服务")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        } else {
            appendStatus("电池优化白名单: 已允许")
        }
    }

    private fun requestBluetoothPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return

        val missing = arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            appendStatus("请求蓝牙权限，用于让 iPhone 自动发现本机 HTTP server")
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                REQUEST_BLUETOOTH_DISCOVERY
            )
        } else {
            appendStatus("蓝牙发现权限: 已允许")
        }
    }

    private fun healthConnectStatusName(status: Int): String = when (status) {
        HealthConnectClient.SDK_AVAILABLE -> "可用"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "需安装/更新"
        HealthConnectClient.SDK_UNAVAILABLE -> "不可用"
        else -> "未知($status)"
    }

    private fun isPackageManagerReadyForHealthConnect(): Boolean =
        packageManager.queryIntentActivities(
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS),
            0
        ).isNotEmpty() || HealthConnectReader.availability(this) == HealthConnectClient.SDK_AVAILABLE

    private fun runDiagnostics() {
        lifecycleScope.launch {
            try {
                val result = reader.dumpInventory()
                appendStatus(result)
            } catch (e: Exception) {
                appendStatus("诊断失败: ${e.message}")
            }
        }
    }

    private fun updateIPAddress() {
        val ip = getLocalIPAddress()
        ipText.text = "本机 IP: $ip:8787"
    }

    private fun getLocalIPAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            // fallback
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress ?: "未获取到"
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "未连接WiFi"
    }

    private fun appendStatus(text: String) {
        runOnUiThread {
            statusText.append("\n$text")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    companion object {
        private const val REQUEST_POST_NOTIFICATIONS = 1001
        private const val REQUEST_BLUETOOTH_DISCOVERY = 1002
    }
}
