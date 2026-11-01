/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.bluetooth

import android.util.Log
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Manager class for Apple Accessory Communication Protocol (AACP)
 * This class is responsible for handling the L2CAP socket management,
 * constructing and parsing packets for communication with AirPods.
 */
class AACPManager {
    private val TAG = "AACPManager[${System.identityHashCode(this)}]"
    companion object {
        @Suppress("unused")
        object Opcodes {
            const val SET_FEATURE_FLAGS: Byte = 0x4D
            const val REQUEST_NOTIFICATIONS: Byte = 0x0F
            const val BATTERY_INFO: Byte = 0x04
            const val CONTROL_COMMAND: Byte = 0x09
            const val EAR_DETECTION: Byte = 0x06
            const val CONVERSATION_AWARENESS: Byte = 0x4B
            const val INFORMATION: Byte = 0x1D
            const val RENAME: Byte = 0x1A
            const val HEADTRACKING: Byte = 0x17
            const val PROXIMITY_KEYS_REQ: Byte = 0x30
            const val PROXIMITY_KEYS_RSP: Byte = 0x31
            const val STEM_PRESS: Byte = 0x19
            const val HEADPHONE_ACCOMMODATION: Byte = 0x53
            const val CONNECTED_DEVICES: Byte = 0x2E // TiPi 1
            const val AUDIO_SOURCE: Byte = 0x0E // TiPi 2
            const val SMART_ROUTING: Byte = 0x10
            const val TIPI_3: Byte = 0x0C // Don't know this one
            const val SMART_ROUTING_RESP: Byte = 0x11
            const val SEND_CONNECTED_MAC: Byte = 0x14
            const val AUDIO_SOURCE_2: Byte = 0x0C // seems redundant?
            const val CUSTOM_EQ: Byte = 0x63
        }

        private val HEADER_BYTES = byteArrayOf(0x04, 0x00, 0x04, 0x00)

        data class ControlCommandStatus(
            val identifier: ControlCommandIdentifiers, val value: ByteArray
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ControlCommandStatus

                if (identifier != other.identifier) return false
                if (!value.contentEquals(other.value)) return false

                return true
            }

            override fun hashCode(): Int {
                var result: Int = identifier.hashCode()
                result = 31 * result + value.contentHashCode()
                return result
            }
        }

        //        @Suppress("unused")
        enum class ControlCommandIdentifiers(val value: Byte) {
            MIC_MODE(0x01), BUTTON_SEND_MODE(0x05), VOICE_TRIGGER(0x12), SINGLE_CLICK_MODE(0x14), DOUBLE_CLICK_MODE(
                0x15
            ),
            CLICK_HOLD_MODE(0x16), DOUBLE_CLICK_INTERVAL(0x17), CLICK_HOLD_INTERVAL(0x18), LISTENING_MODE_CONFIGS(
                0x1A
            ),
            ONE_BUD_ANC_MODE(0x1B), CROWN_ROTATION_DIRECTION(0x1C), LISTENING_MODE(0x0D), AUTO_ANSWER_MODE(
                0x1E
            ),
            CHIME_VOLUME(0x1F), VOLUME_SWIPE_INTERVAL(0x23), CALL_MANAGEMENT_CONFIG(0x24), VOLUME_SWIPE_MODE(
                0x25
            ),
            ADAPTIVE_VOLUME_CONFIG(0x26), SOFTWARE_MUTE_CONFIG(0x27), CONVERSATION_DETECT_CONFIG(
                0x28
            ),
            SSL(0x29), HEARING_AID(0x2C), AUTO_ANC_STRENGTH(0x2E), HPS_GAIN_SWIPE(0x2F), HRM_STATE(
                0x30
            ),
            IN_CASE_TONE_CONFIG(0x31), SIRI_MULTITONE_CONFIG(0x32), HEARING_ASSIST_CONFIG(0x33), ALLOW_OFF_OPTION(
                0x34
            ),
            STEM_CONFIG(0x39), SLEEP_DETECTION_CONFIG(0x35), ALLOW_AUTO_CONNECT(0x36), // not sure what this does, AUTOMATIC_CONNECTION is the only one used, but this is newer... so ¯\_(ツ)_/¯
            EAR_DETECTION_CONFIG(0x0A), AUTOMATIC_CONNECTION_CONFIG(0x20), OWNS_CONNECTION(0x06), PPE_TOGGLE_CONFIG(
                0x37
            ),
            PPE_CAP_LEVEL_CONFIG(0x38),
            DYNAMIC_END_OF_CHARGE(0x3B);

            companion object {
                fun fromByte(byte: Byte): ControlCommandIdentifiers? =
                    entries.find { it.value == byte }
            }
        }

        enum class ProximityKeyType(val value: Byte) {
            IRK(0x01), ENC_KEY(0x04);

            companion object {
                fun fromByte(byte: Byte): ProximityKeyType = entries.find { it.value == byte }
                    ?: throw IllegalArgumentException("Unknown ProximityKeyType: $byte")
            }
        }

