package com.example.thermalguard.util

import android.os.SystemClock

/**
 * 时间与时钟提供接口，便于规则引擎与 Android 系统解耦及单元测试。
 */
interface TimeProvider {
    /**
     * 单调流逝时间（毫秒），等同于 SystemClock.elapsedRealtime()
     */
    fun elapsedRealtime(): Long

    /**
     * 当前挂钟时间（毫秒），等同于 System.currentTimeMillis()
     */
    fun currentTimeMillis(): Long
}

/**
 * 默认 Android 系统时钟实现
 */
class DefaultTimeProvider : TimeProvider {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
