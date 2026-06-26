package com.example.galaxybridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log

class NsdServiceRegistrar(
    context: Context,
    private val port: Int
) {
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    private var listener: NsdManager.RegistrationListener? = null

    fun start() {
        if (listener != null) return

        val registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD registration failed: error=$errorCode service=$serviceInfo")
                listener = null
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered: ${serviceInfo.serviceName}.${serviceInfo.serviceType}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "NSD unregistration failed: error=$errorCode service=$serviceInfo")
            }
        }

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = SERVICE_NAME
            serviceType = SERVICE_TYPE
            port = this@NsdServiceRegistrar.port
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAttribute("path", "/sync/sleep")
                setAttribute("health", "/health")
                setAttribute("tokenHeader", "X-Bridge-Token")
                setAttribute("version", "1")
            }
        }

        listener = registrationListener
        runCatching {
            nsdManager.registerService(
                serviceInfo,
                NsdManager.PROTOCOL_DNS_SD,
                registrationListener
            )
        }.onFailure {
            listener = null
            Log.w(TAG, "Failed to start NSD registration", it)
        }
    }

    fun stop() {
        val activeListener = listener ?: return
        listener = null
        runCatching {
            nsdManager.unregisterService(activeListener)
        }.onFailure {
            Log.w(TAG, "Failed to stop NSD registration", it)
        }
    }

    companion object {
        private const val TAG = "NsdRegistrar"
        const val SERVICE_NAME = "GalaxyBridge"
        const val SERVICE_TYPE = "_http._tcp."
    }
}
