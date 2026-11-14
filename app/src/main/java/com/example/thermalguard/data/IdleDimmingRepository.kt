package com.example.thermalguard.data

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class IdleDimmingStatus(val displayName: String) {
    STOPPED("等待守护服务运行"),
    SCREEN_OFF("屏幕已熄灭，暂停检测"),
    PAUSED("守护已暂停，亮度保持原设置"),
    NEEDS_PERMISSION("等待授权"),
    MONITORING("检测中 · 无操作 20 分钟后调暗"),
    DIMMED("已调暗 · 操作后约 5 秒内恢复"),
    ERROR("暂时无法检测或恢复亮度")
}

data class IdleDimmingState(
    val enabled: Boolean = false,
    val hasDumpPermission: Boolean = false,
    val canWriteSettings: Boolean = false,
    val permissionRevision: Int = 0,
    val status: IdleDimmingStatus = IdleDimmingStatus.STOPPED,
    val dimmed: Boolean = false,
    val lastMessage: String? = null,
    val error: String? = null
)

object IdleDimmingRepository {
    private var prefs: SharedPreferences? = null
    private val mutableState = MutableStateFlow(IdleDimmingState())
    val state = mutableState.asStateFlow()

    fun initialize(context: Context) {
        if (prefs != null) return
        val stored = context.applicationContext.getSharedPreferences("idle_dimming_settings", Context.MODE_PRIVATE)
        prefs = stored
        mutableState.value = IdleDimmingState(
            enabled = stored.getBoolean("enabled", false),
            lastMessage = stored.getString("last_message", null)
        )
        refreshPermissions(context)
    }

    fun setEnabled(enabled: Boolean) {
        checkNotNull(prefs).edit { putBoolean("enabled", enabled) }
        mutableState.update { it.copy(enabled = enabled, error = null) }
    }

    fun refreshPermissions(context: Context) {
        val dump = ContextCompat.checkSelfPermission(context, Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
        val write = Settings.System.canWrite(context)
        mutableState.update {
            it.copy(hasDumpPermission = dump, canWriteSettings = write, permissionRevision = it.permissionRevision + 1)
        }
    }

    fun runtime(status: IdleDimmingStatus, dimmed: Boolean = false, error: String? = null) {
        mutableState.update { it.copy(status = status, dimmed = dimmed, error = error) }
    }

    fun log(message: String, warning: Boolean = false) {
        checkNotNull(prefs).edit { putString("last_message", message) }
        mutableState.update { it.copy(lastMessage = message) }
        GuardianLogRepository.addLog("无操作调暗", message, isWarningOrError = warning)
    }
}
