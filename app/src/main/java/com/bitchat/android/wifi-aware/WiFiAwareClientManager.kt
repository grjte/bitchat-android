package com.bitchat.android.`wifi-aware`

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.bitchat.android.crypto.EncryptionService
import kotlinx.coroutines.*
import java.net.Inet6Address
import java.net.Socket

/**
 * Manages Wi-Fi Aware client operations including network setup and socket connection
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareClientManager(
    private val context: Context,
    private val encryptionService: EncryptionService
) {
    companion object {
        private const val TAG = "WiFiAwareClient"
        private const val CONNECTION_TIMEOUT = 30000L // 30 seconds
    }
    
    interface ClientDelegate {
        fun onConnected(peerID: String, socket: Socket)
        fun onConnectionError(peerID: String, error: String)
    }
    
    var delegate: ClientDelegate? = null
    
    private val clientScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeClient: ClientInfo? = null
    
    private data class ClientInfo(
        val peerID: String,
        val network: Network?,
        val socket: Socket?,
        val networkCallback: ConnectivityManager.NetworkCallback
    )
    
    fun connectToServer(
        peerInfo: WiFiAwareDiscoveryManager.PeerInfo,
        subscribeSession: SubscribeDiscoverySession
    ): Boolean {
        // Check if we already have an active client connection
        if (activeClient != null) {
            Log.w(TAG, "Client already active for peer ${activeClient?.peerID}. Only one client allowed.")
            return false
        }
        
        // We need the publisher handle to connect to their server
        val publisherHandle = peerInfo.publisherHandle
        if (publisherHandle == null) {
            Log.e(TAG, "Cannot connect as client - no publisher handle for peer ${peerInfo.peerID}")
            delegate?.onConnectionError(peerInfo.peerID, "No publisher handle available")
            return false
        }
        
        try {
            // TODO: Derive PSK from peer's public key (must match server's PSK)
            // val psk = derivePSKFromPeer(peerInfo)
            val psk = "bitchat-passphrase"
            
            // Create network specifier for client (no port specified)
            val networkSpecifier = WifiAwareNetworkSpecifier.Builder(subscribeSession, publisherHandle)
                .setPskPassphrase(psk)
                .build()
            
            // Request network
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                .setNetworkSpecifier(networkSpecifier)
                .build()
            
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                private var connected = false
                
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Client network available for peer ${peerInfo.peerID}")
                    
                    // Update client info with network
                    activeClient = ClientInfo(
                        peerID = peerInfo.peerID,
                        network = network,
                        socket = null,
                        networkCallback = this
                    )
                }
                
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    Log.d(TAG, "Network capabilities changed for peer ${peerInfo.peerID}")
                    
                    if (connected) {
                        return
                    }
                    
                    // Extract peer info from network capabilities
                    val peerAwareInfo = networkCapabilities.transportInfo as? WifiAwareNetworkInfo
                    if (peerAwareInfo == null) {
                        Log.e(TAG, "No WifiAwareNetworkInfo available")
                        return
                    }
                    
                    val peerAddress = peerAwareInfo.peerIpv6Addr
                    val peerPort = peerAwareInfo.port
                    
                    if (peerAddress == null || peerPort == 0) {
                        Log.e(TAG, "Invalid peer address or port: $peerAddress:$peerPort")
                        return
                    }
                    
                    Log.d(TAG, "Connecting to peer at $peerAddress:$peerPort")
                    
                    connected = true
                    clientScope.launch {
                        connectToServerSocket(peerInfo.peerID, network, peerAddress, peerPort)
                    }
                }
                
                override fun onLost(network: Network) {
                    Log.d(TAG, "Client network lost for peer ${peerInfo.peerID}")
                    disconnectClient()
                }
                
                override fun onUnavailable() {
                    Log.e(TAG, "Client network unavailable for peer ${peerInfo.peerID}")
                    delegate?.onConnectionError(peerInfo.peerID, "Network unavailable")
                    disconnectClient()
                }
            }
            
            connectivityManager.requestNetwork(networkRequest, networkCallback)
            
            // Store initial client info
            activeClient = ClientInfo(
                peerID = peerInfo.peerID,
                network = null,
                socket = null,
                networkCallback = networkCallback
            )
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server for peer ${peerInfo.peerID}", e)
            delegate?.onConnectionError(peerInfo.peerID, e.message ?: "Unknown error")
            return false
        }
    }
    
    private suspend fun connectToServerSocket(peerID: String, network: Network, peerAddress: Inet6Address, port: Int) {
        try {
            withTimeout(CONNECTION_TIMEOUT) {
                val socket = network.socketFactory.createSocket(peerAddress, port)
                socket.keepAlive = true
                
                Log.d(TAG, "Connected to server for peer $peerID at $peerAddress:$port")
                
                // Update client info with connected socket
                activeClient = activeClient?.copy(socket = socket)
                
                delegate?.onConnected(peerID, socket)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error connecting to server socket for peer $peerID", e)
            delegate?.onConnectionError(peerID, "Connection failed: ${e.message}")
            disconnectClient()
        }
    }
    
    fun disconnectClient() {
        val client = activeClient ?: return
        
        Log.d(TAG, "Disconnecting client for peer ${client.peerID}")
        
        try {
            client.socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing client socket", e)
        }
        
        // Unregister network callback
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(client.networkCallback)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback", e)
        }
        
        activeClient = null
    }
    
    fun hasActiveClient(): Boolean = activeClient != null
    
    fun getActiveClientPeerID(): String? = activeClient?.peerID
    
    private fun derivePSKFromPeer(peerInfo: WiFiAwareDiscoveryManager.PeerInfo): String {
        // Must match server's PSK derivation
        val sharedSecret = peerInfo.noisePublicKey.take(16).joinToString("") { "%02x".format(it) }
        return "bitchat-$sharedSecret"
    }
    
    fun shutdown() {
        disconnectClient()
        clientScope.cancel()
    }
}