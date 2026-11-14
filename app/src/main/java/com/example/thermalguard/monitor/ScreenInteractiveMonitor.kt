package com.example.thermalguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.util.Log

/**
 * 屏幕亮灭与交互状态监听器（动态注册与注销）
 */
class ScreenInteractiveMonitor(
    private val context: Context,
    private val onScreenOn: () -> Unit,
    private val onScreenOff: () -> Unit
) {
    private var isRegistered = false
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    Log.d(TAG, "Screen interactive event: ${intent.action}")
                    onScreenOn()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    Log.d(TAG, "Screen non-interactive event: ${intent.action}")
                    onScreenOff()
                }
            }
        }
    }

    /**
     * 当前系统是否处于交互（点亮）状态
     */
    fun isInteractive(): Boolean {
        return try {
            powerManager.isInteractive
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query powerManager.isInteractive", e)
            false
        }
    }

    /**
     * 注册屏幕监听广播
     */
    fun start() {
        if (isRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, filter)
        isRegistered = true
        Log.i(TAG, "ScreenInteractiveMonitor registered.")
    }

    /**
     * 注销屏幕监听广播
     */
    fun stop() {
        if (!isRegistered) return
        try {
            context.unregisterReceiver(receiver)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering ScreenInteractiveMonitor", e)
        }
        isRegistered = false
        Log.i(TAG, "ScreenInteractiveMonitor unregistered.")
    }

    companion object {
        private const val TAG = "ScreenMonitor"
    }
}
