package com.example.thermalguard.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 用户配置持久化存储仓库
 */
class GuardianSettingsRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<GuardianSettings> = _settingsFlow.asStateFlow()

    private val _isGuardianDesiredFlow = MutableStateFlow(loadGuardianDesired())
    val isGuardianDesiredFlow: StateFlow<Boolean> = _isGuardianDesiredFlow.asStateFlow()

    /**
     * 读取用户是否主动希望开启守护（用于重启恢复校验，§4.2）
     */
    fun isGuardianDesired(): Boolean = prefs.getBoolean(KEY_GUARDIAN_DESIRED, false)

    /**
     * 保存用户是否主动希望开启守护
     */
    fun setGuardianDesired(desired: Boolean) {
        prefs.edit().putBoolean(KEY_GUARDIAN_DESIRED, desired).apply()
        _isGuardianDesiredFlow.value = desired
    }

    /**
     * 读取配置实体
     */
    fun loadSettings(): GuardianSettings {
        return GuardianSettings(
            warningTempTenthsC = prefs.getInt(KEY_WARNING_TEMP, 420),
            lockTempTenthsC = prefs.getInt(KEY_LOCK_TEMP, 450),
            chargingLockTempTenthsC = prefs.getInt(KEY_CHARGING_LOCK_TEMP, 460),
            recoveryTempTenthsC = prefs.getInt(KEY_RECOVERY_TEMP, 410),
            antiShakeSeconds = prefs.getInt(KEY_ANTI_SHAKE_SEC, 10),
            maxScreenOnMinutes = prefs.getInt(KEY_MAX_SCREEN_ON_MIN, 30),
            isThermalLinkageEnabled = prefs.getBoolean(KEY_THERMAL_LINKAGE, true),
            isTempGuardEnabled = prefs.getBoolean(KEY_TEMP_GUARD, true),
            isScreenGuardEnabled = prefs.getBoolean(KEY_SCREEN_GUARD, true)
        )
    }

    /**
     * 保存配置实体（严格先校验）
     */
    fun saveSettings(settings: GuardianSettings): ValidationResult {
        val validation = settings.validate()
        if (!validation.isValid) {
            return validation
        }

        prefs.edit()
            .putInt(KEY_WARNING_TEMP, settings.warningTempTenthsC)
            .putInt(KEY_LOCK_TEMP, settings.lockTempTenthsC)
            .putInt(KEY_CHARGING_LOCK_TEMP, settings.chargingLockTempTenthsC)
            .putInt(KEY_RECOVERY_TEMP, settings.recoveryTempTenthsC)
            .putInt(KEY_ANTI_SHAKE_SEC, settings.antiShakeSeconds)
            .putInt(KEY_MAX_SCREEN_ON_MIN, settings.maxScreenOnMinutes)
            .putBoolean(KEY_THERMAL_LINKAGE, settings.isThermalLinkageEnabled)
            .putBoolean(KEY_TEMP_GUARD, settings.isTempGuardEnabled)
            .putBoolean(KEY_SCREEN_GUARD, settings.isScreenGuardEnabled)
            .apply()

        _settingsFlow.value = settings
        return ValidationResult.Valid
    }

    private fun loadGuardianDesired(): Boolean {
        return prefs.getBoolean(KEY_GUARDIAN_DESIRED, false)
    }

    companion object {
        private const val PREFS_NAME = "guardian_user_settings"
        private const val KEY_GUARDIAN_DESIRED = "is_guardian_desired_enabled"
        private const val KEY_WARNING_TEMP = "warning_temp_tenths_c"
        private const val KEY_LOCK_TEMP = "lock_temp_tenths_c"
        private const val KEY_CHARGING_LOCK_TEMP = "charging_lock_temp_tenths_c"
        private const val KEY_RECOVERY_TEMP = "recovery_temp_tenths_c"
        private const val KEY_ANTI_SHAKE_SEC = "anti_shake_seconds"
        private const val KEY_MAX_SCREEN_ON_MIN = "max_screen_on_minutes"
        private const val KEY_THERMAL_LINKAGE = "is_thermal_linkage_enabled"
        private const val KEY_TEMP_GUARD = "is_temp_guard_enabled"
        private const val KEY_SCREEN_GUARD = "is_screen_guard_enabled"
    }
}
