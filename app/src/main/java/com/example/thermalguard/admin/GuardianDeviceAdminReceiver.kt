package com.example.thermalguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.data.GuardianSettingsRepository
import com.example.thermalguard.service.GuardianService

/**
 * 设备管理接收器，声明 force-lock 权限以允许安全锁屏。
 */
class GuardianDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "GuardianDeviceAdmin"

        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, GuardianDeviceAdminReceiver::class.java)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled by user.")
        GuardianLogRepository.addLog("权限", "用户已激活设备管理员 (force-lock 权限就绪)")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled by user.")
        // 权限撤销后，立即停止服务并清除开启愿望，防止意外锁屏与崩溃 (§4.2, S-05)
        GuardianService.stopService(context)
        GuardianSettingsRepository(context).setGuardianDesired(false)
        GuardianLogRepository.addLog(
            "权限撤销",
            "设备管理员已被用户取消，守护服务已自动停止并注销",
            isWarningOrError = true
        )
    }
}
