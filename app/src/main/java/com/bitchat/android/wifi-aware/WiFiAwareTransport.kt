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
    }

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
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

            wifiAwareManager!!.attach(object : AttachCallback() {
                override fun onAttached(session: WifiAwareSession) {
                    Log.d(TAG, "WiFi Aware attached successfully")
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
                // First, try to decode as BitchatPacket to check type
                val packet = BinaryProtocol.decode(message)
                if (packet != null && packet.type == MessageType.ANNOUNCE.value) {
                    // This is an announcement from a subscriber
                    handleAnnounce(peerHandle, message, isFromPublisher = false)
                } else {
                    // Handle other message types (connection negotiation, etc.)
                    Log.d(TAG, "Received non-announcement message in publisher")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling publish message", e)
            }
        }
    }
    
    private fun handleSubscribeMessage(peerHandle: PeerHandle, message: ByteArray) {
        transportScope.launch {
            try {
                // Handle messages received in subscriber session
                // These would typically be connection negotiation messages
                Log.d(TAG, "Received message in subscriber session")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling subscribe message", e)
            }
        }
    }

    fun getPeerList(): List<String> {
        return activePeers.keys.toList()
    }
    
    // TODO: Future connection establishment methods will be added here
    // These will handle:
    // - Creating WiFi Aware network connections to discovered peers
    // - Establishing secure channels using the exchanged Noise keys
    // - Transitioning from discovery phase to connected phase

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