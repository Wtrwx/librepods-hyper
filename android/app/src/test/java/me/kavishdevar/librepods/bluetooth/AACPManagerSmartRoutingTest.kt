package me.kavishdevar.librepods.bluetooth

import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AACPManagerSmartRoutingTest {
    private val mac = "01:02:03:04:05:06"
    private fun incoming(fields: Map<String, Any?>): ByteArray = AACPManager().createDataPacket(
        SmartRoutingMessage.createPacket(mac, fields).also { it[0] = 0x11 })
    private fun state(fields: Map<String, Any?>) = parseSmartRoutingPlaybackState(SmartRoutingMessage.decode(incoming(fields)))

    @Test fun fullIntegerCategoriesAreDecodedRatherThanTheirTypeTags() {
        for (category in listOf(0, 100, 301, 501, 12345)) {
            assertEquals(category, state(mapOf("otherDeviceAudioCategory" to category)).otherDeviceAudioCategory)
        }
        assertEquals(true, state(mapOf("otherDeviceAudioCategory" to 301, "hostStreamingState" to "NO")).hasRemotePlaybackIntent)
        assertEquals(false, state(mapOf("otherDeviceAudioCategory" to 100, "hostStreamingState" to "NO")).hasRemotePlaybackIntent)
        assertNull(state(mapOf("otherDeviceAudioCategory" to 12345)).hasRemotePlaybackIntent)
    }

    @Test fun idleAnnouncementsDoNotYieldAndExplicitStreamingWins() {
        assertEquals(false, state(mapOf("playingApp" to "NA", "hostStreamingState" to "NO", "otherDeviceAudioCategory" to 100)).hasRemotePlaybackIntent)
        assertEquals(false, state(mapOf("playingApp" to "com.example.music", "otherDeviceAudioCategory" to 0)).hasRemotePlaybackIntent)
        assertEquals(true, state(mapOf("hostStreamingState" to "YES", "otherDeviceAudioCategory" to 0)).hasRemotePlaybackIntent)
    }

    @Test fun dictionaryOrderAndUnrelatedYesStringsDoNotChangePlaybackState() {
        val fields = linkedMapOf<String, Any?>("btName" to "YES iPhone", "hostStreamingState" to "NO", "playingApp" to "NA")
        assertEquals(false, state(fields).hasRemotePlaybackIntent)
        assertEquals(state(fields), state(fields.entries.reversed().associate { it.toPair() }))
        assertEquals(true, state(mapOf("PlayingApp" to "com.example.music", "HostStreamingState" to "YES")).hasRemotePlaybackIntent)
    }

    @Test fun bothStreamingLengthsAndAllRelayBuildersAreValid() {
        val manager = AACPManager()
        val packets = listOf(
            manager.createMediaInformationPacket(mac, mac, true),
            manager.createMediaInformationPacket(mac, mac, false),
            manager.createMediaInformationNewDevicePacket(mac, mac),
            manager.createHijackRequestPacket(mac, 0xffffffffL),
            manager.createSmartRoutingShowUIPacket(mac),
            manager.createHijackReversedPacket(mac),
            manager.createAddTiPiDevicePacket(mac, mac))
        val decoded = packets.map { SmartRoutingMessage.decode(manager.createDataPacket(it.copyOf().also { it[0] = 0x11 })) }
        assertTrue(decoded.all { it.sender == mac && it.fields.isNotEmpty() })
        assertEquals("YES", decoded[0].string("hostStreamingState"))
        assertEquals("NO", decoded[1].string("hostStreamingState"))
        assertTrue(decoded[4].flag("SmartRoutingKeyShowNearbyUI"))
        assertFalse(decoded[4].flag("audioRoutingSetOwnershipToFalse"))
    }

    @Test fun negotiationsAndStringValuesDoNotTriggerLegacyRelinquish() {
        val events = mutableListOf<String>()
        val manager = AACPManager()
        manager.setPacketCallback(Proxy.newProxyInstance(
            AACPManager.PacketCallback::class.java.classLoader, arrayOf(AACPManager.PacketCallback::class.java)
        ) { _, method, args ->
            events += method.name
            if (method.name == "onHijackRequest") assertEquals(4294967295L, (args!![1] as SmartRoutingHijackRequest).requestId)
            null
        } as AACPManager.PacketCallback)
        manager.receivePacket(incoming(mapOf("audioRoutingHijackRequest" to true, "audioRoutingScore" to 301, "audioRoutingRequestID" to 4294967295L)))
        manager.receivePacket(incoming(mapOf("audioRoutingHijackAnswer" to 1, "audioRoutingRequestID" to 4294967295L)))
        manager.receivePacket(incoming(mapOf("btName" to "audioRoutingSetOwnershipToFalse ShowNearbyUI")))
        manager.receivePacket(incoming(mapOf("audioRoutingSetOwnershipToFalse" to false)))
        manager.receivePacket(incoming(mapOf("audioRoutingHijackRequest" to true, "hostStreamingState" to "YES")))
        manager.receivePacket(incoming(mapOf("audioRoutingHijackAnswer" to 99, "audioRoutingRequestID" to 1, "hostStreamingState" to "YES")))
        assertEquals(listOf("onHijackRequest", "onHijackResponse"), events)
    }

    @Test fun truncatedAndMismatchedEnvelopesNeverInvokeRoutingCallbacks() {
        val manager = AACPManager()
        var callbacks = 0
        manager.setPacketCallback(Proxy.newProxyInstance(
            AACPManager.PacketCallback::class.java.classLoader, arrayOf(AACPManager.PacketCallback::class.java)
        ) { _, _, _ -> callbacks++; null } as AACPManager.PacketCallback)
        val valid = incoming(mapOf("hostStreamingState" to "YES"))
        for (size in 6 until valid.size) manager.receivePacket(valid.copyOf(size))
        manager.receivePacket(valid + byteArrayOf(0))
        manager.receivePacket(valid.copyOf().also { it[14] = 2 })
        assertEquals(0, callbacks)
        manager.receivePacket(valid)
        assertEquals(1, callbacks)
    }

    @Test fun connectionPriorityListPacketFormatMatchesAppleHardwareContract() {
        val manager = AACPManager()
        val selfMac = "A4:C7:88:6C:C4:73"
        val peerMac = "70:3C:69:45:A1:AB"
        val p1 = manager.createConnectionPriorityListPacket(selfMac, peerMac)
        assertNotNull(p1)
        assertEquals(15, p1!!.size)
        // Opcode 0x14, 0x00, count 0x02
        assertEquals(0x14.toByte(), p1[0])
        assertEquals(0x00.toByte(), p1[1])
        assertEquals(0x02.toByte(), p1[2])
        // self MAC reversed: 73 C4 6C 88 C7 A4
        val expectedSelfRev = byteArrayOf(0x73.toByte(), 0xC4.toByte(), 0x6C.toByte(), 0x88.toByte(), 0xC7.toByte(), 0xA4.toByte())
        assertArrayEquals(expectedSelfRev, p1.copyOfRange(3, 9))
        // peer MAC reversed: AB A1 45 69 3C 70
        val expectedPeerRev = byteArrayOf(0xAB.toByte(), 0xA1.toByte(), 0x45.toByte(), 0x69.toByte(), 0x3C.toByte(), 0x70.toByte())
        assertArrayEquals(expectedPeerRev, p1.copyOfRange(9, 15))

        // Wildcard peer fallback
        val p2 = manager.createConnectionPriorityListPacket(selfMac, null)
        assertNotNull(p2)
        assertEquals(15, p2!!.size)
        assertArrayEquals(expectedSelfRev, p2.copyOfRange(3, 9))
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, p2.copyOfRange(9, 15))
    }
}
