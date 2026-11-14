package com.example.thermalguard.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.thermalguard.data.FontGuardEvent
import com.example.thermalguard.data.FontGuardRepository
import com.example.thermalguard.data.GuardianLogRepository

/** An event listener owned by GuardianService; no polling, extra service, or wake lock. */
class FontGuardMonitor(private val context: Context) {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!registered || !FontGuardRepository.state.value.enabled) return
            val action = intent?.action ?: return
            val ordered = isOrderedBroadcast
            val result = FontGuardBroadcastHandler.handle(
                action = action,
                isOrdered = ordered,
                allowsAbort = intent.flags and Intent.FLAG_RECEIVER_NO_ABORT == 0,
                abort = { abortBroadcast() }
            ) ?: return

            // Package-scoped diagnostics never reach the system theme receiver or count as real checks.
            val selfTest = intent.`package` == context?.packageName &&
                intent.getBooleanExtra(EXTRA_SELF_TEST, false)
            FontGuardRepository.record(FontGuardEvent(System.currentTimeMillis(), action, ordered, result, selfTest))
            val message = (if (selfTest) "[自检] " else "") +
                "收到 $action；有序=${if (ordered) "是" else "否"}；${result.outcome.displayName}" +
                (result.errorMessage?.let { "：$it" } ?: "")
            Log.i(TAG, message)
            GuardianLogRepository.addLog(
                "字体防回退", message,
                isWarningOrError = result.outcome != FontGuardOutcome.ABORT_REQUESTED
            )
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    private fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            FontGuardBroadcastHandler.actions.forEach(::addAction)
            // Android 16 reserves the endpoints for system receivers.
            priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
        }
        try {
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
            FontGuardRepository.setListening(true)
            Log.i(TAG, "Theme check receiver registered (priority=${filter.priority})")
            GuardianLogRepository.addLog("字体防回退", "实验监听已开启，等待主题校验广播；防回退效果尚未验证")
        } catch (e: RuntimeException) {
            val error = e.message ?: e.javaClass.simpleName
            FontGuardRepository.setListening(false, error)
            Log.e(TAG, "Cannot register theme check receiver", e)
            GuardianLogRepository.addLog("字体防回退", "监听注册失败：$error", isWarningOrError = true)
        }
    }

    fun stop() {
        if (!registered) {
            FontGuardRepository.setListening(false)
            return
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Theme check receiver was already unregistered", e)
        } finally {
            registered = false
            FontGuardRepository.setListening(false)
        }
        Log.i(TAG, "Theme check receiver unregistered")
        GuardianLogRepository.addLog("字体防回退", "实验监听已停止")
    }

    companion object {
        private const val TAG = "FontGuard"
        private const val EXTRA_SELF_TEST = "com.example.thermalguard.extra.FONT_GUARD_SELF_TEST"
    }
}
