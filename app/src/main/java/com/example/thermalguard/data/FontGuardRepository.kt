package com.example.thermalguard.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.thermalguard.monitor.FontGuardOutcome
import com.example.thermalguard.monitor.FontGuardResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class FontGuardEvent(
    val timestampMs: Long,
    val action: String,
    val isOrdered: Boolean,
    val result: FontGuardResult,
    val isSelfTest: Boolean = false
)

data class FontGuardState(
    val enabled: Boolean = false,
    val listening: Boolean = false,
    val registrationError: String? = null,
    val receivedCount: Int = 0,
    val lastEvent: FontGuardEvent? = null
)

/** Shared by the Activity and its existing service in the same process. */
object FontGuardRepository {
    private var prefs: SharedPreferences? = null
    private val mutableState = MutableStateFlow(FontGuardState())
    val state = mutableState.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences("font_guard_state", Context.MODE_PRIVATE)
        prefs = stored
        val savedOutcome = stored.getString("last_outcome", null)?.let { name ->
            FontGuardOutcome.entries.firstOrNull { it.name == name }
        }
        val lastEvent = savedOutcome?.let {
            FontGuardEvent(
                stored.getLong("last_timestamp", 0L),
                stored.getString("last_action", "").orEmpty(),
                stored.getBoolean("last_ordered", false),
                FontGuardResult(it, stored.getString("last_error", null)),
                stored.getBoolean("last_self_test", false)
            )
        }
        mutableState.value = FontGuardState(
            enabled = stored.getBoolean("enabled", false),
            receivedCount = stored.getInt("received_count", 0),
            lastEvent = lastEvent
        )
    }

    fun setEnabled(enabled: Boolean) {
        checkNotNull(prefs).edit { putBoolean("enabled", enabled) }
        mutableState.update { it.copy(enabled = enabled, registrationError = null) }
    }

    fun setListening(listening: Boolean, error: String? = null) {
        mutableState.update { it.copy(listening = listening, registrationError = error) }
    }

    fun record(event: FontGuardEvent) {
        val increment = if (event.isSelfTest) 0 else 1
        val count = (mutableState.value.receivedCount.toLong() + increment).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        checkNotNull(prefs).edit {
            putInt("received_count", count)
            putLong("last_timestamp", event.timestampMs)
            putString("last_action", event.action)
            putBoolean("last_ordered", event.isOrdered)
            putBoolean("last_self_test", event.isSelfTest)
            putString("last_outcome", event.result.outcome.name)
            putString("last_error", event.result.errorMessage)
        }
        mutableState.update { it.copy(receivedCount = count, lastEvent = event) }
    }
}
