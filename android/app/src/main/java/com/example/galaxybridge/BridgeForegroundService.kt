package com.example.galaxybridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class BridgeForegroundService : Service() {
    private var server: BridgeServer? = null
    private var nsdRegistrar: NsdServiceRegistrar? = null
    private var bleDiscoveryAdvertiser: BleDiscoveryAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val token = intent?.getStringExtra(EXTRA_TOKEN) ?: DEFAULT_TOKEN
        setKeepAliveEnabled(this, true)
        acquireLocks()
        startServer(token)
        scheduleKeepAlive(this)
        return START_STICKY
    }

    override fun onDestroy() {
        bleDiscoveryAdvertiser?.stop()
        bleDiscoveryAdvertiser = null
        nsdRegistrar?.stop()
        nsdRegistrar = null
        server?.stop()
        server = null
        releaseLocks()
        if (isKeepAliveEnabled(this)) {
            scheduleKeepAlive(this, RESTART_DELAY_MS)
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (isKeepAliveEnabled(this)) {
            scheduleKeepAlive(this, RESTART_DELAY_MS)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer(token: String) {
        if (server != null) return
        val reader = HealthConnectReader(this)
        val syncState = SyncState(this)
        server = BridgeServer(reader, syncState, token).also { it.start(PORT) }
        nsdRegistrar = NsdServiceRegistrar(this, PORT).also { it.start() }
        bleDiscoveryAdvertiser = BleDiscoveryAdvertiser(this, PORT).also { it.start() }
        Log.i(TAG, "Bridge server started")
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            runCatching {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "$packageName:BridgeWakeLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                Log.w(TAG, "Failed to acquire wake lock", it)
            }
        }

        if (wifiLock?.isHeld != true) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wifiManager.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "$packageName:BridgeWifiLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                Log.w(TAG, "Failed to acquire Wi-Fi lock", it)
            }
        }

        if (multicastLock?.isHeld != true) {
            runCatching {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                multicastLock = wifiManager.createMulticastLock(
                    "$packageName:BridgeMulticastLock"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }.onFailure {
                Log.w(TAG, "Failed to acquire multicast lock", it)
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null

        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        multicastLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GalaxyBridge Sync",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the sync server running"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GalaxyBridge")
            .setContentText("Sync server running on port $PORT with Bonjour and BLE discovery")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BridgeService"
        private const val CHANNEL_ID = "galaxy_bridge_channel"
        private const val NOTIFICATION_ID = 1
        private const val PORT = 8787
        private const val DEFAULT_TOKEN = "change-me"

        const val EXTRA_TOKEN = "extra_token"
        const val ACTION_KEEP_ALIVE = "com.example.galaxybridge.KEEP_ALIVE"

        private const val PREFS = "bridge_service"
        private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
        private const val KEEP_ALIVE_REQUEST_CODE = 8701
        private const val KEEP_ALIVE_INTERVAL_MS = 15 * 60 * 1000L
        private const val RESTART_DELAY_MS = 5 * 1000L

        fun start(context: Context, token: String = DEFAULT_TOKEN) {
            setKeepAliveEnabled(context, true)
            val intent = Intent(context, BridgeForegroundService::class.java).apply {
                putExtra(EXTRA_TOKEN, token)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            setKeepAliveEnabled(context, false)
            cancelKeepAlive(context)
            context.stopService(Intent(context, BridgeForegroundService::class.java))
        }

        fun isKeepAliveEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_KEEP_ALIVE_ENABLED, false)

        private fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled)
                .apply()
        }

        private fun scheduleKeepAlive(context: Context, delayMs: Long = KEEP_ALIVE_INTERVAL_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + delayMs
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                KEEP_ALIVE_REQUEST_CODE,
                Intent(context, StartServiceReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }

        private fun cancelKeepAlive(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                KEEP_ALIVE_REQUEST_CODE,
                Intent(context, StartServiceReceiver::class.java).apply {
                    action = ACTION_KEEP_ALIVE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
        }
    }
}