        enum class StemPressType(val value: Byte) {
            SINGLE_PRESS(0x05), DOUBLE_PRESS(0x06), TRIPLE_PRESS(0x07), LONG_PRESS(0x08);

            companion object {
                fun fromByte(byte: Byte): StemPressType? = entries.find { it.value == byte }
            }
        }

        enum class StemPressBudType(val value: Byte) {
            LEFT(0x01), RIGHT(0x02);

            companion object {
                fun fromByte(byte: Byte): StemPressBudType? = entries.find { it.value == byte }
            }
        }

        enum class AudioSourceType(val value: Byte) {
            NONE(0x00), CALL(0x01), MEDIA(0x02);

            companion object {
                fun fromByte(byte: Byte): AudioSourceType? = entries.find { it.value == byte }
            }
        }

        data class AudioSource(
            val mac: String, val type: AudioSourceType
        )

        data class ConnectedDevice(
            val mac: String, val info1: Byte, val info2: Byte, var type: String?
        ) {
            // TiPi tables also retain disconnected devices. iOS names these
            // states Disconnected, Connecting, Connected, and Disconnecting.
            val connectionStatus: Int get() = info1.toInt() and 0xff
            val stateFlags: Int get() = info2.toInt() and 0xff
            val isConnected: Boolean get() = connectionStatus == 2
            val isConnectingOrConnected: Boolean get() = connectionStatus in 1..2
        }

