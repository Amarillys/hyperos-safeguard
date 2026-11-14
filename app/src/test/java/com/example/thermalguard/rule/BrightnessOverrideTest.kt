package com.example.thermalguard.rule

import org.junit.Assert.*
import org.junit.Test

class BrightnessOverrideTest {
    private class Store : BrightnessLeaseStore {
        var value: BrightnessLease? = null
        override fun load() = value
        override fun save(lease: BrightnessLease) { value = lease }
        override fun clear() { value = null }
    }

    private class Port(var value: BrightnessValues) : BrightnessPort {
        var failLevel = false
        var failAutomaticMode = false
        var beforeWrite: () -> Unit = {}
        override fun read() = value
        override fun writeLevel(level: Int) {
            beforeWrite()
            if (failLevel) throw SecurityException("brightness write denied")
            value = value.copy(level = level)
        }
        override fun writeMode(mode: Int) {
            beforeWrite()
            if (mode == 1 && failAutomaticMode) throw SecurityException("mode write denied")
            value = value.copy(mode = mode)
        }
    }

    @Test
    fun savesOriginalBeforeAnyChangeAndRestoresAutomaticBrightness() {
        val store = Store()
        val port = Port(BrightnessValues(41, 1))
        port.beforeWrite = { assertNotNull("Original brightness must already be persisted", store.value) }
        val override = BrightnessOverride(port, store)
        assertTrue(override.dim())
        assertEquals(BrightnessValues(8, 0), port.value)
        assertEquals(BrightnessRestoreResult.RESTORED, override.restore())
        assertEquals(BrightnessValues(41, 1), port.value)
        assertNull(store.value)
    }

    @Test
    fun aUserBrightnessOrModeChangeIsNeverOverwritten() {
        for (userValue in listOf(BrightnessValues(90, 0), BrightnessValues(8, 1))) {
            val store = Store()
            val port = Port(BrightnessValues(100, 1))
            val override = BrightnessOverride(port, store)
            override.dim()
            port.value = userValue
            assertEquals(BrightnessRestoreResult.USER_CHANGED, override.restore())
            assertEquals(userValue, port.value)
            assertNull(store.value)
        }
    }

    @Test
    fun aFailedDimWriteRestoresTheOriginalAutomaticMode() {
        val store = Store()
        val port = Port(BrightnessValues(41, 1)).apply { failLevel = true }
        try {
            BrightnessOverride(port, store).dim()
            fail("Write failure should be reported")
        } catch (_: SecurityException) {
            assertEquals(BrightnessValues(41, 1), port.value)
            assertNull(store.value)
        }
    }

    @Test
    fun processRestartCanRecoverAnInterruptedModeChange() {
        val store = Store().apply {
            value = BrightnessLease(BrightnessValues(41, 1), BrightnessValues(8, 0))
        }
        val port = Port(BrightnessValues(41, 0))
        assertEquals(BrightnessRestoreResult.RESTORED, BrightnessOverride(port, store).restore())
        assertEquals(BrightnessValues(41, 1), port.value)
    }

    @Test
    fun interruptedRestoreKeepsItsLeaseForTheNextRecoveryAttempt() {
        val store = Store()
        val port = Port(BrightnessValues(41, 1))
        val override = BrightnessOverride(port, store)
        override.dim()
        port.failAutomaticMode = true
        try {
            override.restore()
            fail("Mode restore failure should be reported")
        } catch (_: SecurityException) {
            assertEquals(BrightnessLeasePhase.RESTORING, store.value?.phase)
            assertEquals(BrightnessValues(41, 0), port.value)
        }
        port.failAutomaticMode = false
        assertEquals(BrightnessRestoreResult.RESTORED, BrightnessOverride(port, store).restore())
        assertEquals(BrightnessValues(41, 1), port.value)
        assertNull(store.value)
    }

    @Test
    fun minimumBrightnessIsNotIncreasedOrForcedIntoManualMode() {
        val store = Store()
        val port = Port(BrightnessValues(1, 1))
        assertFalse(BrightnessOverride(port, store).dim())
        assertEquals(BrightnessValues(1, 1), port.value)
        assertNull(store.value)
    }
}
