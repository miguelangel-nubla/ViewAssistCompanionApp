package com.msp1974.vacompanion.wyoming

import com.msp1974.vacompanion.utils.Logger
import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketException

/**
 * Low-level messenger for the Wyoming protocol.
 * Responsible for packet framing, JSON serialization, and raw data transmission.
 */
class WyomingMessenger(
    private val clientId: Int,
    private val reader: DataInputStream,
    private val writer: DataOutputStream,
    private val version: String,
    private val log: Logger = Logger(),
    private val checkRunning: () -> Boolean = { true }
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Sends a Wyoming packet to the peer.
     * Performs JSON framing and optional payload serialization.
     */
    @Synchronized
    fun sendEvent(packet: WyomingPacket) {
        if (packet.type != "ping" && packet.type != "pong" && packet.type != "audio-chunk") {
            log.d("Sending to $clientId: ${packet.toMap()}")
        }
        
        // Ensure session_id is included in the data if present in packet
        val outboundData = if (packet.sessionId != null && packet.data["session_id"] == null) {
            buildJsonObject {
                packet.data.forEach { (k, v) -> put(k, v) }
                put("session_id", packet.sessionId)
            }
        } else {
            packet.data
        }

        val dataBytes = outboundData.toString().toByteArray(Charsets.UTF_8)
        
        val header = buildJsonObject {
            put("type", packet.type)
            put("version", version)
            if (dataBytes.isNotEmpty()) put("data_length", dataBytes.size)
            if (packet.payload.isNotEmpty()) put("payload_length", packet.payload.size)
        }

        val jsonLine = "$header\n".toByteArray(Charsets.UTF_8)

        try {
            writer.write(jsonLine)
            if (dataBytes.isNotEmpty()) writer.write(dataBytes)
            if (packet.payload.isNotEmpty()) writer.write(packet.payload)
            writer.flush()
        } catch (ex: SocketException) {
            log.e("Error sending event: $ex. Likely just a closed socket.")
            throw ex
        } catch (ex: Exception) {
            log.e("Unknown error sending event: $ex")
            throw ex
        }
    }

    /**
     * Reads the next Wyoming packet from the peer.
     * Blocks until a full packet is available or connection is lost.
     * @throws IOException if connection is lost or protocol error occurs.
     */
    fun readEvent(): WyomingPacket? {
        val jsonString = StringBuilder()
        var byte = reader.read()
        
        if (byte == -1) throw java.io.EOFException("Connection closed by peer")

        while (checkRunning() && byte != -1 && byte != '\n'.code) {
            jsonString.append(byte.toChar())
            byte = reader.read()
        }
        
        if (!checkRunning()) return null
        if (byte == -1 && jsonString.isEmpty()) return null
        if (byte == -1) throw java.io.EOFException("Connection lost during header read")

        val headerString = jsonString.toString()
        val header = try {
            json.parseToJsonElement(headerString).jsonObject
        } catch (ex: Exception) {
            throw java.io.IOException("Malformed Wyoming header: $headerString", ex)
        }

        val type = header["type"]?.jsonPrimitive?.content ?: throw java.io.IOException("Missing type in Wyoming header")
        
        val dataLength = header["data_length"]?.jsonPrimitive?.intOrNull ?: 0
        val data = if (dataLength > 0) {
            val dataBytes = ByteArray(dataLength)
            reader.readFully(dataBytes)
            try {
                json.parseToJsonElement(String(dataBytes)).jsonObject
            } catch (ex: Exception) {
                log.e("Failed to parse Wyoming data payload: $ex")
                buildJsonObject {}
            }
        } else {
            header["data"]?.jsonObject ?: buildJsonObject {}
        }

        // Support both session_id and sessionId for compatibility
        val sessionId = data["session_id"]?.jsonPrimitive?.intOrNull 
            ?: data["sessionId"]?.jsonPrimitive?.intOrNull
            ?: header["session_id"]?.jsonPrimitive?.intOrNull

        val packet = WyomingPacket(type, data, sessionId = sessionId)
        val payloadLength = header["payload_length"]?.jsonPrimitive?.intOrNull ?: 0
        if (payloadLength != 0) {
            val payloadBytes = ByteArray(payloadLength)
            reader.readFully(payloadBytes)
            packet.payload = payloadBytes
        }

        if (packet.type != "ping" && packet.type != "pong" && packet.type != "audio-chunk") {
            log.d("Event received - $clientId: ${packet.toMap()}")
        }

        return packet
    }
}
