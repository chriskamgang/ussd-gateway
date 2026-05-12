package com.munapay.gateway.model

data class PendingTask(
    val id: String,
    val type: String,        // "credit" or "forfait"
    val sender: String,
    val receiver: String,
    val montant: Int,
    val ussdCode: String?,   // For forfaits: the USSD code to execute
    val status: String
)
