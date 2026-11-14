package com.example.thermalguard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermalguard.data.GuardianSettings
import com.example.thermalguard.data.FontGuardState
import com.example.thermalguard.data.IdleDimmingState
import com.example.thermalguard.data.LogEntry
import com.example.thermalguard.rule.LockResult
import com.example.thermalguard.rule.TemperatureState
import com.example.thermalguard.service.GuardianServiceState
import com.example.thermalguard.service.ServiceStatus
import com.example.thermalguard.ui.components.HyperOSGuidanceCard
import com.example.thermalguard.ui.components.FontGuardCard
import com.example.thermalguard.ui.components.IdleDimmingCard
import com.example.thermalguard.ui.components.SettingsEditDialog
import com.example.thermalguard.ui.theme.ErrorRed
import com.example.thermalguard.ui.theme.InfoBlue
import com.example.thermalguard.ui.theme.SafeGreen
import com.example.thermalguard.ui.theme.WarningOrange

/**
 * 阶段四最终界面：包含温控与亮屏实时监控、HyperOS 设置引导、规则参数调优、日志导出与授权退出
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhaseFourScreen(
    isAdminActive: Boolean,
    isNotificationPermGranted: Boolean,
    serviceState: GuardianServiceState,
    currentSettings: GuardianSettings,
    fontGuardState: FontGuardState,
    onFontGuardEnabledChange: (Boolean) -> Unit,
    idleDimmingState: IdleDimmingState,
    onIdleDimmingEnabledChange: (Boolean) -> Unit,
    onRequestWriteSettings: () -> Unit,
    onCopyIdleDimmingAdbCommand: () -> Unit,
    onRefreshIdleDimmingPermissions: () -> Unit,
    onPreviewIdleDimming: () -> Unit,
    logList: List<LogEntry>,
    onRequestAdminPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onPauseService: () -> Unit,
    onResumeService: () -> Unit,
    onOpenSystemAppSettings: () -> Unit,
    onSaveSettings: (GuardianSettings) -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onTestLock: () -> LockResult,
    onRevokeAdminPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTestLockConfirmDialog by remember { mutableStateOf(false) }
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var lastLockResultMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "温控亮屏守护 (Xiaomi 15)",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Tune, contentDescription = "规则设置")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(4.dp)) }

            // 1. 服务总开关与快捷操作卡片
            item {
                ServiceControlCard(
                    isAdminActive = isAdminActive,
                    isNotificationPermGranted = isNotificationPermGranted,
                    serviceState = serviceState,
                    onRequestAdminPermission = onRequestAdminPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onStartService = onStartService,
                    onStopService = onStopService,
                    onPauseService = onPauseService,
                    onResumeService = onResumeService
                )
            }

            item {
                FontGuardCard(state = fontGuardState, onEnabledChange = onFontGuardEnabledChange)
            }

            item {
                IdleDimmingCard(
                    state = idleDimmingState,
                    guardianRunning = serviceState.status == ServiceStatus.RUNNING,
                    onEnabledChange = onIdleDimmingEnabledChange,
                    onRequestWriteSettings = onRequestWriteSettings,
                    onCopyAdbCommand = onCopyIdleDimmingAdbCommand,
                    onRefreshPermissions = onRefreshIdleDimmingPermissions,
                    onPreview = onPreviewIdleDimming
                )
            }

            // 2. 电池温度与热状态监控卡片 (§2, §3.2)
            item {
                BatteryThermalCard(serviceState = serviceState)
            }

            // 3. 屏幕交互与连续时长卡片 (§3.1)
            item {
                ScreenTimingCard(serviceState = serviceState)
            }

            // 4. 小米 HyperOS 澎湃 OS 设置建议与引导卡片 (§4.2)
            item {
                HyperOSGuidanceCard(onOpenSettings = onOpenSystemAppSettings)
            }

            // 5. 手动测试锁屏与权限退出卡片
            item {
                TestAndRevokeCard(
                    isAdminActive = isAdminActive,
                    onShowTestLockDialog = { showTestLockConfirmDialog = true },
                    onShowRevokeDialog = { showRevokeConfirmDialog = true },
                    lastResultMsg = lastLockResultMsg
                )
            }

            // 6. 日志标题栏（支持导出与清空）
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "事件日志 (${logList.size} 条)",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Row {
                        if (logList.isNotEmpty()) {
                            TextButton(onClick = onExportLogs) {
                                Text("导出文本")
                            }
                            TextButton(onClick = onClearLogs) {
                                Text("清空")
                            }
                        }
                    }
                }
            }

            // 7. 日志列表
            if (logList.isEmpty()) {
                item {
                    Text(
                        text = "暂无事件日志，开启守护或状态变动后将在此记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(logList, key = { it.id }) { log ->
                    LogItemRow(log = log)
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // 规则参数修改对话框
        if (showSettingsDialog) {
            SettingsEditDialog(
                currentSettings = currentSettings,
                onDismiss = { showSettingsDialog = false },
                onSave = { newSettings ->
                    onSaveSettings(newSettings)
                    showSettingsDialog = false
                }
            )
        }

        // 锁屏确认弹窗
        if (showTestLockConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showTestLockConfirmDialog = false },
                icon = { Icon(Icons.Default.Lock, contentDescription = null) },
                title = { Text("确认立即锁屏？") },
                text = {
                    Text("调用系统的 lockNow() 后屏幕会立即锁定。\n\n锁屏后需要输入 PIN/密码/图案 才能解锁，指纹/人脸可能暂时不可用。")
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showTestLockConfirmDialog = false
                            val result = onTestLock()
                            lastLockResultMsg = when (result) {
                                is LockResult.Success -> "锁屏执行成功！"
                                is LockResult.AdminNotActive -> "执行被拒绝：设备管理员未激活"
                                is LockResult.ScreenNotInteractive -> "执行被拒绝：屏幕未处于交互状态"
                                is LockResult.RejectedMinInterval -> "执行被拒绝：防抖冷却中 (剩余 ${result.remainingMs}ms)"
                                is LockResult.RejectedCooldown -> "执行被拒绝：高温冷却中 (剩余 ${result.remainingMs}ms)"
                                is LockResult.RejectedPaused -> "执行被拒绝：守护处于暂停状态"
                                is LockResult.RejectedGuardianDisabled -> "执行被拒绝：守护未开启"
                                is LockResult.SecurityError -> "权限异常: ${result.message}"
                                is LockResult.ExecutionError -> "执行失败: ${result.message}"
                            }
                        }
                    ) {
                        Text("立即锁屏")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showTestLockConfirmDialog = false }) {
                        Text("取消")
                    }
                }
            )
        }

        // 撤销管理员确认弹窗
        if (showRevokeConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRevokeConfirmDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed) },
                title = { Text("确认停止守护并取消设备管理员？") },
                text = {
                    Text("取消设备管理员后守护服务将立即停止，之后可直接长按图标卸载应用。")
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            showRevokeConfirmDialog = false
                            onRevokeAdminPermission()
                            lastLockResultMsg = "已撤销设备管理员"
                        }
                    ) {
                        Text("确认取消")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showRevokeConfirmDialog = false }) {
                        Text("返回")
                    }
                }
            )
        }
    }
}

@Composable
private fun BatteryThermalCard(serviceState: GuardianServiceState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (serviceState.temperatureState) {
                TemperatureState.NORMAL -> MaterialTheme.colorScheme.surfaceVariant
                TemperatureState.WARNING -> WarningOrange.copy(alpha = 0.15f)
                TemperatureState.CONFIRMING -> ErrorRed.copy(alpha = 0.18f)
                TemperatureState.LOCKED_COOLDOWN -> InfoBlue.copy(alpha = 0.15f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Thermostat,
                        contentDescription = null,
                        tint = when (serviceState.temperatureState) {
                            TemperatureState.NORMAL -> SafeGreen
                            TemperatureState.WARNING -> WarningOrange
                            TemperatureState.CONFIRMING -> ErrorRed
                            TemperatureState.LOCKED_COOLDOWN -> InfoBlue
                        }
                    )
                    Text(
                        text = "电池温控与热状态",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (serviceState.temperatureState) {
                        TemperatureState.NORMAL -> SafeGreen.copy(alpha = 0.2f)
                        TemperatureState.WARNING -> WarningOrange.copy(alpha = 0.2f)
                        TemperatureState.CONFIRMING -> ErrorRed.copy(alpha = 0.2f)
                        TemperatureState.LOCKED_COOLDOWN -> InfoBlue.copy(alpha = 0.2f)
                    }
                ) {
                    Text(
                        text = serviceState.temperatureState.displayName,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "实时电池温度", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = serviceState.currentTempTenthsC?.let { "${it / 10.0}°C" } ?: "未知",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "供电状态", style = MaterialTheme.typography.bodySmall)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = if (serviceState.isPlugged) Icons.Default.Power else Icons.Default.BatteryStd,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (serviceState.isPlugged) WarningOrange else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (serviceState.isPlugged) "充电中" else "未充电",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "生效阈值: ${serviceState.effectiveLockThresholdTenthsC / 10.0}°C",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
                Text(
                    text = "系统热状态: ${serviceState.thermalLevel.displayName}",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                )
            }

            if (serviceState.remainingCooldownMs > 0) {
                Text(
                    text = "处于高温锁屏冷却期中，剩余: ${serviceState.remainingCooldownMs / 1000} 秒 (已连续锁屏 ${serviceState.consecutiveLockCount} 次)",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    color = InfoBlue
                )
            }
        }
    }
}

@Composable
private fun ServiceControlCard(
    isAdminActive: Boolean,
    isNotificationPermGranted: Boolean,
    serviceState: GuardianServiceState,
    onRequestAdminPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onPauseService: () -> Unit,
    onResumeService: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "守护服务状态",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = serviceState.status.displayName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = when (serviceState.status) {
                                ServiceStatus.RUNNING -> SafeGreen
                                ServiceStatus.PAUSED -> WarningOrange
                                ServiceStatus.ERROR -> ErrorRed
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                if (serviceState.status == ServiceStatus.RUNNING || serviceState.status == ServiceStatus.PAUSED) {
                    FilledTonalButton(
                        onClick = onStopService,
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text("停止守护", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                } else {
                    Button(
                        onClick = {
                            if (!isAdminActive) {
                                onRequestAdminPermission()
                            } else {
                                onStartService()
                            }
                        }
                    ) {
                        Text(if (!isAdminActive) "先授权管理员" else "开启守护")
                    }
                }
            }

            if (serviceState.status == ServiceStatus.RUNNING) {
                OutlinedButton(
                    onClick = onPauseService,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("临时暂停 15 分钟")
                }
            } else if (serviceState.status == ServiceStatus.PAUSED) {
                Button(
                    onClick = onResumeService,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("立即恢复守护")
                }
            }

            if (!isNotificationPermGranted) {
                TextButton(
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = WarningOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("建议授予通知权限以保证前台服务稳定常驻", color = WarningOrange, fontSize = 12.sp)
                }
            }

            if (serviceState.errorMessage != null) {
                Text(
                    text = "错误提示: ${serviceState.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed
                )
            }
        }
    }
}

@Composable
private fun ScreenTimingCard(serviceState: GuardianServiceState) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (serviceState.isInteractive) Icons.Default.BrightnessHigh else Icons.Default.BrightnessMedium,
                    contentDescription = null,
                    tint = if (serviceState.isInteractive) SafeGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "屏幕交互状态: " + if (serviceState.isInteractive) "屏幕已点亮 (交互中)" else "屏幕已熄灭 (非交互)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }

            val elapsedSec = serviceState.interactiveDurationMs / 1000
            val elapsedMin = elapsedSec / 60
            val elapsedSecRem = elapsedSec % 60

            val remainingSec = serviceState.remainingTimeoutMs / 1000
            val remainingMin = remainingSec / 60
            val remainingSecRem = remainingSec % 60

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "本次连续亮屏", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = String.format("%02d分%02d秒", elapsedMin, elapsedSecRem),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "距离超时锁屏剩余", style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = String.format("%02d分%02d秒", remainingMin, remainingSecRem),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (remainingMin < 5 && serviceState.isInteractive) WarningOrange else MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TestAndRevokeCard(
    isAdminActive: Boolean,
    onShowTestLockDialog: () -> Unit,
    onShowRevokeDialog: () -> Unit,
    lastResultMsg: String?
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "锁屏测试与授权退出",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onShowTestLockDialog,
                    enabled = isAdminActive,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("测试锁屏")
                }
                OutlinedButton(
                    onClick = onShowRevokeDialog,
                    enabled = isAdminActive,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("撤销管理员")
                }
            }

            if (lastResultMsg != null) {
                Text(
                    text = "操作结果: $lastResultMsg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun LogItemRow(log: LogEntry) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (log.isWarningOrError) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = log.formattedTime,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (log.isWarningOrError) ErrorRed.copy(alpha = 0.15f) else InfoBlue.copy(alpha = 0.15f)
            ) {
                Text(
                    text = log.eventType,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (log.isWarningOrError) ErrorRed else InfoBlue,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
            Text(
                text = log.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
