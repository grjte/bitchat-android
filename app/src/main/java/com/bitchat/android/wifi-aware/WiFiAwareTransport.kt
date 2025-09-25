package com.bitchat.android.`wifi-aware`

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.aware.*
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.*
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.concurrent.ConcurrentHashMap

interface WiFiAwareDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveReadReceipt(messageID: String, from: String)
    fun didReceiveDeliveryAck(messageID: String, from: String)
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
        private const val MESSAGE_TYPE_PRIVATE = 1
        private const val MESSAGE_TYPE_READ_RECEIPT = 2
        private const val MESSAGE_TYPE_DELIVERY_ACK = 3
        private const val MESSAGE_TYPE_ANNOUNCEMENT = 4
        private const val MAX_MESSAGE_SIZE = 255 // WiFi Aware message size limit
    }

    private var wifiAwareManager: WifiAwareManager? = null
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishDiscoverySession: PublishDiscoverySession? = null
    private var subscribeDiscoverySession: SubscribeDiscoverySession? = null
    
    private val activePeers = ConcurrentHashMap<String, PeerInfo>()
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val encryptionService = EncryptionService(context)
    
    val myPeerID: String
        get() = encryptionService.getIdentityFingerprint().take(16)
    
    var isActive = false
        private set

    private data class PeerInfo(
        val peerHandle: PeerHandle,
        val lastSeen: Long = System.currentTimeMillis()
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

    private fun startPublishing() {
        val config = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .setPublishType(PublishConfig.PUBLISH_TYPE_UNSOLICITED)
            .setTtlSec(0) // 0 means publish until explicitly stopped
            .build()

        wifiAwareSession?.publish(config, object : DiscoverySessionCallback() {
            override fun onPublishStarted(session: PublishDiscoverySession) {
                Log.d(TAG, "Publishing started")
                publishDiscoverySession = session
                // Send initial announcement
                sendAnnouncementBroadcast()
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
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
                Log.d(TAG, "Service discovered from peer")
                // Send announcement to newly discovered peer
                sendAnnouncementToPeer(peerHandle)
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                handleIncomingMessage(peerHandle, message)
            }
        }, null)
    }

    private fun handleIncomingMessage(peerHandle: PeerHandle, data: ByteArray) {
        transportScope.launch {
            try {
                if (data.isEmpty()) return@launch
                
                val messageType = data[0].toInt()
                val payload = data.copyOfRange(1, data.size)
                
                when (messageType) {
                    MESSAGE_TYPE_ANNOUNCEMENT -> handleAnnouncement(peerHandle, payload)
                    MESSAGE_TYPE_PRIVATE -> handlePrivateMessage(peerHandle, payload)
                    MESSAGE_TYPE_READ_RECEIPT -> handleReadReceipt(peerHandle, payload)
                    MESSAGE_TYPE_DELIVERY_ACK -> handleDeliveryAck(peerHandle, payload)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling incoming message", e)
            }
        }
    }

    private suspend fun handleAnnouncement(peerHandle: PeerHandle, data: ByteArray) {
        try {
            val announcement = String(data, StandardCharsets.UTF_8)
            val parts = announcement.split("|")
            if (parts.size >= 2) {
                val peerID = parts[0]
                val nickname = parts[1]
                
                activePeers[peerID] = PeerInfo(peerHandle)
                
                withContext(Dispatchers.Main) {
                    delegate?.didUpdatePeerList(activePeers.keys.toList())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling announcement", e)
        }
    }

    private suspend fun handlePrivateMessage(peerHandle: PeerHandle, data: ByteArray) {
        try {
            val messageStr = String(data, StandardCharsets.UTF_8)
            val parts = messageStr.split("|", limit = 5)
            if (parts.size >= 5) {
                val messageID = parts[0]
                val senderID = parts[1]
                val senderNickname = parts[2]
                val timestamp = parts[3].toLongOrNull() ?: System.currentTimeMillis()
                val content = parts[4]
                
                val message = BitchatMessage(
                    id = messageID,
                    sender = senderNickname,
                    content = content,
                    timestamp = Date(timestamp),
                    isPrivate = true,
                    senderPeerID = senderID
                )
                
                withContext(Dispatchers.Main) {
                    delegate?.didReceiveMessage(message)
                }
                
                // Send delivery acknowledgment
                sendDeliveryAck(messageID, senderID)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling private message", e)
        }
    }

    private suspend fun handleReadReceipt(peerHandle: PeerHandle, data: ByteArray) {
        try {
            val receipt = String(data, StandardCharsets.UTF_8)
            val parts = receipt.split("|")
            if (parts.size >= 2) {
                val messageID = parts[0]
                val readerID = parts[1]
                
                withContext(Dispatchers.Main) {
                    delegate?.didReceiveReadReceipt(messageID, readerID)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling read receipt", e)
        }
    }

    private suspend fun handleDeliveryAck(peerHandle: PeerHandle, data: ByteArray) {
        try {
            val ack = String(data, StandardCharsets.UTF_8)
            val parts = ack.split("|")
            if (parts.size >= 2) {
                val messageID = parts[0]
                val receiverID = parts[1]
                
                withContext(Dispatchers.Main) {
                    delegate?.didReceiveDeliveryAck(messageID, receiverID)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling delivery ack", e)
        }
    }

    fun sendPrivateMessage(
        content: String,
        to: String,
        recipientNickname: String,
        messageID: String? = null
    ) {
        transportScope.launch {
            try {
                val peer = activePeers[to]
                if (peer == null) {
                    Log.w(TAG, "Peer $to not found in active peers")
                    return@launch
                }
                
                val id = messageID ?: UUID.randomUUID().toString()
                val nickname = getNickname() ?: myPeerID
                val message = "$id|$myPeerID|$nickname|${System.currentTimeMillis()}|$content"
                val data = byteArrayOf(MESSAGE_TYPE_PRIVATE.toByte()) + message.toByteArray(StandardCharsets.UTF_8)
                
                if (data.size > MAX_MESSAGE_SIZE) {
                    Log.w(TAG, "Message too large for WiFi Aware. Size: ${data.size}")
                    // TODO: Implement fragmentation
                    return@launch
                }
                
                publishDiscoverySession?.sendMessage(peer.peerHandle, 0, data)
                Log.d(TAG, "Sent private message to $to")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending private message", e)
            }
        }
    }

    fun sendReadReceipt(messageID: String, to: String) {
        transportScope.launch {
            try {
                val peer = activePeers[to]
                if (peer == null) {
                    Log.w(TAG, "Peer $to not found for read receipt")
                    return@launch
                }
                
                val receipt = "$messageID|$myPeerID"
                val data = byteArrayOf(MESSAGE_TYPE_READ_RECEIPT.toByte()) + receipt.toByteArray(StandardCharsets.UTF_8)
                
                publishDiscoverySession?.sendMessage(peer.peerHandle, 0, data)
                Log.d(TAG, "Sent read receipt for $messageID to $to")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending read receipt", e)
            }
        }
    }

    private fun sendDeliveryAck(messageID: String, to: String) {
        transportScope.launch {
            try {
                val peer = activePeers[to]
                if (peer == null) {
                    Log.w(TAG, "Peer $to not found for delivery ack")
                    return@launch
                }
                
                val ack = "$messageID|$myPeerID"
                val data = byteArrayOf(MESSAGE_TYPE_DELIVERY_ACK.toByte()) + ack.toByteArray(StandardCharsets.UTF_8)
                
                publishDiscoverySession?.sendMessage(peer.peerHandle, 0, data)
                Log.d(TAG, "Sent delivery ack for $messageID to $to")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending delivery ack", e)
            }
        }
    }

    private fun sendAnnouncementBroadcast() {
        transportScope.launch {
            try {
                val nickname = getNickname() ?: myPeerID
                val announcement = "$myPeerID|$nickname"
                val data = byteArrayOf(MESSAGE_TYPE_ANNOUNCEMENT.toByte()) + announcement.toByteArray(StandardCharsets.UTF_8)
                
                // WiFi Aware doesn't support true broadcast, so we send to all known peers
                activePeers.forEach { (_, peer) ->
                    publishDiscoverySession?.sendMessage(peer.peerHandle, 0, data)
                }
                
                Log.d(TAG, "Sent announcement broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending announcement", e)
            }
        }
    }

    private fun sendAnnouncementToPeer(peerHandle: PeerHandle) {
        transportScope.launch {
            try {
                val nickname = getNickname() ?: myPeerID
                val announcement = "$myPeerID|$nickname"
                val data = byteArrayOf(MESSAGE_TYPE_ANNOUNCEMENT.toByte()) + announcement.toByteArray(StandardCharsets.UTF_8)
                
                publishDiscoverySession?.sendMessage(peerHandle, 0, data)
                Log.d(TAG, "Sent announcement to peer")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending announcement to peer", e)
            }
        }
    }

    fun getPeerList(): List<String> {
        return activePeers.keys.toList()
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