package com.bitchat.android.`wifi-aware`

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.*
import java.net.Socket

interface WiFiAwareDelegate {
    fun didUpdatePeerList(peers: List<String>)
    fun didConnect(peerID: String)
    fun didDisconnect(peerID: String)
    fun didReceiveMessage(message: BitchatPacket, from: String)
}

/**
 * Main coordinator for Wi-Fi Aware transport
 * Manages all sub-components and provides public API
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareTransport(
    private val context: Context,
    var delegate: WiFiAwareDelegate? = null,
    private val getNickname: () -> String? = { null }
) {
    companion object {
        private const val TAG = "WiFiAwareTransport"
    }
    
    // Core components
    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private val encryptionService = EncryptionService(context)
    
    // Manager components
    private lateinit var discoveryManager: WiFiAwareDiscoveryManager
    private lateinit var connectionTracker: WiFiAwareConnectionTracker
    private lateinit var connectionManager: WiFiAwareConnectionManager
    private lateinit var serverManager: WiFiAwareServerManager
    private lateinit var clientManager: WiFiAwareClientManager
    
    // Transport state
    var isActive = false
        private set
    
    // Coroutine scope for transport operations
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val myPeerID: String
        get() = encryptionService.getIdentityFingerprint().take(16)
    
    init {
        initializeManagers()
    }
    
    private fun initializeManagers() {
        // Initialize discovery manager
        discoveryManager = WiFiAwareDiscoveryManager(encryptionService, getNickname)
        discoveryManager.delegate = object : WiFiAwareDiscoveryManager.DiscoveryDelegate {
            override fun onPeerDiscovered(peerInfo: WiFiAwareDiscoveryManager.PeerInfo) {
                Log.d(TAG, "Peer discovered: ${peerInfo.peerID}")
                delegate?.didUpdatePeerList(discoveryManager.getPeerList())
            }
            
            override fun onPeerUpdated(peerInfo: WiFiAwareDiscoveryManager.PeerInfo) {
                Log.d(TAG, "Peer updated: ${peerInfo.peerID}")
                delegate?.didUpdatePeerList(discoveryManager.getPeerList())
            }
            
            override fun onPeerListChanged(peers: List<String>) {
                delegate?.didUpdatePeerList(peers)
            }
            
            override fun onDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray, fromPublisher: Boolean) {
                connectionManager.handleDiscoveryMessage(peerHandle, message, fromPublisher)
            }
        }
        
        // Initialize connection tracker
        connectionTracker = WiFiAwareConnectionTracker()
        
        // Initialize server manager
        serverManager = WiFiAwareServerManager(context, encryptionService)
        serverManager.delegate = object : WiFiAwareServerManager.ServerDelegate {
            override fun onServerReady(peerID: String, port: Int) {
                Log.d(TAG, "Server ready for peer $peerID on port $port")
            }
            
            override fun onClientConnected(peerID: String, socket: Socket) {
                Log.d(TAG, "Client connected: $peerID")
                handleClientConnected(peerID, socket, ConnectionRole.SERVER)
            }
            
            override fun onServerError(peerID: String, error: String) {
                Log.e(TAG, "Server error for peer $peerID: $error")
                connectionManager.delegate?.onConnectionFailed(peerID, error)
            }
        }
        
        // Initialize client manager
        clientManager = WiFiAwareClientManager(context, encryptionService)
        clientManager.delegate = object : WiFiAwareClientManager.ClientDelegate {
            override fun onConnected(peerID: String, socket: Socket) {
                Log.d(TAG, "Connected to server: $peerID")
                handleClientConnected(peerID, socket, ConnectionRole.CLIENT)
            }
            
            override fun onConnectionError(peerID: String, error: String) {
                Log.e(TAG, "Client error for peer $peerID: $error")
                connectionManager.delegate?.onConnectionFailed(peerID, error)
            }
        }
        
        // Initialize connection manager
        connectionManager = WiFiAwareConnectionManager(discoveryManager, connectionTracker)
        connectionManager.delegate = object : WiFiAwareConnectionManager.ConnectionDelegate {
            override fun onServerNeeded(peerInfo: WiFiAwareDiscoveryManager.PeerInfo) {
                // Get publish session for server
                val publishSession = wifiAwareSession?.let { session ->
                    discoveryManager.publishDiscoverySession
                }
                
                if (publishSession != null) {
                    serverManager.startServer(peerInfo, publishSession)
                } else {
                    Log.e(TAG, "No publish session available for server")
                }
            }
            
            override fun onClientConnectionNeeded(peerInfo: WiFiAwareDiscoveryManager.PeerInfo) {
                // Get subscribe session for client
                val subscribeSession = wifiAwareSession?.let { session ->
                    discoveryManager.subscribeDiscoverySession
                }
                
                if (subscribeSession != null) {
                    clientManager.connectToServer(peerInfo, subscribeSession)
                } else {
                    Log.e(TAG, "No subscribe session available for client")
                }
            }
            
            override fun onConnectionFailed(peerID: String, reason: String) {
                Log.w(TAG, "Connection failed with $peerID: $reason - falling back to BLE")
                // TODO: Notify message router to use BLE
            }
            
            override fun onConnectionRedirect(peerID: String) {
                // Shut down server if we have one for this peer
                if (serverManager.getActiveServerPeerID() == peerID) {
                    serverManager.stopServer()
                }
            }
        }
    }
    
    fun startServices(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "WiFi Aware requires API 31+")
            return false
        }

        // Check for NEARBY_WIFI_DEVICES permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.NEARBY_WIFI_DEVICES) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "NEARBY_WIFI_DEVICES permission not granted")
                return false
            }
        }

        try {
            wifiAwareManager = context.getSystemService(Context.WIFI_AWARE_SERVICE) as? WifiAwareManager
            if (wifiAwareManager == null) {
                Log.e(TAG, "WiFi Aware not supported on this device")
                return false
            }

            if (!wifiAwareManager!!.isAvailable) {
                Log.e(TAG, "WiFi Aware is not available")
                return false
            }

            // Update connection tracker with actual capabilities
            val maxDataPaths = wifiAwareManager!!.characteristics?.numberOfSupportedDataPaths ?: 2
            connectionTracker.updateMaxDataPaths(maxDataPaths)
            
            wifiAwareManager!!.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "WiFi Aware attached successfully")
                    Log.d(TAG, "Supported data paths: $maxDataPaths")
                    wifiAwareSession = session
                    
                    // Start discovery
                    discoveryManager.start(session)
                    
                    isActive = true
                }

                override fun onAttachFailed() {
                    Log.e(TAG, "Failed to attach to WiFi Aware")
                    isActive = false
                }
            }, null)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting WiFi Aware services", e)
            return false
        }
    }
    
    fun stopServices() {
        isActive = false
        
        // Stop all managers
        discoveryManager.stop()
        serverManager.shutdown()
        clientManager.shutdown()
        connectionTracker.clear()
        
        // Close WiFi Aware session
        wifiAwareSession?.close()
        wifiAwareSession = null
        
        // Cancel transport scope
        transportScope.cancel()
    }
    
    // Public API methods
    
    fun sendMessage(peerID: String, packet: BitchatPacket): Boolean {
        // Check if connection exists
        val connection = connectionTracker.getConnection(peerID)
        if (connection != null && connection.isConnected) {
            return sendPacketOverConnection(connection, packet)
        }
        
        // Initiate connection if needed
        return connectionManager.initiateConnection(peerID)
    }
    
    fun sendPrivateMessage(content: String, toPeerID: String, recipientNickname: String, messageID: String): Boolean {
        if (content.isEmpty() || toPeerID.isEmpty()) return false
        
        // Check if we have an existing connection
        val connection = connectionTracker.getConnection(toPeerID)
        
        if (connection != null && connection.isConnected) {
            // Send immediately
            transportScope.launch {
                Log.d(TAG, "Sending private message to $toPeerID via existing Wi-Fi Aware connection")
                try {
                    // Create TLV-encoded private message like Bluetooth does
                    val privateMessage = com.bitchat.android.model.PrivateMessagePacket(
                        messageID = messageID,
                        content = content
                    )
                    
                    val tlvData = privateMessage.encode()
                    if (tlvData == null) {
                        Log.e(TAG, "Failed to encode private message with TLV")
                        return@launch
                    }
                    
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.MESSAGE.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(toPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = tlvData,
                        signature = null,
                        ttl = 1u  // Direct message, no mesh hopping
                    )
                    
                    // Sign the packet
                    val signature = encryptionService.signData(packet.toBinaryDataForSigning()!!)
                    if (signature != null) {
                        packet.signature = signature
                    }
                    
                    sendMessage(toPeerID, packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send message packet for $toPeerID: ${e.message}")
                }
            }
            return true // Message sent
        } else {
            // No connection - initiate one but return false so MessageRouter can use fallback
            Log.d(TAG, "No Wi-Fi Aware connection to $toPeerID (connection=$connection, isConnected=${connection?.isConnected}), initiating connection")
            connectionManager.initiateConnection(toPeerID)
            return false // Message not sent, needs queuing
        }
    }
    
    fun getPeerList(): List<String> {
        return discoveryManager.getPeerList()
    }
    
    fun getPeerNickname(peerID: String): String {
        return discoveryManager.getPeer(peerID)?.nickname ?: peerID
    }
    
    fun hasConnection(peerID: String): Boolean {
        return connectionTracker.hasConnection(peerID)
    }
    
    fun initiateConnection(peerID: String): Boolean {
        return connectionManager.initiateConnection(peerID)
    }
    
    fun getDebugStatus(): String {
        return """
            WiFi Aware Transport Status:
            - Active: $isActive
            - My Peer ID: $myPeerID
            - WiFi Aware Available: ${wifiAwareManager?.isAvailable ?: false}
            - Session Active: ${wifiAwareSession != null}
            - Max Data Paths: ${connectionTracker.connectionState.maxDataPaths}
            - Active Connections: ${connectionTracker.getActiveConnectionCount()}
            - Discovered Peers: ${discoveryManager.getPeerList().size}
            - Peer List: ${discoveryManager.getPeerList().joinToString(", ")}
        """.trimIndent()
    }
    
    // Private helper methods
    
    private fun handleClientConnected(peerID: String, socket: Socket, role: ConnectionRole) {
        Log.d(TAG, "handleClientConnected called for peer $peerID with role $role")
        
        val connection = WiFiAwareConnection(
            peerID = peerID,
            role = role,
            socket = socket
        )
        
        connectionTracker.addConnection(connection)
        Log.d(TAG, "Added connection to tracker for peer $peerID - total connections: ${connectionTracker.getActiveConnectionCount()}")
        
        delegate?.didConnect(peerID)
        
        // Start socket reader for incoming messages
        startSocketReader(connection)
        
        // Flush any queued messages for this peer
        try {
            com.bitchat.android.services.MessageRouter.tryGetInstance()?.onWifiAwareConnected(peerID)
        } catch (e: Exception) {
            Log.w(TAG, "Could not notify MessageRouter of connection: ${e.message}")
        }
    }
    
    private fun sendPacketOverConnection(connection: WiFiAwareConnection, packet: BitchatPacket): Boolean {
        try {
            val socket = connection.socket ?: return false
            val data = BinaryProtocol.encode(packet) ?: return false
            
            socket.outputStream.write(data)
            socket.outputStream.flush()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet to peer ${connection.peerID}", e)
            return false
        }
    }
    
    private fun startSocketReader(connection: WiFiAwareConnection) {
        val socket = connection.socket ?: return
        
        try {
            val socketReader = WiFiAwareSocketReader(
                inputStream = socket.getInputStream(),
                connectionScope = transportScope,
                onPacketReceived = { packet, senderID ->
                    handleIncomingPacket(packet, senderID, connection.peerID)
                },
                onConnectionClosed = { peerID ->
                    handleConnectionClosed(peerID)
                }
            )
            
            socketReader.setPeerID(connection.peerID)
            connection.socketReader = socketReader
            socketReader.start()
            
            Log.d(TAG, "Started socket reader for peer ${connection.peerID}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting socket reader for peer ${connection.peerID}", e)
            handleConnectionClosed(connection.peerID)
        }
    }
    
    private fun handleIncomingPacket(packet: BitchatPacket, senderID: String, peerID: String) {
        Log.d(TAG, "Received packet type ${packet.type} from $senderID via $peerID")
        delegate?.didReceiveMessage(packet, senderID)
    }
    
    private fun handleConnectionClosed(peerID: String) {
        Log.d(TAG, "Connection closed for peer $peerID")
        
        // Clean up the connection
        connectionTracker.removeConnection(peerID)
        
        // Stop server if it was for this peer
        if (serverManager.getActiveServerPeerID() == peerID) {
            serverManager.stopServer()
        }
        
        // Stop client if it was for this peer
        if (clientManager.getActiveClientPeerID() == peerID) {
            clientManager.disconnectClient()
        }
        
        // Notify delegate
        delegate?.didDisconnect(peerID)
    }
    
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
                tempID = tempID.substring(2)
                index++
            } else {
                break
            }
        }
        
        return result
    }
}

// Extension to access internal discovery session (for server/client managers)
internal val WiFiAwareDiscoveryManager.publishDiscoverySession: PublishDiscoverySession?
    get() = try {
        val field = WiFiAwareDiscoveryManager::class.java.getDeclaredField("publishDiscoverySession")
        field.isAccessible = true
        field.get(this) as? PublishDiscoverySession
    } catch (e: Exception) {
        null
    }

internal val WiFiAwareDiscoveryManager.subscribeDiscoverySession: SubscribeDiscoverySession?
    get() = try {
        val field = WiFiAwareDiscoveryManager::class.java.getDeclaredField("subscribeDiscoverySession")
        field.isAccessible = true
        field.get(this) as? SubscribeDiscoverySession
    } catch (e: Exception) {
        null
    }