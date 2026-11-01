package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class OpackTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun independentIntegerAndReferenceVectors() {
        assertEquals(100L, Opack.decode(hex("3064")))
        assertEquals(301L, Opack.decode(hex("312d01")))
        assertEquals(501L, Opack.decode(hex("31f501")))
        assertEquals(4294967295L, Opack.decode(hex("32ffffffff")))
        assertEquals(mapOf("a" to 301L, "b" to 301L), Opack.decode(hex("e24161312d014162a1")))
        assertArrayEquals(hex("43594553"), Opack.encode("YES"))
        assertArrayEquals(hex("424e4f"), Opack.encode("NO"))
    }

    @Test fun deidentifiedCapturedMalformedMetadataIsRejected() {
        // 2026-10-25 iOS relay capture, MAC replaced. Missing btName tag and trailing zeros.
        val captured = hex("e54a506c6179696e6741707056636f6d2e676f6f676c652e696f732e796f757475626552486f737453747265616d696e675374617465424e4f496274416464726573735130313a30323a30333a30343a30353a303662744e616d6547416e64726f6964586f74686572446576696365417564696f43617465676f7279312d010000")
        assertThrows(IllegalArgumentException::class.java) { Opack.decode(captured) }
        // Repair captured bytes independently of the production encoder.
        assertEquals('b'.code.toByte(), captured[85])
        val repaired = captured.copyOfRange(0, 85) + byteArrayOf(0x46) + captured.copyOfRange(85, captured.size - 2)
        val dictionary = Opack.decode(repaired) as Map<*, *>
        assertEquals("Android", dictionary["btName"])
        assertEquals(301L, dictionary["otherDeviceAudioCategory"])
    }

    @Test fun utf8LengthsAndTerminatedContainers() {
        val text = "耳机".repeat(15)
        assertEquals(text, Opack.decode(Opack.encode(text)))
        val fields = (0..15).associate { "key$it" to it.toLong() }
        assertEquals(fields, Opack.decode(Opack.encode(fields)))
        val data = ByteArray(256) { it.toByte() }
        assertArrayEquals(data, Opack.decode(Opack.encode(data)) as ByteArray)
    }

    @Test fun malformedDataIsBoundedAndMustBeConsumedCompletely() {
        for (bytes in listOf("", "62ffff", "a0", "e14161", "ef", "df", "0301", "0100", "41ff")) {
            assertThrows(IllegalArgumentException::class.java) { Opack.decode(hex(bytes)) }
        }
        assertThrows(IllegalArgumentException::class.java) { Opack.decode(ByteArray(999)) }
        assertThrows(IllegalArgumentException::class.java) { Opack.decode(ByteArray(26) { 0xd1.toByte() } + byteArrayOf(1)) }
    }
}
