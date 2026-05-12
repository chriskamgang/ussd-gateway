package com.munapay.gateway.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.munapay.gateway.R
import com.munapay.gateway.api.ApiClient
import com.munapay.gateway.service.GatewayService
import com.munapay.gateway.service.UssdAccessibilityService
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var tvStatus: TextView
    private lateinit var tvProcessed: TextView
    private lateinit var tvAccessibility: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var etBaseUrl: EditText
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText

    private val handler = Handler(Looper.getMainLooper())
    private val logEntries = mutableListOf<String>()

    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_PHONE_STATE
    )
    private val PERMISSION_REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        apiClient = ApiClient(this)

        // Bind views
        tvStatus = findViewById(R.id.tvStatus)
        tvProcessed = findViewById(R.id.tvProcessed)
        tvAccessibility = findViewById(R.id.tvAccessibility)
        tvLog = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)

        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val rgSim = findViewById<RadioGroup>(R.id.rgSim)

        // Load saved config
        etBaseUrl.setText(apiClient.baseUrl)
        val prefs = getSharedPreferences("gateway_prefs", MODE_PRIVATE)
        val simSlot = prefs.getInt("sim_slot", 0)
        if (simSlot == 1) {
            findViewById<RadioButton>(R.id.rbSim2).isChecked = true
        }

        // Toggle service
        btnToggle.setOnClickListener {
            if (GatewayService.isRunning) {
                stopGateway()
            } else {
                startGateway()
            }
        }

        // Save config
        btnSave.setOnClickListener {
            saveConfig(rgSim)
        }

        // Open accessibility settings
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            addLog("Ouvrez les paramètres et activez 'USSD Gateway'")
        }

        // Request permissions
        requestPermissions()

        // Start UI update loop
        startStatusUpdater()
    }

    private fun startGateway() {
        if (apiClient.apiKey.isEmpty()) {
            addLog("Erreur: veuillez d'abord vous connecter")
            Toast.makeText(this, "Connectez-vous d'abord", Toast.LENGTH_SHORT).show()
            return
        }

        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        val intent = Intent(this, GatewayService::class.java)
        startForegroundService(intent)
        addLog("Service démarré")
    }

    private fun stopGateway() {
        val intent = Intent(this, GatewayService::class.java)
        stopService(intent)
        addLog("Service arrêté")
    }

    private fun saveConfig(rgSim: RadioGroup) {
        val baseUrl = etBaseUrl.text.toString().trim()
        val email = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        if (baseUrl.isNotEmpty()) {
            apiClient.baseUrl = baseUrl
        }

        val simSlot = if (rgSim.checkedRadioButtonId == R.id.rbSim2) 1 else 0
        getSharedPreferences("gateway_prefs", MODE_PRIVATE)
            .edit().putInt("sim_slot", simSlot).apply()

        addLog("Config: SIM ${simSlot + 1}, URL: ${apiClient.baseUrl}")

        if (email.isNotEmpty() && password.isNotEmpty()) {
            addLog("Connexion en cours...")
            Thread {
                val success = apiClient.login(email, password)
                handler.post {
                    if (success) {
                        addLog("Connexion réussie !")
                        Toast.makeText(this, "Connecté !", Toast.LENGTH_SHORT).show()
                    } else {
                        addLog("Échec de connexion")
                        Toast.makeText(this, "Échec de connexion", Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        } else {
            Toast.makeText(this, "Configuration sauvegardée", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startStatusUpdater() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                updateUI()
                handler.postDelayed(this, 2000)
            }
        }, 1000)
    }

    private fun updateUI() {
        val running = GatewayService.isRunning
        tvStatus.text = if (running) GatewayService.lastStatus else "Service arrêté"
        tvStatus.setTextColor(
            if (running) ContextCompat.getColor(this, android.R.color.white)
            else ContextCompat.getColor(this, android.R.color.holo_red_light)
        )

        tvProcessed.text = "${GatewayService.processedCount} tâche(s) traitée(s)"

        btnToggle.text = if (running) "Arrêter le service" else "Démarrer le service"
        btnToggle.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (running) android.R.color.holo_red_dark else android.R.color.holo_blue_light
        )

        val accessibilityOn = UssdAccessibilityService.isRunning
        tvAccessibility.text = if (accessibilityOn) "Accessibility: activé" else "Accessibility: désactivé (requis)"
        tvAccessibility.setTextColor(
            if (accessibilityOn) ContextCompat.getColor(this, android.R.color.holo_green_light)
            else ContextCompat.getColor(this, android.R.color.holo_red_light)
        )
    }

    private fun addLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logEntries.add(0, "[$time] $message")
        if (logEntries.size > 50) logEntries.removeAt(logEntries.lastIndex)
        tvLog.text = logEntries.joinToString("\n")
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed, PERMISSION_REQUEST_CODE)
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE + 1
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                addLog("Permissions accordées")
            } else {
                addLog("Permissions refusées — le service ne pourra pas fonctionner")
            }
        }
    }
}
