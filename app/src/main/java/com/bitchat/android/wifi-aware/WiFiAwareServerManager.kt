package com.bitchat.android.`wifi-aware`

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.bitchat.android.crypto.EncryptionService
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket

/**
 * Manages Wi-Fi Aware server operations including network setup and socket handling
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareServerManager(
    private val context: Context,
    private val encryptionService: EncryptionService
) {
    companion object {
        private const val TAG = "WiFiAwareServer"
        private const val SERVER_SOCKET_TIMEOUT = 30000 // 30 seconds
    }
    
    interface ServerDelegate {
        fun onServerReady(peerID: String, port: Int)
        fun onClientConnected(peerID: String, socket: Socket)
        fun onServerError(peerID: String, error: String)
        fun onServerStopped(peerID: String)
    }
    
    var delegate: ServerDelegate? = null
    
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeServer: ServerInfo? = null
    
    private data class ServerInfo(
        val peerID: String,
        val network: Network,
        val serverSocket: ServerSocket,
        val port: Int,
        val acceptJob: Job,
        val networkCallback: ConnectivityManager.NetworkCallback
    )
    
    fun startServer(
        peerInfo: WiFiAwareDiscoveryManager.PeerInfo,
        publishSession: PublishDiscoverySession
    ): Boolean {
        // Check if we already have an active server
        if (activeServer != null) {
            Log.w(TAG, "Server already active for peer ${activeServer?.peerID}. Only one server allowed.")
            return false
        }
        
        // We need the subscriber handle to create a server for a specific client
        val subscriberHandle = peerInfo.subscriberHandle
        if (subscriberHandle == null) {
            Log.e(TAG, "Cannot start server - no subscriber handle for peer ${peerInfo.peerID}")
            delegate?.onServerError(peerInfo.peerID, "No subscriber handle available")
            return false
        }
        
        try {
            // Create server socket with system-assigned port
            val serverSocket = ServerSocket(0)
            val port = serverSocket.localPort
            serverSocket.soTimeout = SERVER_SOCKET_TIMEOUT
            
            Log.d(TAG, "Server socket created on port $port for peer ${peerInfo.peerID}")

            // TODO: use discovery messages to perform an ephemeral key exchange
            //  and derive a shared passphrase for the network connection
            val psk = "insecure-placeholder-passphrase"
            
            // Create network specifier for server with specific peer and port
            val networkSpecifier = WifiAwareNetworkSpecifier.Builder(publishSession, subscriberHandle)
                .setPskPassphrase(psk)
                .setPort(port)
                .build()
            
            // Request network
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build()
            
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Server network available for peer ${peerInfo.peerID}")
                    
                    // Notify that server is ready
                    delegate?.onServerReady(peerInfo.peerID, port)
                    
                    // Start accepting connections
                    val acceptJob = serverScope.launch {
                        acceptConnections(peerInfo.peerID, serverSocket, network)
                    }
                    
                    // Store server info
                    activeServer = ServerInfo(
                        peerID = peerInfo.peerID,
                        network = network,
                        serverSocket = serverSocket,
                        port = port,
                        acceptJob = acceptJob,
                        networkCallback = this
                    )
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Server network lost for peer ${peerInfo.peerID}")
                    stopServer()
                }
                
                override fun onUnavailable() {
                    Log.e(TAG, "Server network unavailable for peer ${peerInfo.peerID}")
                    delegate?.onServerError(peerInfo.peerID, "Network unavailable")
                    stopServer()
                }
            }
            
            connectivityManager.requestNetwork(networkRequest, networkCallback)
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting server for peer ${peerInfo.peerID}", e)
            delegate?.onServerError(peerInfo.peerID, e.message ?: "Unknown error")
            return false
        }
    }
    
    private suspend fun acceptConnections(peerID: String, serverSocket: ServerSocket, network: Network) {
        try {
            while (currentCoroutineContext().isActive && !serverSocket.isClosed) {
                try {
                    // Use the network's socket factory for accept
                    val clientSocket = withContext(Dispatchers.IO) {
                        serverSocket.accept()
                    }
                    
                    Log.d(TAG, "Client connected from ${clientSocket.remoteSocketAddress}")

                    delegate?.onClientConnected(peerID, clientSocket)
                    
                    // Only accept one connection per server
                    break
                    
                } catch (e: java.net.SocketTimeoutException) {
                    // Timeout is normal, continue waiting
                    continue
                }
            }
        } catch (e: Exception) {
            if (currentCoroutineContext().isActive) {
                Log.e(TAG, "Error accepting connections for peer $peerID", e)
                delegate?.onServerError(peerID, "Accept error: ${e.message}")
            }
        }
    }
    
    fun stopServer() {
        val server = activeServer ?: return
        
        Log.d(TAG, "Stopping server for peer ${server.peerID}")
        
        server.acceptJob.cancel()
        
        try {
            server.serverSocket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        
        // Unregister network callback
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(server.networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
        
        val peerID = server.peerID
        activeServer = null
        
        // Notify delegate that server has stopped
        delegate?.onServerStopped(peerID)
    }
    
    fun hasActiveServer(): Boolean = activeServer != null
    
    fun getActiveServerPeerID(): String? = activeServer?.peerID
    
    fun shutdown() {
        stopServer()
        serverScope.cancel()
    }
}