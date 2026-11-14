package com.example.thermalguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

/**
 * 电池温度与充电状态快照
 */
data class BatterySnapshot(
    val tempTenthsC: Int?,      // 电池温度（单位 0.1°C，例如 450 为 45.0°C，无效或缺失时为 null）
    val isPlugged: Boolean,     // 是否接入外部电源（充电中）
    val timestampMs: Long
)

/**
 * 电池温度与充电状态监听器
 */
class BatteryTemperatureMonitor(
    private val context: Context,
    private val onBatteryChanged: (BatterySnapshot) -> Unit
) {
    private var isRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                val snapshot = parseBatteryIntent(intent)
                onBatteryChanged(snapshot)
            }
        }
    }

    /**
     * 解析粘性/实时电池广播
     */
    private fun parseBatteryIntent(intent: Intent): BatterySnapshot {
        // EXTRA_TEMPERATURE 单位为 0.1°C
        val rawTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        // 过滤超出 -20.0°C ~ 100.0°C (-200 ~ 1000) 的异常数值，缺失时不当成 0°C 处理 (§3.2)
        val validTemp: Int? = rawTemp.takeIf { it != Int.MIN_VALUE && it in -200..1000 }

        // 非 0 即视为“充电中”（含 AC, USB, WIRELESS, DOCK 等）
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0

        return BatterySnapshot(
            tempTenthsC = validTemp,
            isPlugged = plugged,
            timestampMs = System.currentTimeMillis()
        )
    }

    /**
     * 单次读取当前粘性 (Sticky) 广播中的电池状态
     */
    fun readCurrent(): BatterySnapshot? {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.let { parseBatteryIntent(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read current sticky battery status", e)
            null
        }
    }

    fun start() {
        if (isRegistered) return
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(receiver, filter)
        isRegistered = true
        Log.i(TAG, "BatteryTemperatureMonitor registered.")
    }

    fun stop() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering BatteryTemperatureMonitor", e)
        }
        isRegistered = false
        Log.i(TAG, "BatteryTemperatureMonitor unregistered.")
    }

    companion object {
        private const val TAG = "BatteryMonitor"
    }
}
