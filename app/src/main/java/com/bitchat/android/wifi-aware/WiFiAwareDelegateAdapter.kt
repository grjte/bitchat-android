package com.bitchat.android.`wifi-aware`

import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.model.BitchatMessage

/**
 * Adapts WiFiAwareDelegate callbacks to BluetoothMeshDelegate interface
 * so Wi-Fi Aware messages can be handled by the existing MeshDelegateHandler
 */
class WiFiAwareDelegateAdapter(
    private val meshDelegate: BluetoothMeshDelegate
) : WiFiAwareDelegate {
    
    override fun didReceiveMessage(message: BitchatMessage) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didUpdatePeerList(peers)
    }
    
    override fun didReceiveReadReceipt(messageID: String, from: String) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didReceiveReadReceipt(messageID, from)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, from: String) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didReceiveDeliveryAck(messageID, from)
    }
}