package me.kavishdevar.librepods.bluetooth

import org.junit.Assert.*
import org.junit.Test

class AacpPacketLogTest {
    @Test fun keyResponsesAreRedactedBeforeLoggingAndOldHistoryIsScrubbed() {
        val secret = byteArrayOf(4, 0, 4, 0, 0x31, 0, 1, 0x12, 0x34, 0x56)
        val formatted = AacpPacketLog.format(secret)
        assertTrue(formatted.contains("redacted"))
        assertFalse(formatted.contains("12 34 56"))
        assertEquals("AirPods: $formatted", AacpPacketLog.sanitizeStored("AirPods: 04 00 04 00 31 00 01 12 34 56"))
        assertEquals("04 00 04 00 09 00", AacpPacketLog.format(byteArrayOf(4, 0, 4, 0, 9, 0)))
    }
}
