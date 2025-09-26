package com.bitchat.android.`wifi-aware`

import android.util.Log
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import java.util.Date
import java.util.UUID

/**
 * Adapts WiFiAwareDelegate callbacks to BluetoothMeshDelegate interface
 * so Wi-Fi Aware discovery events can be handled by the existing MeshDelegateHandler
 */
class WiFiAwareDelegateAdapter(
    private val meshDelegate: BluetoothMeshDelegate,
    private val wifiAwareTransport: WiFiAwareTransport
) : WiFiAwareDelegate {
    
    companion object {
        private const val TAG = "WiFiAwareDelegateAdapter"
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didUpdatePeerList(peers)
    }
    
    override fun didConnect(peerID: String) {
        // Log connection event (BluetoothMeshDelegate doesn't have this method)
        Log.d(TAG, "Wi-Fi Aware connected to peer: $peerID")
        // Could trigger a peer list update if needed
    }
    
    override fun didDisconnect(peerID: String) {
        // Log disconnection event (BluetoothMeshDelegate doesn't have this method)
        Log.d(TAG, "Wi-Fi Aware disconnected from peer: $peerID")
        // Could trigger a peer list update if needed
    }
    
    override fun didReceiveMessage(packet: BitchatPacket, from: String) {
        // Convert BitchatPacket to BitchatMessage for mesh delegate
        when (packet.type) {
            MessageType.MESSAGE.value -> {
                val recipientID = packet.recipientID?.joinToString("") { "%02x".format(it) }
                val isPrivate = recipientID != null
                
                // For private messages, decode the TLV PrivateMessagePacket
                if (isPrivate) {
                    val privateMessage = com.bitchat.android.model.PrivateMessagePacket.decode(packet.payload)
                    if (privateMessage != null) {
                        // Look up sender nickname from Wi-Fi Aware discovery manager
                        val senderNickname = wifiAwareTransport.getPeerNickname(from)
                        
                        val message = BitchatMessage(
                            id = privateMessage.messageID,
                            sender = senderNickname,
                            content = privateMessage.content,
                            type = BitchatMessageType.Message,
                            timestamp = Date(packet.timestamp.toLong() * 1000),
                            isPrivate = true,
                            senderPeerID = from
                        )
                        
                        meshDelegate.didReceiveMessage(message)
                    } else {
                        Log.e(TAG, "Failed to decode private message from $from")
                    }
                } else {
                    // Public message - plain text
                    val content = String(packet.payload)
                    // Look up sender nickname from Wi-Fi Aware discovery manager
                    val senderNickname = wifiAwareTransport.getPeerNickname(from)
                    
                    val message = BitchatMessage(
                        sender = senderNickname,
                        content = content,
                        type = BitchatMessageType.Message,
                        timestamp = Date(packet.timestamp.toLong() * 1000),
                        isPrivate = false,
                        senderPeerID = from
                    )
                    
                    meshDelegate.didReceiveMessage(message)
                }
            }
            else -> {
                // Other packet types are handled internally by the mesh service
                Log.d(TAG, "Received non-message packet type ${packet.type} from $from")
            }
        }
    }
}