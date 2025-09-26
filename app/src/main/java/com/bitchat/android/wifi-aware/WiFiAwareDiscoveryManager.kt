package com.bitchat.android.`wifi-aware`

import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.MessagePadding
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages Wi-Fi Aware discovery operations including publishing, subscribing,
 * and handling announcements
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareDiscoveryManager(
    private val encryptionService: EncryptionService,
    private val getNickname: () -> String?
) {
    companion object {
        private const val TAG = "WiFiAwareDiscovery"
        const val SERVICE_NAME = "BitchatWiFiAware"
        private const val WIFI_AWARE_SSI_MAX_SIZE = 255
    }
    
    // Discovery sessions
    private var wifiAwareSession: WifiAwareSession? = null
    internal var publishDiscoverySession: PublishDiscoverySession? = null
        private set
    internal var subscribeDiscoverySession: SubscribeDiscoverySession? = null
        private set
    
    // Peer tracking
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    private var myAnnouncementPacket: ByteArray? = null
    
    // Coroutine scope for async operations
    private val discoveryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    val myPeerID: String
        get() = encryptionService.getIdentityFingerprint().take(16)
    
    data class PeerInfo(
        val publisherHandle: PeerHandle? = null,     // For sending to them via our subscribeDiscoverySession
        val subscriberHandle: PeerHandle? = null,    // For sending to them via our publishDiscoverySession
        val peerID: String,
        val nickname: String,
        val noisePublicKey: ByteArray,
        val signingPublicKey: ByteArray
    )
    
    interface DiscoveryDelegate {
        fun onPeerDiscovered(peerInfo: PeerInfo)
        fun onPeerUpdated(peerInfo: PeerInfo)
        fun onPeerListChanged(peers: List<String>)
        fun onDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray, fromPublisher: Boolean)
    }
    
    var delegate: DiscoveryDelegate? = null
    
    fun start(wifiAwareSession: WifiAwareSession) {
        this.wifiAwareSession = wifiAwareSession
        startPublishing()
        startSubscribing()
    }
    
    private fun startPublishing() {
        val announcementData = createAnnouncementPacket()
        if (announcementData == null) {
            Log.e(TAG, "Failed to create announcement packet for publishing")
            return
        }
        
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
                // Messages from subscribers
                delegate?.onDiscoveryMessage(peerHandle, message, fromPublisher = false)
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
                
                // Process announcement from SSI
                handleAnnounce(peerHandle, serviceSpecificInfo, isFromPublisher = true)
                
                // Send our announcement back
                myAnnouncementPacket?.let { announcement ->
                    Log.d(TAG, "Sending announcement back to peer's publisher")
                    subscribeDiscoverySession?.sendMessage(peerHandle, 0, announcement)
                }
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                // Messages from publishers
                delegate?.onDiscoveryMessage(peerHandle, message, fromPublisher = true)
            }
        }, null)
    }
    
    fun sendMessageToPublisher(peerHandle: PeerHandle, message: ByteArray) {
        subscribeDiscoverySession?.sendMessage(peerHandle, 0, message)
    }
    
    fun sendMessageToSubscriber(peerHandle: PeerHandle, message: ByteArray) {
        publishDiscoverySession?.sendMessage(peerHandle, 0, message)
    }
    
    fun handleAnnouncementMessage(peerHandle: PeerHandle, message: ByteArray, isFromPublisher: Boolean) {
        handleAnnounce(peerHandle, message, isFromPublisher)
    }
    
    private fun createAnnouncementPacket(): ByteArray? {
        try {
            val nickname = getNickname() ?: myPeerID
            
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for announcement")
                return null
            }
            
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for announcement")
                return null
            }
            
            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
            val tlvPayload = announcement.encode()
            if (tlvPayload == null) {
                Log.e(TAG, "Failed to encode announcement as TLV")
                return null
            }
            
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 0u,
                senderID = myPeerID,
                payload = tlvPayload
            )
            
            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket
            
            var binary = BinaryProtocol.encode(signedPacket)
            if (binary == null) {
                Log.e(TAG, "Failed to encode announcement to binary")
                return null
            }
            
            // Remove padding for Wi-Fi Aware SSI
            val unpaddedBinary = MessagePadding.unpad(binary)
            
            if (unpaddedBinary.size > WIFI_AWARE_SSI_MAX_SIZE) {
                Log.e(TAG, "Announcement too large for WiFi Aware SSI: ${unpaddedBinary.size} bytes (max: $WIFI_AWARE_SSI_MAX_SIZE)")
                return null
            }
            
            // Pad to 255 bytes for Wi-Fi Aware
            binary = MessagePadding.pad(unpaddedBinary, WIFI_AWARE_SSI_MAX_SIZE)
            
            return binary
            
        } catch (e: Exception) {
            Log.e(TAG, "Error creating announcement packet", e)
            return null
        }
    }
    
    private fun handleAnnounce(peerHandle: PeerHandle, announcementData: ByteArray, isFromPublisher: Boolean) {
        discoveryScope.launch {
            try {
                val packet = BinaryProtocol.decode(announcementData)
                if (packet == null) {
                    Log.e(TAG, "Failed to decode announcement packet")
                    return@launch
                }
                
                if (packet.type != MessageType.ANNOUNCE.value) {
                    Log.w(TAG, "Packet is not an announcement: type=${packet.type}")
                    return@launch
                }
                
                val verificationData = packet.toBinaryDataForSigning()
                val signature = packet.signature
                if (verificationData == null || signature == null) {
                    Log.w(TAG, "Cannot verify announcement - missing data or signature")
                    return@launch
                }
                
                val announcement = IdentityAnnouncement.decode(packet.payload)
                if (announcement == null) {
                    Log.e(TAG, "Failed to decode identity announcement from payload")
                    return@launch
                }
                
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
                
                if (existingPeer != null) {
                    delegate?.onPeerUpdated(peerInfo)
                } else {
                    delegate?.onPeerDiscovered(peerInfo)
                }
                
                delegate?.onPeerListChanged(activePeers.keys.toList())
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing announcement", e)
            }
        }
    }
    
    fun getPeer(peerID: String): PeerInfo? = activePeers[peerID]
    
    fun getPeerByHandle(peerHandle: PeerHandle): PeerInfo? {
        return activePeers.values.find { 
            it.publisherHandle == peerHandle || it.subscriberHandle == peerHandle 
        }
    }
    
    fun getPeerList(): List<String> = activePeers.keys.toList()
    
    fun stop() {
        publishDiscoverySession?.close()
        subscribeDiscoverySession?.close()
        discoveryScope.cancel()
        activePeers.clear()
    }
}