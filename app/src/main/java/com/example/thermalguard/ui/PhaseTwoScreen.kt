package com.example.thermalguard.ui

import androidx.compose.foundation.background
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
import com.example.thermalguard.data.LogEntry
import com.example.thermalguard.rule.LockResult
import com.example.thermalguard.service.GuardianServiceState
import com.example.thermalguard.service.ServiceStatus
import com.example.thermalguard.ui.theme.ErrorRed
import com.example.thermalguard.ui.theme.InfoBlue
import com.example.thermalguard.ui.theme.SafeGreen
import com.example.thermalguard.ui.theme.WarningOrange

/**
 * 阶段二界面：前台守护控制、亮屏实时计时、事件日志与测试管理
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhaseTwoScreen(
    isAdminActive: Boolean,
    isNotificationPermGranted: Boolean,
    serviceState: GuardianServiceState,
    logList: List<LogEntry>,
    onRequestAdminPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onPauseService: () -> Unit,
    onResumeService: () -> Unit,
    onClearLogs: () -> Unit,
    onTestLock: () -> LockResult,
    onRevokeAdminPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showTestLockConfirmDialog by remember { mutableStateOf(false) }
    var showRevokeConfirmDialog by remember { mutableStateOf(false) }
    var lastLockResultMsg by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "温控亮屏守护 (Phase 2)",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
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

            // 1. 服务与守护总开关卡片
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

            // 2. 亮屏交互与倒计时卡片
            item {
                ScreenTimingCard(
                    serviceState = serviceState
                )
            }

            // 3. 手动测试锁屏与权限退出卡片
            item {
                TestAndRevokeCard(
                    isAdminActive = isAdminActive,
                    onShowTestLockDialog = { showTestLockConfirmDialog = true },
                    onShowRevokeDialog = { showRevokeConfirmDialog = true },
                    lastResultMsg = lastLockResultMsg
                )
            }

            // 4. 事件日志标题与清空按钮
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
                    if (logList.isNotEmpty()) {
                        TextButton(onClick = onClearLogs) {
                            Text("清空日志")
                        }
                    }
                }
            }

            // 5. 日志条目列表
            if (logList.isEmpty()) {
                item {
                    Text(
                        text = "暂无事件日志，开启守护或亮灭屏后将在此显示",
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

        // 弹窗部分
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

                // 主控制开关
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

            // 暂停/恢复快捷按钮
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

            // 权限警示与引导
            if (!isNotificationPermGranted) {
                TextButton(
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Notifications, contentDescription = null, tint = WarningOrange)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("通知权限未授予（建议开启以保证前台服务稳定常驻）", color = WarningOrange, fontSize = 12.sp)
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

            Text(
                text = "上限设定: 连续交互超过 ${serviceState.maxScreenOnMinutes} 分钟将自动锁屏；手动熄屏立即重新从零起计。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
            )
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
