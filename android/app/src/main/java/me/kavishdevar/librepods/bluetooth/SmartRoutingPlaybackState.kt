/* Adapted from beidaoc/librepods, GPL-3.0-or-later. */
package me.kavishdevar.librepods.bluetooth

internal data class SmartRoutingPlaybackState(
    val hostStreaming: Boolean?,
    val otherDeviceAudioCategory: Int?,
    val playingAppActive: Boolean?
) {
    val hasRemotePlaybackIntent: Boolean?
        get() = when {
            hostStreaming == true -> true
            // 100 accompanies the idle NA/NO announcement. Do not treat every nonzero
            // category (or an unknown future category) as permission to steal a route.
            otherDeviceAudioCategory == 0 || otherDeviceAudioCategory == 100 -> false
            otherDeviceAudioCategory == 301 || otherDeviceAudioCategory == 501 -> true
            playingAppActive != null -> playingAppActive
            hostStreaming != null -> false
            else -> null
        }
}

internal fun parseSmartRoutingPlaybackState(message: SmartRoutingMessage): SmartRoutingPlaybackState {
    val hostStreaming = when (message.string("hostStreamingState")?.uppercase()) {
        "YES" -> true
        "NO" -> false
        else -> null
    }
    val audioCategory = message.number("otherDeviceAudioCategory")
        ?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
    val playingAppActive = message.string("playingApp")
        ?.let { value -> value.isNotBlank() && !value.equals("NA", ignoreCase = true) }
    return SmartRoutingPlaybackState(hostStreaming, audioCategory, playingAppActive)
}

