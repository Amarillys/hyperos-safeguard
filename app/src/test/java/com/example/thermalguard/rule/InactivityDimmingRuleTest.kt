package com.example.thermalguard.rule

import org.junit.Assert.*
import org.junit.Test

class InactivityDimmingRuleTest {
    @Test
    fun deadlineTracksLastInteractionRatherThanContinuousScreenOn() {
        val before = InactivityDimmingRule.decide(1_200_000L, 300_000L, true, null)
        assertEquals(InactivityDimmingDecision.Wait(300_000L), before)
        assertEquals(InactivityDimmingDecision.Dim, InactivityDimmingRule.decide(1_500_000L, 300_000L, true, null))
    }

    @Test
    fun anInteractionBeforeTheDeadlinePostponesDimming() {
        assertEquals(
            InactivityDimmingDecision.Wait(1_100_000L),
            InactivityDimmingRule.decide(1_200_000L, 1_100_000L, true, null)
        )
    }

    @Test
    fun interactionAfterDimmingRequestsRestore() {
        assertEquals(InactivityDimmingDecision.Restore, InactivityDimmingRule.decide(1_300_000L, 1_299_000L, true, 0L))
        assertEquals(InactivityDimmingDecision.Wait(5_000L), InactivityDimmingRule.decide(1_300_000L, 0L, true, 0L))
    }

    @Test
    fun screenOffNeverDimsAndRestoresAnExistingOverride() {
        assertEquals(InactivityDimmingDecision.Stop, InactivityDimmingRule.decide(2_000_000L, 0L, false, null))
        assertEquals(InactivityDimmingDecision.Restore, InactivityDimmingRule.decide(2_000_000L, 0L, false, 0L))
    }

    @Test
    fun missingOrFutureActivityIsNeverTreatedAsTwentyMinutesIdle() {
        for (invalid in listOf(null, -1L, 2_000_001L)) {
            assertEquals(InactivityDimmingDecision.Unavailable, InactivityDimmingRule.decide(2_000_000L, invalid, true, null))
            assertEquals(InactivityDimmingDecision.Restore, InactivityDimmingRule.decide(2_000_000L, invalid, true, 0L))
        }
    }

    @Test
    fun brightnessIsRelativeAndNeverIncreasesAnAlreadyLowLevel() {
        assertEquals(8, InactivityDimmingRule.dimmedBrightness(41))
        assertEquals(204, InactivityDimmingRule.dimmedBrightness(1023))
        assertEquals(1, InactivityDimmingRule.dimmedBrightness(1))
        assertEquals(1, InactivityDimmingRule.dimmedBrightness(4))
    }
}
