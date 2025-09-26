package com.bitchat.android.`wifi-aware`

import android.os.Build
import androidx.annotation.RequiresApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks active Wi-Fi Aware connections and connection state
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareConnectionTracker {
    
    companion object {
        const val DEFAULT_MAX_DATA_PATHS = 2
    }
    
    data class ConnectionState(
        var serverConnectionsCount: Int = 0,
        var hasClientConnection: Boolean = false,
        val maxDataPaths: Int
    ) {
        val usedDataPaths: Int 
            get() = serverConnectionsCount + (if (hasClientConnection) 1 else 0)
        
        fun canBeServer() = usedDataPaths < maxDataPaths
        fun canBeClient() = !hasClientConnection && usedDataPaths < maxDataPaths
    }
    
    private val activeConnections = ConcurrentHashMap<String, WiFiAwareConnection>()
    var connectionState = ConnectionState(maxDataPaths = DEFAULT_MAX_DATA_PATHS)
    
    fun updateMaxDataPaths(maxDataPaths: Int) {
        connectionState = connectionState.copy(maxDataPaths = maxDataPaths)
    }
    
    fun addConnection(connection: WiFiAwareConnection) {
        activeConnections[connection.peerID] = connection
        
        when (connection.role) {
            ConnectionRole.SERVER -> connectionState.serverConnectionsCount++
            ConnectionRole.CLIENT -> connectionState.hasClientConnection = true
        }
    }
    
    fun removeConnection(peerID: String) {
        val connection = activeConnections.remove(peerID) ?: return
        
        // Close the connection properly
        connection.close()
        
        when (connection.role) {
            ConnectionRole.SERVER -> connectionState.serverConnectionsCount--
            ConnectionRole.CLIENT -> connectionState.hasClientConnection = false
        }
    }
    
    fun getConnection(peerID: String): WiFiAwareConnection? {
        return activeConnections[peerID]
    }
    
    fun hasConnection(peerID: String): Boolean {
        return activeConnections.containsKey(peerID)
    }
    
    fun getAllConnections(): Collection<WiFiAwareConnection> {
        return activeConnections.values
    }
    
    fun getActiveConnectionCount(): Int {
        return activeConnections.size
    }
    
    fun clear() {
        // Close all connections before clearing
        activeConnections.values.forEach { it.close() }
        activeConnections.clear()
        connectionState.serverConnectionsCount = 0
        connectionState.hasClientConnection = false
    }
}