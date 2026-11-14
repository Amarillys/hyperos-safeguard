package com.example.thermalguard.monitor

import org.junit.Assert.*
import org.junit.Test

class PowerActivityParserTest {
    private fun bytes(vararg values: Int) = values.map { it.toByte() }.toByteArray()

    @Test
    fun readsDefaultDisplayAndIgnoresOtherGroupsAndUnknownFields() {
        // Default group ID is omitted; timestamps are 1000 and 1200. Group 2 has 1500.
        val data = bytes(0xa2, 1, 6, 0x20, 0xe8, 7, 0x28, 0xb0, 9,
            0xa2, 1, 5, 0x20, 0xdc, 0x0b, 0x30, 2, 0x0a, 3, 1, 2, 3)
        assertEquals(1200L, PowerActivityParser.lastActivityUptimeMs(data))
    }

    @Test
    fun missingMalformedOrDeniedDumpReturnsUnavailable() {
        for (data in listOf(
            byteArrayOf(), "Permission Denial".toByteArray(),
            bytes(0xa2, 1, 5, 0x20, 0xdc, 0x0b, 0x30, 2),
            bytes(0xa2, 1, 100, 0x20, 1),
            bytes(0xa2, 1, 2, 0x20, 0x80),
            bytes(0xa2, 1, 3, 0x20, 0x80, 0x80)
        )) assertNull(PowerActivityParser.lastActivityUptimeMs(data))
    }
}
