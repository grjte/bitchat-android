package com.bitchat.android.`wifi-aware`

import com.bitchat.android.mesh.BluetoothMeshDelegate

/**
 * Adapts WiFiAwareDelegate callbacks to BluetoothMeshDelegate interface
 * so Wi-Fi Aware discovery events can be handled by the existing MeshDelegateHandler
 * 
 * Currently only handles peer discovery updates. Message handling will be
 * added when Wi-Fi Aware connections are established.
 */
class WiFiAwareDelegateAdapter(
    private val meshDelegate: BluetoothMeshDelegate
) : WiFiAwareDelegate {
    
    override fun didUpdatePeerList(peers: List<String>) {
        // Forward to mesh delegate for unified handling
        meshDelegate.didUpdatePeerList(peers)
    }
}