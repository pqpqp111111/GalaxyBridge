package com.example.galaxybridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class StartServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            BridgeForegroundService.ACTION_KEEP_ALIVE -> {
                if (BridgeForegroundService.isKeepAliveEnabled(context)) {
                    BridgeForegroundService.start(context)
                }
            }
            else -> BridgeForegroundService.start(context)
        }
    }

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
