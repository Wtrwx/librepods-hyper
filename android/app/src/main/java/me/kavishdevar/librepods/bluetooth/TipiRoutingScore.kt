package me.kavishdevar.librepods.bluetooth

/** Firmware gates recovered from iOS 16's _sendTipiScoreUpdate. */
internal fun createTipiScoreUpdate(score: Int, firmware: String): ByteArray? {
    require(score in 0..7)
    val text = firmware.trim()
    // Recent firmware also reports numeric bundle versions (e.g. 81.x.y).
    // iOS's NSString numeric comparison ranks these above the 5Axxx gates.
    val generation = text.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
    val version = if (generation == 5) Regex("^5([A-Za-z])(\\d+)$").matchEntire(text) else null
    if (generation == 5 && version == null) return null
    val branch = version?.groupValues?.get(1)?.uppercase() ?: ""
    val build = version?.groupValues?.get(2)?.toIntOrNull() ?: 0
    fun newerThan(buildNumber: Int): Boolean = when {
        generation != 5 -> generation > 5
        branch != "A" -> branch > "A"
        else -> build > buildNumber
    }
    if (!newerThan(274)) return null
    val payload = if (newerThan(320)) {
        byteArrayOf(0x02, 0x00, 0x02, score.toByte())
    } else {
        byteArrayOf(0x01, 0x00, score.toByte())
    }
    // Direct headset information, distinct from the 0x10 host-to-host relay.
    return byteArrayOf(0x44, 0x00, payload.size.toByte(), 0x00) + payload
}