        data class AirPodsInformation(
            val name: String,
            val modelNumber: String,
            val manufacturer: String,
            val serialNumber: String,
            val version1: String,
            val version2: String,
            val hardwareRevision: String,
            val updaterIdentifier: String,
            val leftSerialNumber: String,
            val rightSerialNumber: String,
            val version3: String
        )
    }

    var controlCommandStatusList: MutableList<ControlCommandStatus> =
        mutableListOf<ControlCommandStatus>()
    var controlCommandListeners: MutableMap<ControlCommandIdentifiers, MutableList<ControlCommandListener>> =
        mutableMapOf()

    var owns: Boolean = false
        private set

    var oldConnectedDevices: List<ConnectedDevice> = listOf()
        private set

    var connectedDevices: List<ConnectedDevice> = listOf()
        private set

    var audioSource: AudioSource? = null
        private set

    var fallbackOtherDeviceMacs: List<String> = listOf()

    var eqData = FloatArray(8)
        private set

    var eqOnPhone: Boolean = false
        private set

    var eqOnMedia: Boolean = false
        private set

    var customEq: CustomEq = CustomEq(state = 1, low = 50, mid = 50, high = 50)
        private set

    var customEqCallback: ((CustomEq) -> Unit)? = null

    fun getControlCommandStatus(identifier: ControlCommandIdentifiers): ControlCommandStatus? {
        return controlCommandStatusList.find { it.identifier == identifier }
    }

    private fun setControlCommandStatusValue(
        identifier: ControlCommandIdentifiers,
        value: ByteArray,
        confirmedByAirPods: Boolean = false
    ) {
        // Sending an ownership request is not confirmation that the audio route moved.
        if (identifier == ControlCommandIdentifiers.OWNS_CONNECTION && !confirmedByAirPods) return
        controlCommandStatusList.removeAll { it.identifier == identifier }
        controlCommandStatusList.add(ControlCommandStatus(identifier, value.copyOf()))
        if (identifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
            owns = value.firstOrNull() == 0x01.toByte()
        }
        controlCommandListeners[identifier]?.toList()?.forEach { listener ->
            listener.onControlCommandReceived(ControlCommand(identifier.value, value))
        }
    }

    interface PacketCallback {
        fun onBatteryInfoReceived(batteryInfo: ByteArray)
        fun onEarDetectionReceived(earDetection: ByteArray)
        fun onConversationAwarenessReceived(conversationAwareness: ByteArray)
        fun onControlCommandReceived(controlCommand: ByteArray)
        fun onDeviceInformationReceived(deviceInformation: AirPodsInformation)
        fun onHeadTrackingReceived(headTracking: ByteArray)
        fun onUnknownPacketReceived(packet: ByteArray)
        fun onProximityKeysReceived(proximityKeys: ByteArray)
        fun onStemPressReceived(stemPress: ByteArray)
        fun onAudioSourceReceived(audioSource: ByteArray)
        fun onOwnershipChangeReceived(owns: Boolean)
        fun onConnectedDevicesReceived(connectedDevices: List<ConnectedDevice>)
        fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean)
        fun onRemoteStreamingStateChanged(sender: String, isStreaming: Boolean)
        fun onHijackRequest(sender: String, request: SmartRoutingHijackRequest) {}
        fun onHijackResponse(sender: String, response: SmartRoutingHijackResponse) {}
        fun onShowNearbyUI(sender: String)
        fun onHeadphoneAccommodationReceived(eqData: FloatArray)
        fun onCustomEqReceived(customEq: CustomEq)
        fun onCapabilitiesReceived(capabilities: List<Capability>)
    }

    fun parseStemPressResponse(data: ByteArray): Pair<StemPressType, StemPressBudType> {
        Log.d(TAG, "Parsing Stem Press Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size != 8) {
            throw IllegalArgumentException("Data array too short to parse Stem Press Response")
        }
        if (data[4] != Opcodes.STEM_PRESS) {
            throw IllegalArgumentException("Data array does not start with STEM_PRESS opcode")
        }
        val type = StemPressType.fromByte(data[6])
            ?: throw IllegalArgumentException("Unknown Stem Press Type: ${data[5]}")
        val bud = StemPressBudType.fromByte(data[7])
            ?: throw IllegalArgumentException("Unknown Stem Press Bud Type: ${data[6]}")
        return Pair(type, bud)
    }

    interface ControlCommandListener {
        fun onControlCommandReceived(controlCommand: ControlCommand)
    }

    fun registerControlCommandListener(
        identifier: ControlCommandIdentifiers, callback: ControlCommandListener
    ) {
        controlCommandListeners.getOrPut(identifier) { mutableListOf() }.add(callback)
    }

    fun unregisterControlCommandListener(
        identifier: ControlCommandIdentifiers, callback: ControlCommandListener
    ) {
        controlCommandListeners[identifier]?.remove(callback)
    }

    private var callback: PacketCallback? = null

    fun setPacketCallback(callback: PacketCallback) {
        this.callback = callback
    }

    fun createDataPacket(data: ByteArray): ByteArray {
        return HEADER_BYTES + data
    }

    fun createControlCommandPacket(identifier: Byte, data: ByteArray): ByteArray {
        val opcode = byteArrayOf(Opcodes.CONTROL_COMMAND, 0x00)
        val payload = ByteArray(7)

        System.arraycopy(opcode, 0, payload, 0, 2)
        payload[2] = identifier

        val dataLength = minOf(data.size, 4)
        System.arraycopy(data, 0, payload, 3, dataLength)

        return payload
    }

    fun sendDataPacket(data: ByteArray): Boolean {
        return sendPacket(createDataPacket(data))
    }

    fun sendControlCommand(identifier: Byte, value: ByteArray): Boolean {
        val controlPacket = createControlCommandPacket(identifier, value)
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false, value
        )
        return sendDataPacket(controlPacket)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendControlCommand(identifier: Byte, value: Byte): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false, byteArrayOf(value)
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Boolean): Boolean {
        val controlPacket = createControlCommandPacket(
            identifier, if (value) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            if (value) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
        return sendDataPacket(controlPacket)
    }

    fun sendControlCommand(identifier: Byte, value: Int): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value.toByte()))
        setControlCommandStatusValue(
            ControlCommandIdentifiers.fromByte(identifier) ?: return false,
            byteArrayOf(value.toByte())
        )
        return sendDataPacket(controlPacket)
    }

    fun parseProximityKeysResponse(data: ByteArray): Map<ProximityKeyType, ByteArray> {
        Log.d(
            TAG, "Parsing Proximity Keys Response (${data.size} bytes; keys redacted)"
        )
        if (data.size < 7) {
            throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
        }
        if (data[4] != Opcodes.PROXIMITY_KEYS_RSP) {
            throw IllegalArgumentException("Data array does not start with PROXIMITY_KEYS_RSP opcode")
        }
        val keyCount = data[6].toInt() and 0xff
        val keys = mutableMapOf<ProximityKeyType, ByteArray>()
        var offset = 7
        for (i in 0 until keyCount) {
            Log.d(TAG, "Parsing Proximity Key $i")
            if (offset + 3 >= data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val keyType = data[offset]
            val keyLength = data[offset + 2].toInt() and 0xff
            Log.d(TAG, "Key Type: ${keyType.toString(16)}, Key Length: $keyLength")
            offset += 4
            if (offset + keyLength > data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val key = ByteArray(keyLength)
            System.arraycopy(data, offset, key, 0, keyLength)
            try {
                keys[ProximityKeyType.fromByte(keyType)] = key
            } catch (e: Exception) {
                Log.e(
                    TAG, "Unknown proximity key type: $keyType (value redacted)"
                )
            }
            offset += keyLength
            Log.d(
                TAG, "Parsed Proximity Key: Type: $keyType, Length: $keyLength (value redacted)"
            )
        }
        return keys
    }

    fun sendRequestProximityKeys(type: Byte): Boolean {
        Log.d(TAG, "Requesting proximity keys of type: ${type.toString(16)}")
        return sendDataPacket(createRequestProximityKeysPacket(type))
    }

    fun createRequestProximityKeysPacket(type: Byte): ByteArray {
        val opcode = byteArrayOf(Opcodes.PROXIMITY_KEYS_REQ, 0x00)
        val data = byteArrayOf(type, 0x00)
        return opcode + data
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun receivePacket(packet: ByteArray) {
        if (!packet.toHexString().startsWith("04000400")) {
            Log.w(
                TAG, "Received packet does not start with expected header: ${
                packet.joinToString(" ") {
                    "%02X".format(it)
                }
            }")
            return
        }
        if (packet.size < 6) {
            Log.w(
                TAG, "Received packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}"
            )
            return
        }

        when (val opcode = packet[4]) {
            Opcodes.BATTERY_INFO -> {
                callback?.onBatteryInfoReceived(packet)
            }

            Opcodes.CONTROL_COMMAND -> {
                val controlCommand = try {
                    ControlCommand.fromByteArray(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse control command: ${e.message}")
                    callback?.onUnknownPacketReceived(packet)
                    return
                }
                setControlCommandStatusValue(
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier) ?: return,
                    controlCommand.value,
                    confirmedByAirPods = true
                )
                Log.d(
                    TAG,
                    "Control command received: ${controlCommand.identifier.toHexString()} - ${
                        controlCommand.value.joinToString(" ") { "%02X".format(it) }
                    }")

                val controlCommandListText = try {
                    controlCommandStatusList.joinToString(", ") { it ->
                        "${it.identifier.name} (${it.identifier.value.toHexString()}) - ${
                            it.value.joinToString(
                                " "
                            ) { "%02X".format(it) }
                        }"
                    }
                } catch (e: Exception) {
                    e.message
                }

                Log.d(
                    TAG, "Control command list is now: $controlCommandListText"
                )

                val controlCommandIdentifier =
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier)
                if (controlCommandIdentifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
                    callback?.onOwnershipChangeReceived(owns)
                }

                callback?.onControlCommandReceived(packet)
            }

            Opcodes.EAR_DETECTION -> {
                callback?.onEarDetectionReceived(packet)
            }

            Opcodes.CONVERSATION_AWARENESS -> {
                callback?.onConversationAwarenessReceived(packet)
            }

            Opcodes.HEADTRACKING -> {
                if (packet.size < 70) {
                    Log.w(
                        TAG, "Received HEADTRACKING packet too short: ${
                        packet.joinToString(" ") {
                            "%02X".format(it)
                        }
                    }")
                    return
                }
                callback?.onHeadTrackingReceived(packet)
            }

            Opcodes.PROXIMITY_KEYS_RSP -> {
                callback?.onProximityKeysReceived(packet)
            }

            Opcodes.STEM_PRESS -> {
                callback?.onStemPressReceived(packet)
            }

            Opcodes.AUDIO_SOURCE -> {
                try {
                    val (mac, type) = parseAudioSourceResponse(packet)
                    audioSource = AudioSource(mac, type)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing audio source response: ${e.message}")
                    return
                }
                callback?.onAudioSourceReceived(packet)
            }

            Opcodes.CONNECTED_DEVICES -> {
                val devices = try {
                    parseConnectedDevicesResponse(packet)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Ignoring malformed TiPi table: ${e.message}")
                    return
                }
                oldConnectedDevices = connectedDevices
                connectedDevices = devices
                callback?.onConnectedDevicesReceived(connectedDevices)
            }

            Opcodes.SMART_ROUTING_RESP -> {
                val message = try {
                    SmartRoutingMessage.decode(packet)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Ignoring invalid Smart Routing message: ${e.message}")
                    return
                }
                val sender = message.sender
                val request = message.request
                val response = message.response
                if ((message.flag("audioRoutingHijackRequest") && request == null) ||
                    (message.fields.keys.any { it.equals("audioRoutingHijackAnswer", ignoreCase = true) } && response == null) ||
                    (request != null && response != null)) {
                    Log.w(TAG, "Ignoring malformed Smart Routing negotiation")
                    return
                }
                message.string("btName")?.let { name ->
                    connectedDevices.find { it.mac.equals(sender, ignoreCase = true) }?.type = name
                }
                // Dispatch negotiations separately: a request/answer is not playback telemetry.
                when {
                    request != null -> callback?.onHijackRequest(sender, request)
                    response != null -> callback?.onHijackResponse(sender, response)
                    message.flag("audioRoutingSetOwnershipToFalse") -> callback?.onOwnershipToFalseRequest(
                        sender, message.string("reason") == "ReverseBannerTapped"
                    )
                    else -> parseSmartRoutingPlaybackState(message).hasRemotePlaybackIntent?.let {
                        callback?.onRemoteStreamingStateChanged(sender, it)
                    }
                }
                if (message.flag("SmartRoutingKeyShowNearbyUI")) callback?.onShowNearbyUI(sender)
            }

            Opcodes.HEADPHONE_ACCOMMODATION -> {
                if (packet.size != 140) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet of unexpected size: ${packet.size}, expected 140"
                    )
                    return
                }
                if (packet[6] != 0x84.toByte()) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet with unexpected identifier: ${packet[6].toHexString()}, expected 0x84"
                    )
                    return
                }

                eqOnMedia = (packet[10] == 0x01.toByte())
                eqOnPhone = (packet[11] == 0x01.toByte())
                // there are 4 eqs. i am not sure what those are for, maybe all 4 listening modes, or maybe phone+media left+right, but then there shouldn't be another flag for phone/media visible. just directly the EQ... weird.
                // the EQs are little endian floats
                val eq1 =
                    ByteBuffer.wrap(packet, 12, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 44, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 76, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 108, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                // for now, taking just the first EQ
                eqData = FloatArray(8) { i -> eq1.get(i) }

                Log.d(
                    TAG,
                    "EQ Data set to: ${eqData.toList()}, eqOnPhone: $eqOnPhone, eqOnMedia: $eqOnMedia"
                )

                callback?.onHeadphoneAccommodationReceived(eqData)
            }

            Opcodes.INFORMATION -> {
                Log.d(TAG, "Parsing Information Packet")
                val information = parseInformationPacket(packet)
                callback?.onDeviceInformationReceived(information)
            }

            Opcodes.CUSTOM_EQ -> {
                Log.d(TAG, "Parsing CUSTOM_EQ: ${packet.toHexString()}")
                customEq = parseCustomEqPacket(packet)
                customEqCallback?.invoke(customEq)
                callback?.onCustomEqReceived(customEq)
            }

            else -> {
                Log.d(TAG, "Unhandled opcode received: ${opcode.toHexString()}")
                callback?.onUnknownPacketReceived(packet)
            }
        }
    }

    fun sendNotificationRequest(): Boolean {
        return sendDataPacket(createRequestNotificationPacket())
    }

    fun createRequestNotificationPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.REQUEST_NOTIFICATIONS, 0x00)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        // note to self #1: third byte is 0xfd when ear detection is disabled
        // note to self #2: this can be sent any time, not just at the start of the aacp connection
        return opcode + data
    }

    fun sendSetFeatureFlagsPacket(): Boolean {
        return sendDataPacket(createSetFeatureFlagsPacket())
    }

    fun sendTipiScoreUpdate(score: Int, firmware: String): Boolean {
        val packet = createTipiScoreUpdate(score, firmware) ?: return false
        return sendDataPacket(packet)
    }

    fun createSetFeatureFlagsPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.SET_FEATURE_FLAGS, 0x00)
        val data = byteArrayOf(0xD7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        return opcode + data
    }

    fun createHandshakePacket(): ByteArray {
        return byteArrayOf(
            0x00,
            0x00,
            0x04,
            0x00,
            0x01,
            0x00,
            0x02,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
    }

    fun sendStartHeadTracking(): Boolean {
        return sendDataPacket(createStartHeadTrackingPacket())
    }

    fun createStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x10,
            0x00,
            0x08,
            0xA1.toByte(),
            0x02,
            0x42,
            0x0B,
            0x08,
            0x0E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00,
        )
        return opcode + data
    }

    fun createAlternateStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x73,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00
        )
        return opcode + data
    }

    fun sendStopHeadTracking(): Boolean {
        return sendDataPacket(createStopHeadTrackingPacket())
    }

    fun createStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x11,
            0x00,
            0x08,
            0x7E,
            0x10,
            0x02,
            0x42,
            0x0B,
            0x08,
            0x4E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
        )
        return opcode + data
    }

    fun createAlternateStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x75,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
        )
        return opcode + data
    }

    fun sendRename(name: String): Boolean {
        return sendDataPacket(createRenamePacket(name))
    }

    fun createRenamePacket(name: String): ByteArray {
        val nameBytes = name.toByteArray()
        val size = nameBytes.size
        val packet = ByteArray(5 + size)
        packet[0] = Opcodes.RENAME
        packet[1] = 0x00
        packet[2] = 0x01
        packet[3] = size.toByte()
        packet[4] = 0x00
        System.arraycopy(nameBytes, 0, packet, 5, size)

        return packet
    }

    fun sendMediaInformationNewDevice(selfMacAddress: String, targetMacAddress: String, streamingState: Boolean = false): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }
        Log.d(TAG, "SELFMAC: ${selfMacAddress}, TARGETMAC: $targetMacAddress")
        Log.d(TAG, "Sending Media Information packet to $targetMacAddress")
        return sendDataPacket(
            createMediaInformationNewDevicePacket(
                selfMacAddress,
                targetMacAddress,
                streamingState
            )
        )
    }

    fun createMediaInformationNewDevicePacket(
        selfMacAddress: String,
        targetMacAddress: String,
        streamingState: Boolean = false
    ): ByteArray = createMediaInformationPacket(selfMacAddress, targetMacAddress, streamingState)

    private fun remoteAudioSource(selfMacAddress: String): String? = audioSource
        ?.takeIf { it.type != AudioSourceType.NONE }
        ?.mac?.let(::normalizeSmartRoutingAddress)
        ?.takeUnless { it.equals(selfMacAddress, ignoreCase = true) }
        ?.takeUnless { source ->
            connectedDevices.any { it.mac.equals(source, ignoreCase = true) && !it.isConnected }
        }

    internal fun otherDeviceMacs(selfMacAddress: String): List<String> =
        (listOfNotNull(remoteAudioSource(selfMacAddress)) + connectedDevices.filter { it.isConnected }.map { it.mac })
            .mapNotNull(::normalizeSmartRoutingAddress)
            .filterNot { it.equals(selfMacAddress, ignoreCase = true) }
            .distinct()

    // Only the current audio owner arbitrates a takeover. Historical devices must
    // never become required acknowledgers, nor receive periodic playback reports.
    fun smartRoutingPeers(selfMacAddress: String): List<String> =
        remoteAudioSource(selfMacAddress)?.let { listOf(it) } ?: otherDeviceMacs(selfMacAddress)

    fun sendHijackRequest(targetMacAddress: String, requestId: Long, score: Int = 301): Boolean =
        sendDataPacket(createHijackRequestPacket(targetMacAddress, requestId, score))

    fun createHijackRequestPacket(targetMacAddress: String, requestId: Long, score: Int = 301): ByteArray {
        require(requestId in 0..0xffffffffL && score >= 0)
        return SmartRoutingMessage.createPacket(targetMacAddress, linkedMapOf(
            "audioRoutingHijackRequest" to true,
            "audioRoutingScore" to score,
            "audioRoutingRequestID" to requestId
        ))
    }

    fun sendHijackResponse(targetMacAddress: String, requestId: Long, answer: HijackAnswer): Boolean =
        sendDataPacket(SmartRoutingMessage.createPacket(targetMacAddress, linkedMapOf(
            "audioRoutingRequestID" to requestId,
            "audioRoutingHijackAnswer" to answer.value
        )))

    fun sendMediaInformataion(selfMacAddress: String, streamingState: Boolean = false): Boolean {
        if (!selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) return false
        val targets = otherDeviceMacs(selfMacAddress)
        var success = false
        for (target in targets) {
            success = sendDataPacket(createMediaInformationPacket(selfMacAddress, target, streamingState)) || success
        }
        return success
    }

    fun createMediaInformationPacket(
        selfMacAddress: String, targetMacAddress: String, streamingState: Boolean = true
    ): ByteArray = SmartRoutingMessage.createPacket(targetMacAddress, linkedMapOf(
        // The actual player's identity is unavailable here; never impersonate an iOS app.
        "playingApp" to "NA",
        "hostStreamingState" to if (streamingState) "YES" else "NO",
        "btAddress" to selfMacAddress,
        "btName" to "Android",
        "otherDeviceAudioCategory" to if (streamingState) 301 else 100
    ))

    fun sendSmartRoutingShowUI(selfMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}"))) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(TAG, "Invalid MAC address format, got: selfMacAddress=$selfMacAddress")
            return false
        }

        val targetMac = otherDeviceMacs(selfMacAddress).firstOrNull()
        if (targetMac == null) {
            Log.w(TAG, "Cannot send Smart Routing Show UI packet: No connected device found")
            return false
        }
        Log.d(TAG, "Sending Smart Routing Show UI packet to $targetMac")
        return sendDataPacket(createSmartRoutingShowUIPacket(targetMac))
    }

    fun createSmartRoutingShowUIPacket(targetMacAddress: String): ByteArray =
        SmartRoutingMessage.createPacket(targetMacAddress, mapOf("SmartRoutingKeyShowNearbyUI" to true))

    fun sendHijackReversed(selfMacAddress: String): Boolean {
        var success = false
        for (targetMac in otherDeviceMacs(selfMacAddress)) {
            Log.d(TAG, "Sending Hijack Reversed packet to $targetMac")
            success = sendDataPacket(createHijackReversedPacket(targetMac)) || success
        }
        return success
    }

    fun createHijackReversedPacket(targetMacAddress: String): ByteArray =
        SmartRoutingMessage.createPacket(targetMacAddress, linkedMapOf(
            "audioRoutingSetOwnershipToFalse" to true,
            "audioRoutingShowReverseUI" to true,
            "reason" to "ReverseBannerTapped"
        ))

    fun sendAddTiPiDevice(selfMacAddress: String, targetMacAddress: String): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }
        Log.d(TAG, "Sending Add TiPi Device packet to $targetMacAddress")
        return sendDataPacket(createAddTiPiDevicePacket(selfMacAddress, targetMacAddress))
    }

    fun sendConnectionPriorityList(selfMacAddress: String, peerMacAddress: String? = null): Boolean {
        val packet = createConnectionPriorityListPacket(selfMacAddress, peerMacAddress) ?: return false
        Log.d(TAG, "Sending Connection Priority List (0x14) self=$selfMacAddress, peer=${peerMacAddress ?: "wildcard"}")
        return sendDataPacket(packet)
    }

    fun createConnectionPriorityListPacket(selfMacAddress: String, peerMacAddress: String? = null): ByteArray? {
        val macRegex = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
        if (!selfMacAddress.matches(macRegex)) return null
        val targetPeer = peerMacAddress?.takeIf { it.matches(macRegex) } ?: "FF:FF:FF:FF:FF:FF"
        val selfBytes = selfMacAddress.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
        val peerBytes = targetPeer.split(":").map { it.toInt(16).toByte() }.reversed().toByteArray()
        val opcode = byteArrayOf(Opcodes.SEND_CONNECTED_MAC, 0x00)
        val count = byteArrayOf(0x02)
        return opcode + count + selfBytes + peerBytes
    }

    fun createAddTiPiDevicePacket(selfMacAddress: String, targetMacAddress: String): ByteArray =
        SmartRoutingMessage.createPacket(targetMacAddress, linkedMapOf(
            "idleTime" to 0,
            "newTipi" to true,
            "btAddress" to selfMacAddress,
            "btName" to "Android",
            "nearbyAudioScore" to 6
        ))

    data class ControlCommand(
        val identifier: Byte, val value: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ControlCommand

            if (identifier != other.identifier) return false
            if (!value.contentEquals(other.value)) return false

            return true
        }

        override fun hashCode(): Int {
            var result: Int = identifier.toInt()
            result = 31 * result + value.contentHashCode()
            return result
        }

        companion object {
            fun fromByteArray(data: ByteArray): ControlCommand {
                var offset = 0
                while (data.size - offset >= 4 &&
                    data[offset] == 0x04.toByte() &&
                    data[offset + 1] == 0x00.toByte() &&
                    data[offset + 2] == 0x04.toByte() &&
                    data[offset + 3] == 0x00.toByte()
                ) {
                    offset += 4
                }
                if (data.size - offset < 7) {
                    throw IllegalArgumentException("Too short for ControlCommand")
                }
                if (data[offset] != Opcodes.CONTROL_COMMAND) {
                    throw IllegalArgumentException("Invalid opcode")
                }
                val identifier = data[offset + 2]
                val value = data.copyOfRange(offset + 3, offset + 7)
                val trimmed = value.dropLastWhile { it == 0x00.toByte() }.toByteArray()
                return ControlCommand(identifier, if (trimmed.isEmpty()) byteArrayOf(0x00) else trimmed)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendStemConfigPacket(
        singlePressCustomized: Boolean = false,
        doublePressCustomized: Boolean = false,
        triplePressCustomized: Boolean = false,
        longPressCustomized: Boolean = false
    ): Boolean {
        val value =
            ((if (singlePressCustomized) 0x01 else 0) or (if (doublePressCustomized) 0x02 else 0) or (if (triplePressCustomized) 0x04 else 0) or (if (longPressCustomized) 0x08 else 0)).toByte()
        Log.d(TAG, "Sending Stem Config Packet with value: ${value.toHexString()}")
        return sendControlCommand(
            ControlCommandIdentifiers.STEM_CONFIG.value, value
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun sendPacket(packet: ByteArray): Boolean {
        try {
            Log.d(TAG, "Sending packet: ${AacpPacketLog.format(packet)}")

            if (packet[4] == Opcodes.CONTROL_COMMAND) {
                val controlCommand = try {
                    ControlCommand.fromByteArray(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid control command: ${e.message}")
                    callback?.onUnknownPacketReceived(packet)
                    return false
                }
                Log.d(
                    TAG, "Control command: ${controlCommand.identifier.toHexString()} - ${
                    controlCommand.value.joinToString(" ") { "%02X".format(it) }
                }")
                setControlCommandStatusValue(
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier) ?: return false,
                    controlCommand.value
                )
            }

            val socket = BluetoothConnectionManager.aacpSocket ?: return false

            if (socket.isConnected) {
                socket.outputStream?.write(packet)
                socket.outputStream?.flush()
                return true
            } else {
                Log.d(TAG, "Can't send packet: Socket not initialized or connected")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.message}")
            return false
        }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phone: Byte = 0x02.toByte(), media: Byte = 0x02.toByte()) {
        if (eq.size != 8) throw IllegalArgumentException("EQ must be 8 floats")
        val header = byteArrayOf(
            0x04.toByte(),
            0x00.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            0x53.toByte(),
            0x00.toByte(),
            0x84.toByte(),
            0x00.toByte(),
            0x02.toByte(),
            0x02.toByte(),
            phone,
            media
        )
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (block in 0..3) {
            for (i in 0..7) {
                buffer.putFloat(eq[i])
            }
        }
        val payload = buffer.array()
        val packet = header + payload
        sendPacket(packet)
        this.eqData = eq.copyOf()
        this.eqOnPhone = phone == 0x01.toByte()
        this.eqOnMedia = media == 0x01.toByte()
    }

    fun parseAudioSourceResponse(data: ByteArray): Pair<String, AudioSourceType> {
        Log.d(TAG, "Parsing Audio Source Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size < 13) {
            throw IllegalArgumentException("Data array too short to parse Audio Source Response")
        }
        if (data[4] != Opcodes.AUDIO_SOURCE) {
            throw IllegalArgumentException("Data array does not start with AUDIO_SOURCE opcode")
        }
        val macBytes = data.sliceArray(6..11).reversedArray()
        val mac = macBytes.joinToString(":") { "%02X".format(it) }
        val typeByte = data[12]
        val type = AudioSourceType.fromByte(typeByte)
            ?: throw IllegalArgumentException("Unknown Audio Source Type: $typeByte")
        return Pair(mac, type)
    }

    fun parseConnectedDevicesResponse(data: ByteArray): List<ConnectedDevice> {
        Log.d(
            TAG,
            "Parsing Connected Devices Response: ${data.joinToString(" ") { "%02X".format(it) }}"
        )
        if (data.size < 9) {
            throw IllegalArgumentException("Data array too short to parse Connected Devices Response")
        }
        if (data[4] != Opcodes.CONNECTED_DEVICES) {
            throw IllegalArgumentException("Data array does not start with CONNECTED_DEVICES opcode")
        }
        val deviceCount = data[8].toInt() and 0xff
        require(data.size >= 9 + deviceCount * 8) { "Truncated TiPi device table" }
        val devices = mutableListOf<ConnectedDevice>()

        var offset = 9
        for (i in 0 until deviceCount) {
            val macBytes = data.sliceArray(offset until offset + 6)
            val mac = macBytes.joinToString(":") { "%02X".format(it) }
            val info1 = data[offset + 6]
            val info2 = data[offset + 7]
            val existingDevice = connectedDevices.find { it.mac.equals(mac, ignoreCase = true) }
            devices.add(ConnectedDevice(mac, info1, info2, existingDevice?.type))
            offset += 8
        }

        return devices
    }

    fun sendSomePacketIDontKnowWhatItIs() {
        // 2900 00ff ffff ffff ffff -- enables setting EQ
        sendDataPacket(
            byteArrayOf(
                0x29, 0x00,
                0x00, 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
            )
        )
    }

    fun disconnected() {
        Log.d(TAG, "Disconnected, clearing state")
        controlCommandStatusList.clear()
        controlCommandListeners.clear()
        owns = false
        oldConnectedDevices = listOf()
        connectedDevices = listOf()
        audioSource = null
    }

    fun parseInformationPacket(packet: ByteArray): AirPodsInformation {
        val data = packet.sliceArray(6 until packet.size)

        var index = 0
        while (index < data.size && data[index] != 0x00.toByte()) index++

        val strings = mutableListOf<String>()
        while (index < data.size) {
            // skip 0x00 bytes
            while (index < data.size && data[index] == 0x00.toByte()) index++
            if (index >= data.size) break
            val start = index
            // find next 0x00 byte
            while (index < data.size && data[index] != 0x00.toByte()) index++
            val str = data.sliceArray(start until index).decodeToString()
            strings.add(str)
        }

        strings.removeAt(0) // I'm too lazy to adjust, just removing the first empty string

        return AirPodsInformation(
            name = strings.getOrNull(0) ?: "",
            modelNumber = strings.getOrNull(1) ?: "",
            manufacturer = strings.getOrNull(2) ?: "",
            serialNumber = strings.getOrNull(3) ?: "",
            version1 = strings.getOrNull(4) ?: "",
            version2 = strings.getOrNull(5) ?: "",
            hardwareRevision = strings.getOrNull(6) ?: "",
            updaterIdentifier = strings.getOrNull(7) ?: "",
            leftSerialNumber = strings.getOrNull(8) ?: "",
            rightSerialNumber = strings.getOrNull(9) ?: "",
            version3 = strings.getOrNull(10) ?: "",
        )
    }

    fun sendCustomEqPacket(customEq: CustomEq): Boolean {
        return sendDataPacket(customEq.toPacket())
    }

    fun parseCustomEqPacket(packet: ByteArray): CustomEq {
        val data = packet.sliceArray(6 until packet.size)

        if (data.size < 7) {
            Log.e(TAG, "custom EQ packet length less than 7, returning default")
            return CustomEq(1, 50, 50, 50)
        }

        val lengthLow = data[0].toInt() and 0xFF
        val lengthHigh = data[1].toInt() and 0xFF

        val length = (lengthHigh shl 8) or lengthLow

        if (length != 5) {
            Log.w(TAG, "parseCustomEqPacket: unexpected length ($length). parsing normally")
        }

        val state = data[3].toInt()
        val low = data[4].toInt()
        val mid = data[5].toInt()
        val high = data[6].toInt()

        return CustomEq(
            state,
            low,
            mid,
            high
        )
    }
}
