package com.example.thermalguard.monitor

import android.content.Context
import android.provider.Settings
import com.example.thermalguard.rule.BrightnessLease
import com.example.thermalguard.rule.BrightnessLeasePhase
import com.example.thermalguard.rule.BrightnessLeaseStore
import com.example.thermalguard.rule.BrightnessPort
import com.example.thermalguard.rule.BrightnessValues
import java.io.IOException

class AndroidBrightnessPort(private val context: Context) : BrightnessPort {
    override fun read() = BrightnessValues(
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1),
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
    )

    override fun writeLevel(level: Int) = write(Settings.System.SCREEN_BRIGHTNESS, level)
    override fun writeMode(mode: Int) = write(Settings.System.SCREEN_BRIGHTNESS_MODE, mode)

    private fun write(key: String, value: Int) {
        check(Settings.System.canWrite(context)) { "尚未允许修改系统设置" }
        if (!Settings.System.putInt(context.contentResolver, key, value)) {
            throw IOException("系统未接受亮度设置：$key")
        }
    }
}

class SharedPrefsBrightnessLeaseStore(context: Context) : BrightnessLeaseStore {
    private val prefs = context.applicationContext.getSharedPreferences("idle_dimming_lease", Context.MODE_PRIVATE)

    override fun load(): BrightnessLease? {
        if (!prefs.getBoolean("active", false)) return null
        val phase = BrightnessLeasePhase.entries.firstOrNull { it.name == prefs.getString("phase", null) }
            ?: throw IOException("亮度恢复记录不完整，请手动检查亮度")
        val lease = BrightnessLease(
            BrightnessValues(prefs.getInt("original_level", -1), prefs.getInt("original_mode", -1)),
            BrightnessValues(prefs.getInt("target_level", -1), 0),
            phase
        )
        if (lease.original.level < 1 || lease.original.mode !in 0..1 || lease.target.level !in 1..lease.original.level) {
            throw IOException("亮度恢复记录无效，请手动检查亮度")
        }
        return lease
    }

    override fun save(lease: BrightnessLease) {
        if (!prefs.edit()
                .putBoolean("active", true)
                .putInt("original_level", lease.original.level)
                .putInt("original_mode", lease.original.mode)
                .putInt("target_level", lease.target.level)
                .putString("phase", lease.phase.name)
                .commit()) throw IOException("无法保存原亮度，已放弃修改")
    }

    override fun clear() {
        if (!prefs.edit().clear().commit()) throw IOException("无法清除亮度恢复记录")
    }
}
