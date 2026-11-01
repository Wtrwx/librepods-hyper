/* LibrePods contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.bluetooth

import java.util.Locale

private val ROUTING_ADDRESS = Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")

internal fun normalizeSmartRoutingAddress(address: String?): String? {
    val normalized = address?.trim()?.uppercase(Locale.ROOT) ?: return null
    return normalized.takeIf {
        ROUTING_ADDRESS.matches(it) && it != "00:00:00:00:00:00" && it != "FF:FF:FF:FF:FF:FF"
    }
}

enum class HijackAnswer(val value: Int) {
    ACCEPTED(1), AMBIGUOUS(2), REJECTED(3), BACKOFF(4);
    companion object {
        fun fromValue(value: Long?): HijackAnswer? = entries.find { it.value.toLong() == value }
    }
}

/** iOS uses an unsigned 32-bit client ID, echoed unchanged by the answering host. */
data class SmartRoutingHijackRequest(val requestId: Long, val score: Int)
data class SmartRoutingHijackResponse(val requestId: Long, val answer: HijackAnswer, val backoffTicks: Long?)

internal data class SmartRoutingMessage(val sender: String, val fields: Map<String, Any?>) {
    private val normalized = fields.entries.associate { it.key.lowercase(Locale.ROOT) to it.value }
    fun string(key: String): String? = normalized[key.lowercase(Locale.ROOT)] as? String
    fun number(key: String): Long? = normalized[key.lowercase(Locale.ROOT)] as? Long
    fun flag(key: String): Boolean = normalized[key.lowercase(Locale.ROOT)] == true
    val request: SmartRoutingHijackRequest?
        get() {
            if (!flag("audioRoutingHijackRequest")) return null
            val id = number("audioRoutingRequestID")?.takeIf { it in 0..0xffffffffL } ?: return null
            val score = number("audioRoutingScore")?.takeIf { it in 0..Int.MAX_VALUE.toLong() } ?: return null
            return SmartRoutingHijackRequest(id, score.toInt())
        }
    val response: SmartRoutingHijackResponse?
        get() {
            val id = number("audioRoutingRequestID")?.takeIf { it in 0..0xffffffffL } ?: return null
            val answer = HijackAnswer.fromValue(number("audioRoutingHijackAnswer")) ?: return null
            return SmartRoutingHijackResponse(id, answer, number("hijackBackoffTicks"))
        }

    companion object {
        // AACP's outer four-byte header precedes the native 0x11 relay envelope.
        fun decode(packet: ByteArray): SmartRoutingMessage {
            require(packet.size >= 16 && packet[4] == 0x11.toByte() && packet[5] == 0.toByte())
            require(packet.copyOfRange(0, 4).contentEquals(byteArrayOf(4, 0, 4, 0)))
            val length = (packet[12].toInt() and 0xff) or ((packet[13].toInt() and 0xff) shl 8)
            require(length == packet.size - 14 && packet[14] == 1.toByte()) { "Invalid routing relay envelope" }
            val sender = packet.copyOfRange(6, 12).reversedArray().joinToString(":") { "%02X".format(it) }
            val decoded = Opack.decode(packet.copyOfRange(15, packet.size))
            require(decoded is Map<*, *> && decoded.keys.all { it is String }) { "Routing payload is not a dictionary" }
            require(decoded.keys.map { (it as String).lowercase(Locale.ROOT) }.distinct().size == decoded.size) {
                "Conflicting routing dictionary keys"
            }
            @Suppress("UNCHECKED_CAST")
            return SmartRoutingMessage(sender, decoded as Map<String, Any?>)
        }

        fun createPacket(targetMacAddress: String, fields: Map<String, Any?>): ByteArray {
            require(MAC.matches(targetMacAddress)) { "Invalid routing destination" }
            val address = targetMacAddress.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
            val body = byteArrayOf(1) + Opack.encode(fields)
            return byteArrayOf(0x10, 0) + address +
                byteArrayOf(body.size.toByte(), (body.size ushr 8).toByte()) + body
        }

        private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    }
}
