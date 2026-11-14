package com.example.thermalguard.monitor

import org.junit.Assert.*
import org.junit.Test

class FontGuardBroadcastHandlerTest {
    @Test
    fun unorderedBroadcastDoesNotAttemptAbort() {
        val result = FontGuardBroadcastHandler.handle(
            FontGuardBroadcastHandler.CHECK_TIME_UP, isOrdered = false, allowsAbort = true
        ) { fail("An unordered broadcast cannot be aborted") }
        assertEquals(FontGuardOutcome.UNORDERED, result?.outcome)
    }

    @Test
    fun nonAbortableBroadcastIsReportedWithoutCallingAbort() {
        val result = FontGuardBroadcastHandler.handle(
            FontGuardBroadcastHandler.CHECK_TIME_UP, isOrdered = true, allowsAbort = false
        ) { fail("A broadcast with NO_ABORT must not be reported as intercepted") }
        assertEquals(FontGuardOutcome.ABORT_NOT_ALLOWED, result?.outcome)
    }

    @Test
    fun bothThemeActionsRequestAbortWithoutClaimingVerifiedProtection() {
        for (action in FontGuardBroadcastHandler.actions) {
            var calls = 0
            val result = FontGuardBroadcastHandler.handle(action, isOrdered = true, allowsAbort = true) { calls++ }
            assertEquals(1, calls)
            assertEquals(FontGuardOutcome.ABORT_REQUESTED, result?.outcome)
            assertNull(result?.errorMessage)
        }
    }

    @Test
    fun unrelatedAndMissingActionsAreIgnored() {
        for (action in listOf(null, "android.intent.action.SCREEN_OFF")) {
            assertNull(FontGuardBroadcastHandler.handle(action, isOrdered = true, allowsAbort = true) {
                fail("Screen and unrelated broadcasts must not be interrupted")
            })
        }
    }

    @Test
    fun platformFailureIsReportedWithoutCrashingTheLockService() {
        val result = FontGuardBroadcastHandler.handle(
            FontGuardBroadcastHandler.CHECK_THEME_UPDATE, isOrdered = true, allowsAbort = true
        ) { throw SecurityException("platform rejected abort") }
        assertEquals(FontGuardOutcome.FAILED, result?.outcome)
        assertEquals("platform rejected abort", result?.errorMessage)
    }
}
