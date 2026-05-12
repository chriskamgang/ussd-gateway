package com.munapay.gateway.api

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.munapay.gateway.model.PendingTask
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class ApiClient(private val context: Context) {

    companion object {
        private const val TAG = "ApiClient"
        private const val PREF_NAME = "gateway_prefs"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_API_KEY = "api_key"
        private const val DEFAULT_BASE_URL = "https://api.munapay.cm"
    }

    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private val prefs get() = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    /**
     * Fetch pending transactions that need USSD execution
     */
    fun getPendingTasks(): List<PendingTask> {
        val request = Request.Builder()
            .url("$baseUrl/api/gateway/pending")
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "[]"
            Log.d(TAG, "Pending tasks response: $body")

            if (response.isSuccessful) {
                val type = object : TypeToken<List<PendingTask>>() {}.type
                gson.fromJson(body, type) ?: emptyList()
            } else {
                Log.e(TAG, "Failed to fetch pending: ${response.code}")
                emptyList()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error fetching pending tasks", e)
            emptyList()
        }
    }

    /**
     * Report task result back to server
     */
    fun reportResult(taskId: String, success: Boolean, ussdResponse: String): Boolean {
        val json = gson.toJson(mapOf(
            "taskId" to taskId,
            "status" to if (success) "success" else "failed",
            "response" to ussdResponse
        ))

        val request = Request.Builder()
            .url("$baseUrl/api/gateway/report")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody(jsonType))
            .build()

        return try {
            val response = client.newCall(request).execute()
            Log.d(TAG, "Report result: ${response.code} for task $taskId")
            response.isSuccessful
        } catch (e: IOException) {
            Log.e(TAG, "Failed to report result for task $taskId", e)
            false
        }
    }

    /**
     * Activate gateway with a short code
     */
    fun activate(code: String): Boolean {
        val json = gson.toJson(mapOf("code" to code))

        val request = Request.Builder()
            .url("$baseUrl/api/gateway/activate")
            .post(json.toRequestBody(jsonType))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: "{}"
            Log.d(TAG, "Activate response: $body")
            val data = gson.fromJson(body, Map::class.java)

            val token = data["token"] as? String
            if (token != null) {
                apiKey = token
                true
            } else {
                false
            }
        } catch (e: IOException) {
            Log.e(TAG, "Activation failed", e)
            false
        }
    }

    val isActivated: Boolean get() = apiKey.isNotEmpty()
}
