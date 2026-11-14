package com.example.thermalguard.rule

sealed interface InactivityDimmingDecision {
    data class Wait(val delayMs: Long) : InactivityDimmingDecision
    data object Dim : InactivityDimmingDecision
    data object Restore : InactivityDimmingDecision
    data object Stop : InactivityDimmingDecision
    data object Unavailable : InactivityDimmingDecision
}

/** User activity timestamps use uptimeMillis, not elapsedRealtime (which includes sleep). */
object InactivityDimmingRule {
    const val TIMEOUT_MS = 20 * 60 * 1000L
    const val DIMMED_CHECK_MS = 5_000L

    fun decide(
        nowUptimeMs: Long,
        lastActivityUptimeMs: Long?,
        interactive: Boolean,
        dimmedForActivityUptimeMs: Long?
    ): InactivityDimmingDecision {
        if (!interactive) {
            return if (dimmedForActivityUptimeMs != null) InactivityDimmingDecision.Restore
                else InactivityDimmingDecision.Stop
        }
        if (lastActivityUptimeMs == null || lastActivityUptimeMs < 0 || lastActivityUptimeMs > nowUptimeMs) {
            return if (dimmedForActivityUptimeMs != null) InactivityDimmingDecision.Restore
                else InactivityDimmingDecision.Unavailable
        }
        if (dimmedForActivityUptimeMs != null) {
            return if (lastActivityUptimeMs > dimmedForActivityUptimeMs) InactivityDimmingDecision.Restore
                else InactivityDimmingDecision.Wait(DIMMED_CHECK_MS)
        }
        val remaining = TIMEOUT_MS - (nowUptimeMs - lastActivityUptimeMs)
        return if (remaining <= 0) InactivityDimmingDecision.Dim
            else InactivityDimmingDecision.Wait(remaining)
    }

    fun dimmedBrightness(original: Int): Int {
        require(original >= 1)
        return (original / 5).coerceAtLeast(1).coerceAtMost(original)
    }
}
