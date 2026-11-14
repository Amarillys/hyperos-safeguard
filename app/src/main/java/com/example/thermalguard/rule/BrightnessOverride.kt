package com.example.thermalguard.rule

data class BrightnessValues(val level: Int, val mode: Int)
enum class BrightnessLeasePhase { PREPARING, DIMMED, RESTORING }
data class BrightnessLease(
    val original: BrightnessValues,
    val target: BrightnessValues,
    val phase: BrightnessLeasePhase = BrightnessLeasePhase.PREPARING
)

interface BrightnessPort {
    fun read(): BrightnessValues
    fun writeLevel(level: Int)
    fun writeMode(mode: Int)
}

interface BrightnessLeaseStore {
    fun load(): BrightnessLease?
    /** Must finish persisting before returning so a process death cannot lose the original values. */
    fun save(lease: BrightnessLease)
    fun clear()
}

enum class BrightnessRestoreResult { NONE, RESTORED, USER_CHANGED }

/** Own only the values we wrote; a manual brightness/mode change always releases the override. */
class BrightnessOverride(
    private val port: BrightnessPort,
    private val store: BrightnessLeaseStore
) {
    val hasLease: Boolean get() = store.load() != null

    fun dim(): Boolean {
        check(!hasLease) { "A brightness override is already pending" }
        val original = port.read()
        require(original.level >= 1 && original.mode in 0..1) { "Unsupported brightness settings" }
        val target = BrightnessValues(InactivityDimmingRule.dimmedBrightness(original.level), 0)
        if (target.level >= original.level) return false
        val lease = BrightnessLease(original, target)
        store.save(lease)
        try {
            if (original.mode != target.mode) port.writeMode(target.mode)
            port.writeLevel(target.level)
            store.save(lease.copy(phase = BrightnessLeasePhase.DIMMED))
            return true
        } catch (failure: Exception) {
            try {
                restore()
            } catch (restoreFailure: Exception) {
                failure.addSuppressed(restoreFailure)
            }
            throw failure
        }
    }

    fun restore(): BrightnessRestoreResult {
        val lease = store.load() ?: return BrightnessRestoreResult.NONE
        val current = port.read()
        if (current == lease.original) {
            store.clear()
            return BrightnessRestoreResult.RESTORED
        }
        val intermediate = BrightnessValues(lease.original.level, lease.target.mode)
        val stillOwned = current == lease.target ||
            (lease.phase != BrightnessLeasePhase.DIMMED && current == intermediate)
        if (!stillOwned) {
            store.clear()
            return BrightnessRestoreResult.USER_CHANGED
        }
        store.save(lease.copy(phase = BrightnessLeasePhase.RESTORING))
        if (current.level != lease.original.level) port.writeLevel(lease.original.level)
        if (current.mode != lease.original.mode) port.writeMode(lease.original.mode)
        store.clear()
        return BrightnessRestoreResult.RESTORED
    }

    fun isStillOwned(): Boolean {
        val lease = store.load() ?: return false
        if (port.read() == lease.target) return true
        // Do not restore over a user-selected brightness or automatic-brightness mode.
        store.clear()
        return false
    }
}
