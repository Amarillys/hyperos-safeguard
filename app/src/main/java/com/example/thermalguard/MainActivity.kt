package com.example.thermalguard

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.thermalguard.admin.GuardianDeviceAdminReceiver
import com.example.thermalguard.data.GuardianLogRepository
import com.example.thermalguard.data.GuardianSettings
import com.example.thermalguard.data.GuardianSettingsRepository
import com.example.thermalguard.data.FontGuardRepository
import com.example.thermalguard.data.IdleDimmingRepository
import com.example.thermalguard.rule.AndroidLockExecutor
import com.example.thermalguard.rule.LockCoordinator
import com.example.thermalguard.rule.LockReason
import com.example.thermalguard.rule.LockResult
import com.example.thermalguard.service.GuardianService
import com.example.thermalguard.ui.PhaseFourScreen
import com.example.thermalguard.ui.theme.PhoneGuardTheme

/**
 * 主 Activity（阶段四）：全功能集成，支持设置修改、日志导出、HyperOS保活引导与状态监控
 */
class MainActivity : ComponentActivity() {

    private lateinit var lockCoordinator: LockCoordinator
    private lateinit var settingsRepo: GuardianSettingsRepository

    private val adminComponent by lazy {
        GuardianDeviceAdminReceiver.getComponentName(this)
    }
    private val devicePolicyManager by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private var isAdminActiveState by mutableStateOf(false)
    private var isNotificationGrantedState by mutableStateOf(false)

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            isNotificationGrantedState = granted
            if (!granted) {
                Toast.makeText(this, "建议授予通知权限以确保前台守护服务正常常驻", Toast.LENGTH_LONG).show()
            }
        }

    // 系统文件选择器导出日志 (无需申请外部存储权限，§5)
    private val exportLogsLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
            if (uri != null) {
                exportLogsToUri(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        settingsRepo = GuardianSettingsRepository(this)
        FontGuardRepository.initialize(this)
        IdleDimmingRepository.initialize(this)
        val lockExecutor = AndroidLockExecutor(this, adminComponent)
        lockCoordinator = LockCoordinator(lockExecutor)

        refreshAdminStatus()
        checkNotificationPermission()

        setContent {
            PhoneGuardTheme {
                val serviceState by GuardianService.serviceStateFlow.collectAsState()
                val logList by GuardianLogRepository.logsFlow.collectAsState()
                val currentSettings by settingsRepo.settingsFlow.collectAsState()
                val fontGuardState by FontGuardRepository.state.collectAsState()
                val idleDimmingState by IdleDimmingRepository.state.collectAsState()

                Surface(modifier = Modifier.fillMaxSize()) {
                    PhaseFourScreen(
                        isAdminActive = isAdminActiveState,
                        isNotificationPermGranted = isNotificationGrantedState,
                        serviceState = serviceState,
                        currentSettings = currentSettings,
                        fontGuardState = fontGuardState,
                        onFontGuardEnabledChange = FontGuardRepository::setEnabled,
                        idleDimmingState = idleDimmingState,
                        onIdleDimmingEnabledChange = IdleDimmingRepository::setEnabled,
                        onRequestWriteSettings = {
                            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
                        },
                        onCopyIdleDimmingAdbCommand = {
                            val command = "adb shell pm grant $packageName android.permission.DUMP"
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB 授权命令", command))
                            Toast.makeText(this, "已复制 ADB 授权命令", Toast.LENGTH_SHORT).show()
                        },
                        onRefreshIdleDimmingPermissions = { IdleDimmingRepository.refreshPermissions(this) },
                        onPreviewIdleDimming = { GuardianService.previewIdleDimming(this) },
                        logList = logList,
                        onRequestAdminPermission = ::requestDeviceAdminPermission,
                        onRequestNotificationPermission = ::requestNotificationPermission,
                        onStartService = ::startGuardianService,
                        onStopService = ::stopGuardianService,
                        onPauseService = { GuardianService.pause(this) },
                        onResumeService = { GuardianService.resume(this) },
                        onOpenSystemAppSettings = ::openSystemAppSettings,
                        onSaveSettings = ::saveNewSettings,
                        onExportLogs = { exportLogsLauncher.launch("phone_guard_logs.txt") },
                        onClearLogs = { GuardianLogRepository.clearLogs() },
                        onTestLock = ::executeTestLock,
                        onRevokeAdminPermission = ::revokeDeviceAdminPermission
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminStatus()
        checkNotificationPermission()
        IdleDimmingRepository.refreshPermissions(this)
    }

    private fun refreshAdminStatus() {
        isAdminActiveState = devicePolicyManager.isAdminActive(adminComponent)
    }

    private fun checkNotificationPermission() {
        isNotificationGrantedState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestDeviceAdminPermission() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                getString(R.string.device_admin_description)
            )
        }
        startActivity(intent)
    }

    private fun revokeDeviceAdminPermission() {
        GuardianService.stopService(this)
        if (devicePolicyManager.isAdminActive(adminComponent)) {
            devicePolicyManager.removeActiveAdmin(adminComponent)
            Toast.makeText(this, "已停止守护并取消设备管理员授权", Toast.LENGTH_SHORT).show()
        }
        refreshAdminStatus()
    }

    private fun startGuardianService() {
        if (!isAdminActiveState) {
            Toast.makeText(this, "请先激活设备管理员权限", Toast.LENGTH_SHORT).show()
            requestDeviceAdminPermission()
            return
        }
        GuardianService.startService(this)
    }

    private fun stopGuardianService() {
        GuardianService.stopService(this)
    }

    private fun openSystemAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    private fun saveNewSettings(newSettings: GuardianSettings) {
        val result = settingsRepo.saveSettings(newSettings)
        if (result.isValid) {
            Toast.makeText(this, "守护规则已成功更新并生效", Toast.LENGTH_SHORT).show()
            // 若服务正在运行，重启刷新配置
            if (GuardianService.serviceStateFlow.value.status == com.example.thermalguard.service.ServiceStatus.RUNNING) {
                GuardianService.startService(this)
            }
        } else {
            Toast.makeText(this, "配置校验失败: ${(result as com.example.thermalguard.data.ValidationResult.Invalid).reason}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportLogsToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                val logs = GuardianLogRepository.logsFlow.value
                val text = buildString {
                    appendLine("=== 小米 15 温控亮屏守护运行日志导出 ===")
                    appendLine("导出时间: ${java.util.Date()}")
                    appendLine("日志条数: ${logs.size}")
                    appendLine("----------------------------------------")
                    logs.forEach { log ->
                        appendLine("[${log.formattedTime}] [${log.eventType}] ${log.message}")
                    }
                }
                outputStream.write(text.toByteArray(Charsets.UTF_8))
                Toast.makeText(this, "日志导出成功！", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "日志导出失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun executeTestLock(): LockResult {
        refreshAdminStatus()
        return lockCoordinator.requestLock(
            reason = LockReason.MANUAL_TEST,
            isGuardianEnabled = true,
            isPaused = false,
            bypassInteractiveCheck = false
        )
    }
}
