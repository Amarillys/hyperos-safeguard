package com.example.thermalguard.monitor

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * 系统热状态等级与名称映射
 */
enum class ThermalLevel(val statusCode: Int, val displayName: String) {
    NONE(0, "正常 (None)"),
    LIGHT(1, "轻微 (Light)"),
    MODERATE(2, "温热 (Moderate)"),
    SEVERE(3, "发热严重 (Severe)"),
    CRITICAL(4, "极度高温 (Critical)"),
    EMERGENCY(5, "紧急高温 (Emergency)"),
    SHUTDOWN(6, "关机保护 (Shutdown)"),
    UNAVAILABLE(-1, "不可用");

    companion object {
        fun fromStatusCode(code: Int): ThermalLevel {
            return entries.firstOrNull { it.statusCode == code } ?: UNAVAILABLE
        }
    }
}

/**
 * 系统热状态监听器 (Android 10+)
 */
class ThermalStatusMonitor(
    context: Context,
    private val onThermalStatusChanged: (ThermalLevel) -> Unit
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    /**
     * 读取当前实时热状态
     */
    fun getCurrentThermalLevel(): ThermalLevel {
        if (powerManager == null) return ThermalLevel.UNAVAILABLE
        return try {
            val status = powerManager.currentThermalStatus
            ThermalLevel.fromStatusCode(status)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting currentThermalStatus", e)
            ThermalLevel.UNAVAILABLE
        }
    }

    fun start() {
        if (powerManager == null || listener != null) return
        try {
            val newListener = PowerManager.OnThermalStatusChangedListener { status ->
                val level = ThermalLevel.fromStatusCode(status)
                Log.i(TAG, "Thermal status changed: ${level.displayName}")
                onThermalStatusChanged(level)
            }
            powerManager.addThermalStatusListener(newListener)
            listener = newListener
            Log.i(TAG, "ThermalStatusListener registered.")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering ThermalStatusListener", e)
            onThermalStatusChanged(ThermalLevel.UNAVAILABLE)
        }
    }

    fun stop() {
        val current = listener ?: return
        try {
            powerManager?.removeThermalStatusListener(current)
        } catch (e: Exception) {
            Log.e(TAG, "Error removing ThermalStatusListener", e)
        }
        listener = null
        Log.i(TAG, "ThermalStatusListener unregistered.")
    }

    companion object {
        private const val TAG = "ThermalMonitor"
    }
}
