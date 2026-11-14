package com.example.thermalguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.thermalguard.admin.GuardianDeviceAdminReceiver
import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.data.GuardianSettingsRepository
import com.example.thermalguard.service.GuardianService

/**
 * 接收系统开机与应用覆盖安装更新广播，尝试按需恢复前台守护 (§4.2)
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "BootReceiver onReceive: $action")

        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val settingsRepo = GuardianSettingsRepository(context)
        val isDesired = settingsRepo.isGuardianDesired()

        // 仅当用户此前主动开启过守护时才尝试恢复
        if (!isDesired) {
            Log.i(TAG, "Guardian recovery skipped: user did not enable guardian previously.")
            return
        }

        // 校验设备管理员当前是否有效
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        val adminActive = dpm?.isAdminActive(GuardianDeviceAdminReceiver.getComponentName(context)) == true

        if (!adminActive) {
            Log.w(TAG, "Guardian recovery skipped: Device admin is no longer active.")
            GuardianLogRepository.addLog(
                "恢复跳过",
                "收到 $action，但设备管理员已被撤销，放弃自启恢复",
                isWarningOrError = true
            )
            return
        }

        // 尝试恢复启动前台服务（单次尝试，不循环重试，§4.2）
        try {
            GuardianLogRepository.addLog(
                "自动恢复",
                "收到 ${if (action == Intent.ACTION_BOOT_COMPLETED) "开机广播" else "应用更新广播"}，尝试拉起守护服务"
            )
            GuardianService.startService(context)
            Log.i(TAG, "GuardianService restored successfully after $action")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore GuardianService on $action", e)
            GuardianLogRepository.addLog(
                "恢复失败",
                "拉起守护服务发生异常: ${e.message}，按规范不再自动重试",
                isWarningOrError = true
            )
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
