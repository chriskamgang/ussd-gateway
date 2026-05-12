package com.munapay.gateway.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.ActivityCompat

/**
 * Executes USSD codes silently using TelephonyManager.sendUssdRequest()
 * Requires Android 8.0+ (API 26) and CALL_PHONE permission
 */
class UssdExecutor(private val context: Context) {

    companion object {
        private const val TAG = "UssdExecutor"
    }

    interface UssdCallback {
        fun onSuccess(response: String)
        fun onFailure(error: String)
    }

    /**
     * Execute a USSD code on the specified SIM slot
     * @param ussdCode The USSD code to execute (e.g., *825*1*500*620644564#)
     * @param simSlot 0 for SIM 1, 1 for SIM 2 (default: 0)
     * @param callback Callback for result
     */
    fun execute(ussdCode: String, simSlot: Int = 0, callback: UssdCallback) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback.onFailure("CALL_PHONE permission not granted")
            return
        }

        val telephonyManager = getTelephonyManagerForSim(simSlot)
        if (telephonyManager == null) {
            callback.onFailure("Could not get TelephonyManager for SIM $simSlot")
            return
        }

        Log.d(TAG, "Executing USSD: $ussdCode on SIM $simSlot")

        try {
            telephonyManager.sendUssdRequest(
                ussdCode,
                object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(
                        telephonyManager: TelephonyManager,
                        request: String,
                        response: CharSequence
                    ) {
                        val responseStr = response.toString()
                        Log.d(TAG, "USSD Success: $responseStr")
                        callback.onSuccess(responseStr)
                    }

                    override fun onReceiveUssdResponseFailed(
                        telephonyManager: TelephonyManager,
                        request: String,
                        failureCode: Int
                    ) {
                        val error = "USSD failed with code: $failureCode"
                        Log.e(TAG, error)
                        callback.onFailure(error)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception executing USSD", e)
            callback.onFailure("Exception: ${e.message}")
        }
    }

    /**
     * Get TelephonyManager for a specific SIM slot
     */
    private fun getTelephonyManagerForSim(simSlot: Int): TelephonyManager? {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }

        val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as? SubscriptionManager ?: return null

        val subscriptions = subscriptionManager.activeSubscriptionInfoList ?: return null

        if (simSlot >= subscriptions.size) {
            Log.w(TAG, "SIM slot $simSlot not available, using default")
            return context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        }

        val subId = subscriptions[simSlot].subscriptionId
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        return tm?.createForSubscriptionId(subId)
    }

    /**
     * Build credit USSD code for Camtel
     * Format: *825*1*montant*numero#
     */
    fun buildCreditUssd(montant: Int, receiver: String): String {
        val cleanNumber = receiver.removePrefix("237")
        return "*825*1*$montant*$cleanNumber#"
    }
}
