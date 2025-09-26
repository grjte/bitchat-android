package com.bitchat.android.`wifi-aware`

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.BinaryProtocol
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Handles reading BitchatPackets from a Wi-Fi Aware socket connection
 */
class WiFiAwareSocketReader(
    private val inputStream: InputStream,
    private val connectionScope: CoroutineScope,
    private val onPacketReceived: (BitchatPacket, String) -> Unit,
    private val onConnectionClosed: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "WiFiAwareSocketReader"
    }
    
    private var readerJob: Job? = null
    private var isActive = false
    private val dataBuffer = mutableListOf<Byte>()
    private var peerID: String? = null
    
    fun setPeerID(connectedPeer: String) {
        peerID = connectedPeer
    }
    
    /**
     * Start reading from socket with proper packet boundary detection
     */
    fun start() {
        if (isActive) return
        
        isActive = true
        readerJob = connectionScope.launch {
            try {
                val bufferedInputStream = BufferedInputStream(inputStream)
                
                Log.d(TAG, "Started socket reader for peer: $peerID")
                
                while (isActive) {
                    try {
                        val available = bufferedInputStream.available()
                        if (available > 0) {
                            val data = ByteArray(available)
                            val bytesRead = bufferedInputStream.read(data)
                            if (bytesRead != -1) {
                                processIncomingBytes(data, bytesRead)
                            }
                        } else {
                            // No data available, wait a bit
                            delay(10)
                        }
                    } catch (e: IOException) {
                        if (isActive) {
                            Log.w(TAG, "Error reading from socket for $peerID: ${e.message}")
                        }
                        break
                    }
                }
                
                Log.d(TAG, "Socket reader stopped for peer: $peerID")
                
            } catch (e: Exception) {
                Log.e(TAG, "Socket reader error for $peerID: ${e.message}")
            } finally {
                // Notify that connection is closed
                peerID?.let {
                    onConnectionClosed?.invoke(it)
                }
            }
        }
    }
    
    /**
     * Stop the socket reader
     */
    fun stop() {
        isActive = false
        readerJob?.cancel()
        readerJob = null
    }
    
    /**
     * Process incoming bytes by accumulating data and trying to parse complete packets
     */
    private fun processIncomingBytes(data: ByteArray, length: Int) {
        // Add new data to buffer
        for (i in 0 until length) {
            dataBuffer.add(data[i])
        }
        
        // Process buffer contents - keep trying to extract messages until we can't find any more
        while (dataBuffer.isNotEmpty()) {
            val processed = tryExtractMessage()
            if (!processed) {
                break // No complete message found, wait for more data
            }
        }
    }
    
    /**
     * Try to extract a complete message from the buffer
     * @return true if a message was extracted and processed, false if no complete message found
     */
    private fun tryExtractMessage(): Boolean {
        if (dataBuffer.isEmpty()) return false
        
        // Convert current buffer to byte array for processing
        val bufferData = dataBuffer.toByteArray()
        
        // Try to parse as BitchatPacket
        try {
            val packet = BinaryProtocol.decode(bufferData)
            if (packet != null) {
                // Successfully parsed a packet - now figure out how many bytes it consumed
                val packetBytes = BinaryProtocol.encode(packet)
                if (packetBytes != null) {
                    val consumedBytes = packetBytes.size
                    val senderID = packet.senderID.take(8).toByteArray().joinToString("") { "%02x".format(it) }
                    
                    Log.d(
                        TAG,
                        "Successfully parsed packet type ${packet.type} from $peerID, sent by $senderID, consumed $consumedBytes bytes"
                    )
                    
                    onPacketReceived(packet, senderID)
                    
                    // Remove consumed bytes from buffer
                    repeat(consumedBytes) { dataBuffer.removeAt(0) }
                    return true
                }
            }
        } catch (e: Exception) {
            // This is expected when buffer doesn't contain a complete packet yet
            Log.v(TAG, "Buffer doesn't contain complete packet yet: ${e.message}")
        }
        
        // No complete message found
        return false
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return "SocketReader[$peerID]: active=$isActive, bufferSize=${dataBuffer.size}"
    }
}