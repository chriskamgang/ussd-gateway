package com.munapay.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.munapay.gateway.service.GatewayService

/**
 * Auto-starts the gateway service when the phone boots up
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("BootReceiver", "Boot completed, starting gateway service")

            val prefs = context.getSharedPreferences("gateway_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start", true)
            val apiKey = prefs.getString("api_key", "")

            if (autoStart && !apiKey.isNullOrEmpty()) {
                val serviceIntent = Intent(context, GatewayService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }
}
