package com.bitchat.android.`wifi-aware`

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

interface WiFiAwareDelegate {
    fun didUpdatePeerList(peers: List<String>)
}

@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareTransport(
    private val context: Context,
    var delegate: WiFiAwareDelegate? = null,
    private val getNickname: () -> String? = { null }
) {
    companion object {
        private const val TAG = "WiFiAwareTransport"
        private const val SERVICE_NAME = "BitchatWiFiAware"
        private const val WIFI_AWARE_SSI_MAX_SIZE = 255
        
        // Connection message types
        private const val MSG_CONNECTION_REQUEST = "CONNECTION_REQUEST"
        private const val MSG_CONNECTION_UNAVAILABLE = "CONNECTION_UNAVAILABLE"
        private const val MSG_CONNECTION_REDIRECT = "CONNECTION_REDIRECT"  // Role swap in progress
    }

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    private val activeConnections = ConcurrentHashMap<String, WiFiAwareConnection>()
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encryptionService = EncryptionService(context)
    private var myAnnouncementPacket: ByteArray? = null  // Cache our announcement
    
    val myPeerID: String
        get() = encryptionService.getIdentityFingerprint().take(16)
    
    var isActive = false
        private set

    private data class PeerInfo(
        val publisherHandle: PeerHandle? = null,     // For sending to them via our subscribeDiscoverySession
        val subscriberHandle: PeerHandle? = null,    // For sending to them via our publishDiscoverySession
        val peerID: String,
        val nickname: String,
        val noisePublicKey: ByteArray,
        val signingPublicKey: ByteArray
    )
    
    private data class ConnectionState(
        var serverConnectionsCount: Int = 0,
        var hasClientConnection: Boolean = false,
        val maxDataPaths: Int
    ) {
        val usedDataPaths: Int 
            get() = serverConnectionsCount + (if (hasClientConnection) 1 else 0)
        
        fun canBeServer() = usedDataPaths < maxDataPaths
        fun canBeClient() = !hasClientConnection && usedDataPaths < maxDataPaths
    }
    
    private enum class ConnectionRole {
        CLIENT,
        SERVER
    }
    
    private data class WiFiAwareConnection(
        val peerID: String,
        val role: ConnectionRole,
        val socket: java.net.Socket? = null,
        val serverSocket: java.net.ServerSocket? = null,
        val network: android.net.Network? = null
    )
    
    private var connectionState = ConnectionState(
        maxDataPaths = 2  // Will be updated when WiFi Aware is initialized
    )

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

            // Update connection state with actual capabilities
            connectionState = ConnectionState(
                maxDataPaths = wifiAwareManager!!.characteristics?.numberOfSupportedDataPaths ?: 2
            )
            
            wifiAwareManager!!.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "WiFi Aware attached successfully")
                    Log.d(TAG, "Supported data paths: ${connectionState.maxDataPaths}")
                    wifiAwareSession = session
                    startPublishing()
                    startSubscribing()
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
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        wifiAwareSession?.close()
        transportScope.cancel()
        activePeers.clear()
    }

    private fun createAnnouncementPacket(): ByteArray? {
        try {
            val nickname = getNickname() ?: myPeerID
            
            // Get the static public key for the announcement
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for announcement")
                return null
            }
            
            // Get the signing public key for the announcement
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for announcement")
                return null
            }
            
            // Create IdentityAnnouncement with TLV encoding
            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
            val tlvPayload = announcement.encode()
            if (tlvPayload == null) {
                Log.e(TAG, "Failed to encode announcement as TLV")
                return null
            }
            
            // Create announcement packet with TTL=0 for discovery
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 0u,  // No forwarding for discovery
                senderID = myPeerID,
                payload = tlvPayload
            )
            
            // Sign the packet using our signing key (exactly like iOS)
            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket
            
            // Convert to binary
            var binary = BinaryProtocol.encode(signedPacket)
            if (binary == null) {
                Log.e(TAG, "Failed to encode announcement to binary")
                return null
            }


            // MessagePadding uses blocks of 256n but Wi-Fi Aware SSI has limit of 255 bytes.
            // Un-pad, check length, re-pad to 255
            // TODO: it would be better to pad correctly during encoding;
            // for now, we're minimizing changes to other sections of the codebase
            val unpaddedBinary = MessagePadding.unpad(binary)
            if (unpaddedBinary.size > WIFI_AWARE_SSI_MAX_SIZE) {
                Log.e(TAG, "Announcement too large for WiFi Aware SSI: ${unpaddedBinary.size} bytes (max: $WIFI_AWARE_SSI_MAX_SIZE)")
                return null
            }
            binary = MessagePadding.pad(unpaddedBinary, WIFI_AWARE_SSI_MAX_SIZE)

            return binary
        } catch (e: Exception) {
            Log.e(TAG, "Error creating announcement packet", e)
            return null
        }
    }

    private fun startPublishing() {
        // Create announcement packet for Service Specific Info
        val announcementData = createAnnouncementPacket()
        if (announcementData == null) {
            Log.e(TAG, "Failed to create announcement packet for publishing")
            return
        }
        
        // Cache it for later use
        myAnnouncementPacket = announcementData
        
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setServiceSpecificInfo(announcementData)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setTtlSec(0) // 0 means publish until explicitly stopped
            .build()

        wifiAwareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d(TAG, "Publishing started with announcement in SSI")
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                // Messages received here are from subscribers
                handlePublishMessage(peerHandle, message)
            }
        }, null)
    }

    private fun startSubscribing() {
        val config = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setSubscribeType(SubscribeConfig.SUBSCRIBE_TYPE_PASSIVE)
            .setTtlSec(0) // 0 means subscribe until explicitly stopped
            .build()

        wifiAwareSession?.subscribe(config, object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                Log.d(TAG, "Subscribing started")
                subscribeDiscoverySession = session
            }

            override fun onServiceDiscovered(
                peerHandle: PeerHandle,
                serviceSpecificInfo: ByteArray,
                matchFilter: List<ByteArray>
            ) {
                Log.d(TAG, "Service discovered from peer, SSI size: ${serviceSpecificInfo.size}")
                
                // Process announcement from Service Specific Info
                // This gives us their publisher handle
                handleAnnounce(peerHandle, serviceSpecificInfo, isFromPublisher = true)
                
                // Send our announcement back to establish bidirectional handle mapping
                myAnnouncementPacket?.let { announcement ->
                    Log.d(TAG, "Sending announcement back to peer's publisher")
                    subscribeDiscoverySession?.sendMessage(peerHandle, 0, announcement)
                }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                // Could be an announcement or other message type
                handleSubscribeMessage(peerHandle, message)
            }
        }, null)
    }
    
    private fun handleAnnounce(peerHandle: PeerHandle, announcementData: ByteArray, isFromPublisher: Boolean) {
        transportScope.launch {
            try {
                // Decode the BitchatPacket from binary
                val packet = BinaryProtocol.decode(announcementData)
                if (packet == null) {
                    Log.e(TAG, "Failed to decode announcement packet from SSI")
                    return@launch
                }
                
                // Verify it's an announcement
                if (packet.type != MessageType.ANNOUNCE.value) {
                    Log.w(TAG, "SSI packet is not an announcement: type=${packet.type}")
                    return@launch
                }
                
                // Verify signature
                val verificationData = packet.toBinaryDataForSigning()
                val signature = packet.signature
                if (verificationData == null || signature == null) {
                    Log.w(TAG, "Cannot verify announcement - missing data or signature")
                    return@launch
                }
                
                // Decode the identity announcement
                val announcement = IdentityAnnouncement.decode(packet.payload)
                if (announcement == null) {
                    Log.e(TAG, "Failed to decode identity announcement from payload")
                    return@launch
                }
                
                // Verify signature using the signing public key from announcement
                val verified = encryptionService.verifyEd25519Signature(
                    signature,
                    verificationData,
                    announcement.signingPublicKey
                )
                
                val peerID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                
                if (!verified) {
                    Log.w(TAG, "Invalid signature on announcement from $peerID")
                    return@launch
                }
                
                Log.d(TAG, "Verified announcement from $peerID: ${announcement.nickname} (${if (isFromPublisher) "publisher" else "subscriber"} handle)")
                
                // Check if we already know this peer
                val existingPeer = activePeers[peerID]
                
                val peerInfo = if (existingPeer != null) {
                    // Update with the new handle
                    if (isFromPublisher) {
                        existingPeer.copy(publisherHandle = peerHandle)
                    } else {
                        existingPeer.copy(subscriberHandle = peerHandle)
                    }
                } else {
                    // New peer
                    PeerInfo(
                        publisherHandle = if (isFromPublisher) peerHandle else null,
                        subscriberHandle = if (!isFromPublisher) peerHandle else null,
                        peerID = peerID,
                        nickname = announcement.nickname,
                        noisePublicKey = announcement.noisePublicKey,
                        signingPublicKey = announcement.signingPublicKey
                    )
                }
                
                activePeers[peerID] = peerInfo
                
                // Notify delegate
                withContext(Dispatchers.Main) {
                    delegate?.didUpdatePeerList(activePeers.keys.toList())
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing announcement from SSI", e)
            }
        }
    }


    private fun handlePublishMessage(peerHandle: PeerHandle, message: ByteArray) {
        transportScope.launch {
            try {
                // First, try to decode as BitchatPacket to check if it's an announcement
                val packet = BinaryProtocol.decode(message)
                if (packet != null && packet.type == MessageType.ANNOUNCE.value) {
                    // This is an announcement from a subscriber
                    handleAnnounce(peerHandle, message, isFromPublisher = false)
                    return@launch
                }
                
                // Otherwise, try to parse as a connection message
                val messageStr = message.toString(Charsets.UTF_8)
                
                when {
                    messageStr == MSG_CONNECTION_REQUEST -> {
                        // Request from subscriber asking to be client (we should be server)
                        handleIncomingClientRequest(peerHandle)
                    }
                    messageStr == MSG_CONNECTION_UNAVAILABLE -> {
                        handleConnectionUnavailable(peerHandle)
                    }
                    else -> {
                        Log.w(TAG, "Unknown message in publisher: $messageStr")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling publish message", e)
            }
        }
    }
    
    private fun handleSubscribeMessage(peerHandle: PeerHandle, message: ByteArray) {
        transportScope.launch {
            try {
                val messageStr = message.toString(Charsets.UTF_8)
                
                when {
                    messageStr.startsWith(MSG_CONNECTION_REQUEST) -> {
                        // Request from publisher saying they're ready as server
                        val parts = messageStr.split(":", limit = 2)
                        val canAlsoBeClient = parts.getOrNull(1)?.toBoolean() ?: false
                        handleIncomingServerOffer(peerHandle, canAlsoBeClient)
                    }
                    messageStr == MSG_CONNECTION_REDIRECT -> {
                        handleConnectionRedirect(peerHandle)
                    }
                    messageStr == MSG_CONNECTION_UNAVAILABLE -> {
                        handleConnectionUnavailable(peerHandle)
                    }
                    else -> {
                        Log.w(TAG, "Unknown message in subscriber: $messageStr")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling subscribe message", e)
            }
        }
    }

    fun getPeerList(): List<String> {
        return activePeers.keys.toList()
    }
    
    // Connection management
    fun sendMessage(peerID: String, packet: BitchatPacket): Boolean {
        // Check if connection exists
        val connection = activeConnections[peerID]
        if (connection != null) {
            return sendPacketOverConnection(connection, packet)
        }
        
        // Initiate connection if needed
        return initiateConnection(peerID)
    }
    
    private fun initiateConnection(peerID: String): Boolean {
        val peerInfo = activePeers[peerID] ?: run {
            Log.w(TAG, "Cannot initiate connection - peer $peerID not found")
            return false
        }
        
        // Determine our capabilities
        val canBeClient = connectionState.canBeClient()
        val canBeServer = connectionState.canBeServer()
        
        when {
            // Can only be server -> must be server
            canBeServer && !canBeClient -> {
                if (peerInfo.subscriberHandle != null) {
                    startServerForPeer(peerInfo)
                    // Send via publish session to their subscriber
                    val message = "$MSG_CONNECTION_REQUEST:false" // Can't also be client
                    publishDiscoverySession?.sendMessage(peerInfo.subscriberHandle, 0, message.toByteArray())
                    return true
                }
            }
            // Can only be client -> must be client
            !canBeServer && canBeClient -> {
                if (peerInfo.publisherHandle != null) {
                    // Send via subscribe session to their publisher
                    subscribeDiscoverySession?.sendMessage(peerInfo.publisherHandle, 0, MSG_CONNECTION_REQUEST.toByteArray())
                    return true
                }
            }
            // Can be both -> optimistically choose server
            canBeServer && canBeClient -> {
                if (peerInfo.subscriberHandle != null) {
                    startServerForPeer(peerInfo)
                    // Send via publish session to their subscriber
                    val message = "$MSG_CONNECTION_REQUEST:true" // Can also be client
                    publishDiscoverySession?.sendMessage(peerInfo.subscriberHandle, 0, message.toByteArray())
                    return true
                } else if (peerInfo.publisherHandle != null) {
                    // Fallback to client if no subscriber handle
                    subscribeDiscoverySession?.sendMessage(peerInfo.publisherHandle, 0, MSG_CONNECTION_REQUEST.toByteArray())
                    return true
                }
            }
            // Cannot be either -> fail
            else -> {
                Log.w(TAG, "Cannot establish connection - no available roles")
                return false
            }
        }
        
        Log.w(TAG, "Cannot establish connection - no suitable handles for peer $peerID")
        return false
    }
    
    private fun handleIncomingClientRequest(subscriberHandle: PeerHandle) {
        // Find peer by subscriber handle
        val peerInfo = activePeers.values.find { it.subscriberHandle == subscriberHandle } ?: run {
            Log.w(TAG, "Received client request from unknown subscriber")
            return
        }
        
        if (connectionState.canBeServer()) {
            startServerForPeer(peerInfo)
            // Client should connect when they receive our network info
        } else {
            // Send unavailable message
            publishDiscoverySession?.sendMessage(subscriberHandle, 0, MSG_CONNECTION_UNAVAILABLE.toByteArray())
        }
    }
    
    private fun handleIncomingServerOffer(publisherHandle: PeerHandle, theyCanAlsoBeClient: Boolean) {
        // Find peer by publisher handle
        val peerInfo = activePeers.values.find { it.publisherHandle == publisherHandle } ?: run {
            Log.w(TAG, "Received server offer from unknown publisher")
            return
        }
        
        if (connectionState.canBeClient()) {
            // Connect as client
            connectAsClientToPeer(peerInfo)
        } else if (connectionState.canBeServer() && theyCanAlsoBeClient && peerInfo.subscriberHandle != null) {
            // We can't be client but they can, so redirect
            // First, tell them via subscriber to redirect (not final unavailable)
            subscribeDiscoverySession?.sendMessage(publisherHandle, 0, MSG_CONNECTION_REDIRECT.toByteArray())
            
            // Then start our server and invite them to connect
            startServerForPeer(peerInfo)
            val message = "$MSG_CONNECTION_REQUEST:false" // We can't also be client
            publishDiscoverySession?.sendMessage(peerInfo.subscriberHandle, 0, message.toByteArray())
        } else {
            // No compatible configuration - this is final
            subscribeDiscoverySession?.sendMessage(publisherHandle, 0, MSG_CONNECTION_UNAVAILABLE.toByteArray())
        }
    }
    
    private fun handleConnectionRedirect(publisherHandle: PeerHandle) {
        val peerInfo = activePeers.values.find { it.publisherHandle == publisherHandle } ?: run {
            Log.w(TAG, "Received redirect from unknown publisher")
            return
        }
        
        Log.d(TAG, "Connection redirect with peer ${peerInfo.peerID} - shutting down server and waiting for new request")
        // TODO: Shut down any server we started for this peer
        // Do NOT fall back to BLE - expect a new connection request
    }
    
    private fun handleConnectionUnavailable(peerHandle: PeerHandle) {
        val peerInfo = activePeers.values.find { 
            it.publisherHandle == peerHandle || it.subscriberHandle == peerHandle 
        } ?: return
        
        Log.w(TAG, "Connection unavailable with peer ${peerInfo.peerID} - falling back to BLE")
        // TODO: Clean up any server we might have started
        // Let message router handle BLE fallback
    }
    
    private fun startServerForPeer(peerInfo: PeerInfo): Boolean {
        // TODO: Implement server socket setup
        Log.d(TAG, "Starting server for peer ${peerInfo.peerID}")
        connectionState.serverConnectionsCount++
        return true
    }
    
    private fun connectAsClientToPeer(peerInfo: PeerInfo): Boolean {
        // TODO: Implement client connection
        Log.d(TAG, "Connecting as client to peer ${peerInfo.peerID}")
        connectionState.hasClientConnection = true
        return true
    }
    
    private fun sendPacketOverConnection(connection: WiFiAwareConnection, packet: BitchatPacket): Boolean {
        // TODO: Implement packet sending over socket
        Log.d(TAG, "Sending packet to peer ${connection.peerID}")
        return true
    }

    fun getDebugStatus(): String {
        return """
            WiFi Aware Transport Status:
            - Active: $isActive
            - My Peer ID: $myPeerID
            - WiFi Aware Available: ${wifiAwareManager?.isAvailable ?: false}
            - Session Active: ${wifiAwareSession != null}
            - Publishing: ${publishDiscoverySession != null}
            - Subscribing: ${subscribeDiscoverySession != null}
            - Active Peers: ${activePeers.size}
            - Peer List: ${activePeers.keys.joinToString(", ")}
        """.trimIndent()
    }
}