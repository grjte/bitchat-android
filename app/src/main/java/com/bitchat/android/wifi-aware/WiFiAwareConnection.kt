package com.bitchat.android.`wifi-aware`

import android.net.Network
import java.net.ServerSocket
import java.net.Socket

/**
 * Represents an active Wi-Fi Aware connection to a peer
 */
data class WiFiAwareConnection(
    val peerID: String,
    val role: ConnectionRole,
    var socket: Socket? = null,
    var serverSocket: ServerSocket? = null,
    var network: Network? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var socketReader: WiFiAwareSocketReader? = null
) {
    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false
    
    fun close() {
        socketReader?.stop()
        socketReader = null
        socket?.close()
        serverSocket?.close()
    }
}

enum class ConnectionRole {
    CLIENT,
    SERVER
}