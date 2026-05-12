package com.munapay.gateway.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Accessibility service that intercepts USSD dialogs and dismisses them automatically.
 * This prevents USSD response popups from blocking the screen.
 */
class UssdAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UssdAccessibility"
        var lastUssdResponse: String? = null
            private set
        var isRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Only handle window state changes from the phone app
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val source = event.source ?: return

            // Look for USSD dialog content
            val ussdText = extractUssdText(source)
            if (ussdText != null) {
                Log.d(TAG, "USSD Response captured: $ussdText")
                lastUssdResponse = ussdText

                // Try to dismiss the dialog by clicking OK/Cancel button
                dismissDialog(source)
            }
        }
    }

    /**
     * Extract text from USSD dialog
     */
    private fun extractUssdText(node: AccessibilityNodeInfo): String? {
        val texts = mutableListOf<String>()
        collectTexts(node, texts)

        // USSD dialogs typically contain response text
        val combined = texts.joinToString(" ")
        if (combined.isNotBlank() && (
                    combined.contains("USSD", ignoreCase = true) ||
                    combined.contains("transfert", ignoreCase = true) ||
                    combined.contains("reussi", ignoreCase = true) ||
                    combined.contains("solde", ignoreCase = true) ||
                    combined.contains("forfait", ignoreCase = true) ||
                    combined.contains("credit", ignoreCase = true) ||
                    combined.contains("echec", ignoreCase = true) ||
                    combined.contains("erreur", ignoreCase = true) ||
                    combined.contains("envoyer", ignoreCase = true) ||
                    combined.contains("*825", ignoreCase = true) ||
                    combined.contains("recharge", ignoreCase = true))
        ) {
            return combined
        }
        return null
    }

    private fun collectTexts(node: AccessibilityNodeInfo, texts: MutableList<String>) {
        node.text?.let { texts.add(it.toString()) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, texts)
        }
    }

    /**
     * Dismiss USSD dialog by clicking OK or Cancel
     */
    private fun dismissDialog(node: AccessibilityNodeInfo) {
        // Look for buttons to click
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        findButtons(node, buttons)

        for (button in buttons) {
            val btnText = button.text?.toString()?.lowercase() ?: ""
            if (btnText in listOf("ok", "cancel", "annuler", "fermer", "dismiss")) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "Dismissed USSD dialog by clicking: $btnText")
                return
            }
        }

        // If no labeled button found, try clicking any button
        if (buttons.isNotEmpty()) {
            buttons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Dismissed USSD dialog by clicking first button")
        }
    }

    private fun findButtons(node: AccessibilityNodeInfo, buttons: MutableList<AccessibilityNodeInfo>) {
        if (node.className?.toString() == "android.widget.Button") {
            buttons.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findButtons(child, buttons)
        }
    }

    override fun onInterrupt() {
        isRunning = false
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        isRunning = false
        super.onDestroy()
    }
}
