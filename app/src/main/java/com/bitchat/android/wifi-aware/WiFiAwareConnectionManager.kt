package com.bitchat.android.`wifi-aware`

import android.net.wifi.aware.PeerHandle
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.BinaryProtocol
import com.bitchat.android.protocol.MessageType

/**
 * Manages Wi-Fi Aware connection negotiation and protocol
 */
@RequiresApi(Build.VERSION_CODES.S)
class WiFiAwareConnectionManager(
    private val discoveryManager: WiFiAwareDiscoveryManager,
    private val connectionTracker: WiFiAwareConnectionTracker
) {
    companion object {
        private const val TAG = "WiFiAwareConnManager"
        
        // Connection message types
        const val MSG_CONNECTION_REQUEST = "CONNECTION_REQUEST"
        const val MSG_CONNECTION_UNAVAILABLE = "CONNECTION_UNAVAILABLE"
        const val MSG_CONNECTION_REDIRECT = "CONNECTION_REDIRECT"  // Role swap in progress
    }
    
    interface ConnectionDelegate {
        fun onServerNeeded(peerInfo: WiFiAwareDiscoveryManager.PeerInfo)
        fun onClientConnectionNeeded(peerInfo: WiFiAwareDiscoveryManager.PeerInfo)
        fun onConnectionFailed(peerID: String, reason: String)
        fun onConnectionRedirect(peerID: String)
    }
    
    var delegate: ConnectionDelegate? = null
    
    fun initiateConnection(peerID: String): Boolean {
        val peerInfo = discoveryManager.getPeer(peerID) ?: run {
            Log.w(TAG, "Cannot initiate connection - peer $peerID not found")
            return false
        }
        
        // Determine our capabilities
        val canBeClient = connectionTracker.connectionState.canBeClient()
        val canBeServer = connectionTracker.connectionState.canBeServer()

        // TODO: key exchange should be handled while negotiating the connection so that
        //  a shared passphrase can be computed and used when setting up the network
        
        when {
            // Can only be server -> must be server
            canBeServer && !canBeClient -> {
                if (peerInfo.subscriberHandle != null) {
                    delegate?.onServerNeeded(peerInfo)
                    // Send via publish session to their subscriber
                    val message = "$MSG_CONNECTION_REQUEST:false" // Can't also be client
                    discoveryManager.sendMessageToSubscriber(peerInfo.subscriberHandle, message.toByteArray())
                    return true
                }
            }
            // Can only be client -> must be client
            !canBeServer && canBeClient -> {
                if (peerInfo.publisherHandle != null) {
                    // Send via subscribe session to their publisher
                    discoveryManager.sendMessageToPublisher(peerInfo.publisherHandle, MSG_CONNECTION_REQUEST.toByteArray())
                    return true
                }
            }
            // Can be both -> optimistically choose server
            canBeServer && canBeClient -> {
                if (peerInfo.subscriberHandle != null) {
                    delegate?.onServerNeeded(peerInfo)
                    // Send via publish session to their subscriber
                    val message = "$MSG_CONNECTION_REQUEST:true" // Can also be client
                    discoveryManager.sendMessageToSubscriber(peerInfo.subscriberHandle, message.toByteArray())
                    return true
                } else if (peerInfo.publisherHandle != null) {
                    // Fallback to client if no subscriber handle
                    discoveryManager.sendMessageToPublisher(peerInfo.publisherHandle, MSG_CONNECTION_REQUEST.toByteArray())
                    return true
                }
            }
            // Cannot be either -> fail
            else -> {
                Log.w(TAG, "Cannot establish connection - no available roles")
                delegate?.onConnectionFailed(peerID, "No available connection roles")
                return false
            }
        }
        
        Log.w(TAG, "Cannot establish connection - no suitable handles for peer $peerID")
        delegate?.onConnectionFailed(peerID, "No suitable handles available")
        return false
    }
    
    fun handleDiscoveryMessage(peerHandle: PeerHandle, message: ByteArray, fromPublisher: Boolean) {
        // First, check if it's an announcement
        val packet = BinaryProtocol.decode(message)
        if (packet != null && packet.type == MessageType.ANNOUNCE.value) {
            // This is an announcement
            discoveryManager.handleAnnouncementMessage(peerHandle, message, fromPublisher)
            return
        }
        
        // Otherwise, handle as connection message
        val messageStr = message.toString(Charsets.UTF_8)
        
        if (fromPublisher) {
            // Message received in our subscriber session
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
                    Log.w(TAG, "Unknown message from publisher: $messageStr")
                }
            }
        } else {
            // Message received in our publisher session
            when {
                messageStr == MSG_CONNECTION_REQUEST -> {
                    // Request from subscriber asking to be client (we should be server)
                    handleIncomingClientRequest(peerHandle)
                }
                messageStr == MSG_CONNECTION_UNAVAILABLE -> {
                    handleConnectionUnavailable(peerHandle)
                }
                else -> {
                    Log.w(TAG, "Unknown message from subscriber: $messageStr")
                }
            }
        }
    }
    
    private fun handleIncomingClientRequest(subscriberHandle: PeerHandle) {
        val peerInfo = discoveryManager.getPeerByHandle(subscriberHandle) ?: run {
            Log.w(TAG, "Received client request from unknown subscriber")
            return
        }
        
        if (connectionTracker.connectionState.canBeServer()) {
            delegate?.onServerNeeded(peerInfo)
            // Client should connect when they receive our network info
        } else {
            // Send unavailable message
            discoveryManager.sendMessageToSubscriber(subscriberHandle, MSG_CONNECTION_UNAVAILABLE.toByteArray())
        }
    }
    
    private fun handleIncomingServerOffer(publisherHandle: PeerHandle, theyCanAlsoBeClient: Boolean) {
        val peerInfo = discoveryManager.getPeerByHandle(publisherHandle) ?: run {
            Log.w(TAG, "Received server offer from unknown publisher")
            return
        }
        
        if (connectionTracker.connectionState.canBeClient()) {
            // Connect as client
            delegate?.onClientConnectionNeeded(peerInfo)
        } else if (connectionTracker.connectionState.canBeServer() && theyCanAlsoBeClient && peerInfo.subscriberHandle != null) {
            // We can't be client but they can, so redirect
            // First, tell them via subscriber to redirect (not final unavailable)
            discoveryManager.sendMessageToPublisher(publisherHandle, MSG_CONNECTION_REDIRECT.toByteArray())
            
            // Then start our server and invite them to connect
            delegate?.onServerNeeded(peerInfo)
            val message = "$MSG_CONNECTION_REQUEST:false" // We can't also be client
            discoveryManager.sendMessageToSubscriber(peerInfo.subscriberHandle, message.toByteArray())
        } else {
            // No compatible configuration - this is final
            discoveryManager.sendMessageToPublisher(publisherHandle, MSG_CONNECTION_UNAVAILABLE.toByteArray())
        }
    }
    
    private fun handleConnectionRedirect(publisherHandle: PeerHandle) {
        val peerInfo = discoveryManager.getPeerByHandle(publisherHandle) ?: run {
            Log.w(TAG, "Received redirect from unknown publisher")
            return
        }
        
        Log.d(TAG, "Connection redirect with peer ${peerInfo.peerID} - shutting down server and waiting for new request")
        delegate?.onConnectionRedirect(peerInfo.peerID)
    }
    
    private fun handleConnectionUnavailable(peerHandle: PeerHandle) {
        val peerInfo = discoveryManager.getPeerByHandle(peerHandle) ?: return
        
        Log.w(TAG, "Connection unavailable with peer ${peerInfo.peerID} - falling back to BLE")
        delegate?.onConnectionFailed(peerInfo.peerID, "Connection unavailable - no compatible roles")
    }
}