package com.munapay.gateway.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.munapay.gateway.api.ApiClient
import com.munapay.gateway.model.PendingTask
import com.munapay.gateway.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that polls the API for pending transactions
 * and executes USSD codes to send credit/forfaits.
 */
class GatewayService : Service() {

    companion object {
        private const val TAG = "GatewayService"
        private const val CHANNEL_ID = "gateway_channel"
        private const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_SECONDS = 15L

        var isRunning = false
            private set
        var processedCount = 0
            private set
        var lastStatus = "En attente..."
            private set
    }

    private lateinit var apiClient: ApiClient
    private lateinit var ussdExecutor: UssdExecutor
    private var scheduler: ScheduledExecutorService? = null
    private var isProcessing = false
    private var simSlot = 0

    override fun onCreate() {
        super.onCreate()
        apiClient = ApiClient(this)
        ussdExecutor = UssdExecutor(this)

        val prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE)
        simSlot = prefs.getInt("sim_slot", 0)

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Gateway active — en attente de tâches...")
        startForeground(NOTIFICATION_ID, notification)

        isRunning = true
        startPolling()

        Log.d(TAG, "Gateway service started")
        return START_STICKY
    }

    private fun startPolling() {
        scheduler?.shutdownNow()
        scheduler = Executors.newSingleThreadScheduledExecutor()
        scheduler?.scheduleWithFixedDelay(
            { pollAndProcess() },
            0,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )
    }

    /**
     * Poll server for pending tasks and process them one by one
     */
    private fun pollAndProcess() {
        if (isProcessing) return

        try {
            val tasks = apiClient.getPendingTasks()

            if (tasks.isEmpty()) {
                updateStatus("En attente... (${processedCount} traités)")
                return
            }

            updateStatus("${tasks.size} tâche(s) en attente")
            Log.d(TAG, "Found ${tasks.size} pending tasks")

            for (task in tasks) {
                isProcessing = true
                processTask(task)
                // Wait between USSD executions to avoid conflicts
                Thread.sleep(5000)
            }

            isProcessing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in poll cycle", e)
            isProcessing = false
            updateStatus("Erreur: ${e.message}")
        }
    }

    /**
     * Process a single pending task
     */
    private fun processTask(task: PendingTask) {
        val ussdCode = when (task.type) {
            "credit" -> ussdExecutor.buildCreditUssd(task.montant, task.receiver)
            "forfait" -> task.ussdCode?.let { buildForfaitUssd(it, task.receiver) }
            else -> null
        }

        if (ussdCode == null) {
            Log.e(TAG, "Cannot build USSD code for task ${task.id}")
            apiClient.reportResult(task.id, false, "Code USSD introuvable")
            return
        }

        updateStatus("Exécution: $ussdCode")
        Log.d(TAG, "Processing task ${task.id}: $ussdCode")

        // Clear previous USSD response
        val latch = java.util.concurrent.CountDownLatch(1)
        var resultSuccess = false
        var resultResponse = ""

        ussdExecutor.execute(ussdCode, simSlot, object : UssdExecutor.UssdCallback {
            override fun onSuccess(response: String) {
                resultSuccess = true
                resultResponse = response
                Log.d(TAG, "Task ${task.id} USSD success: $response")
                latch.countDown()
            }

            override fun onFailure(error: String) {
                resultSuccess = false
                resultResponse = error
                Log.e(TAG, "Task ${task.id} USSD failed: $error")
                latch.countDown()
            }
        })

        // Wait for USSD response (max 30 seconds)
        val completed = latch.await(30, TimeUnit.SECONDS)

        if (!completed) {
            resultResponse = "Timeout: pas de réponse USSD"
            resultSuccess = false
        }

        // Report result to server
        apiClient.reportResult(task.id, resultSuccess, resultResponse)
        processedCount++
        updateStatus(
            if (resultSuccess) "Succès: tâche ${task.id}"
            else "Échec: tâche ${task.id} — $resultResponse"
        )
    }

    /**
     * Build forfait USSD code
     * The forfait code from DB is like *825*1*2*1*1
     * We need to append the receiver number: *825*1*2*1*1*receiver#
     */
    private fun buildForfaitUssd(code: String, receiver: String): String {
        val cleanNumber = receiver.removePrefix("237")
        val cleanCode = code.removeSuffix("#")
        return "$cleanCode*$cleanNumber#"
    }

    private fun updateStatus(status: String) {
        lastStatus = status
        val notification = buildNotification(status)
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "USSD Gateway",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Service d'envoi automatique de crédit/forfaits"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("USSD Gateway")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        scheduler?.shutdownNow()
        isRunning = false
        lastStatus = "Service arrêté"
        Log.d(TAG, "Gateway service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
