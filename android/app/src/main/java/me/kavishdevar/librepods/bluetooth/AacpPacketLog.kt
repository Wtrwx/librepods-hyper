/* LibrePods contributors, GPL-3.0-or-later. */
package me.kavishdevar.librepods.bluetooth

/** Shared by logcat and the persisted/exported packet history. */
internal object AacpPacketLog {
    fun format(packet: ByteArray): String = if (
        packet.size >= 5 && packet[0] == 4.toByte() && packet[1] == 0.toByte() &&
        packet[2] == 4.toByte() && packet[3] == 0.toByte() && packet[4] == 0x31.toByte()
    ) "04 00 04 00 31 [proximity keys redacted]"
    else packet.joinToString(" ") { "%02X".format(it) }

    fun sanitizeStored(entry: String): String {
        val marker = "04 00 04 00 31"
        val start = entry.indexOf(marker, ignoreCase = true)
        return if (start >= 0) entry.take(start) + "$marker [proximity keys redacted]" else entry
    }
}
