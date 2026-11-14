package com.example.thermalguard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.thermalguard.rule.LockResult
import com.example.thermalguard.ui.theme.ErrorRed
import com.example.thermalguard.ui.theme.SafeGreen
import com.example.thermalguard.ui.theme.WarningOrange

/**
 * 阶段一界面：设备管理员授权管理与手动锁屏测试
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhaseOneScreen(
    isAdminActive: Boolean,
    onRequestAdminPermission: () -> Unit,
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
                        text = "温控亮屏守护 (Phase 1)",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // 1. 设备管理员状态卡片
            AdminStatusCard(isAdminActive = isAdminActive)

            // 2. 授权指引与操作
            AdminActionCard(
                isAdminActive = isAdminActive,
                onRequestAdminPermission = onRequestAdminPermission,
                onShowRevokeDialog = { showRevokeConfirmDialog = true }
            )

            // 3. 手动测试锁屏卡片
            TestLockCard(
                isAdminActive = isAdminActive,
                onShowTestLockDialog = { showTestLockConfirmDialog = true },
                lastResultMsg = lastLockResultMsg
            )

            // 4. 重要 UX 与卸载说明卡片
            ImportantNoticeCard()

            Spacer(modifier = Modifier.height(16.dp))
        }

        // 测试锁屏确认弹窗
        if (showTestLockConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showTestLockConfirmDialog = false },
                icon = { Icon(Icons.Default.Lock, contentDescription = "锁屏确认") },
                title = { Text("确认立即锁屏？") },
                text = {
                    Text("调用系统的 lockNow() 后屏幕会立即锁定。\n\n由于系统安全机制限制，锁屏后通常需要输入锁屏密码 (PIN/图案) 才能解锁，指纹或面部识别可能暂时不可用。")
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
                                is LockResult.RejectedMinInterval -> "执行被拒绝：距离上次锁屏不足5秒 (防抖冷却剩余 ${result.remainingMs}ms)"
                                is LockResult.RejectedCooldown -> "执行被拒绝：处于高温冷却期 (剩余 ${result.remainingMs}ms)"
                                is LockResult.RejectedPaused -> "执行被拒绝：守护处于暂停状态"
                                is LockResult.RejectedGuardianDisabled -> "执行被拒绝：守护总开关已关闭"
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

        // 取消管理员授权确认弹窗
        if (showRevokeConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showRevokeConfirmDialog = false },
                icon = { Icon(Icons.Default.Warning, contentDescription = "取消授权确认", tint = ErrorRed) },
                title = { Text("确认取消设备管理员授权？") },
                text = {
                    Text("取消设备管理员后，应用将无法在高温或超时时执行强制锁屏。如需卸载应用，可以在取消授权后直接卸载。")
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        onClick = {
                            showRevokeConfirmDialog = false
                            onRevokeAdminPermission()
                            lastLockResultMsg = "已撤销设备管理员授权"
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
private fun AdminStatusCard(isAdminActive: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAdminActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isAdminActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isAdminActive) SafeGreen else ErrorRed,
                modifier = Modifier.size(32.dp)
            )
            Column {
                Text(
                    text = if (isAdminActive) "设备管理员已激活" else "设备管理员未激活",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = if (isAdminActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = if (isAdminActive) "已具备 force-lock 安全锁屏权限" else "需要激活后才能支持高温/超时锁屏守护",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isAdminActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun AdminActionCard(
    isAdminActive: Boolean,
    onRequestAdminPermission: () -> Unit,
    onShowRevokeDialog: () -> Unit
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
                text = "权限管理",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "本项目遵循无侵入原则，不使用 Root、Shizuku 或无障碍权限，仅使用官方标准的设备管理员策略来实现强制锁屏保护。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!isAdminActive) {
                Button(
                    onClick = onRequestAdminPermission,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("前往激活设备管理员")
                }
            } else {
                OutlinedButton(
                    onClick = onShowRevokeDialog,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("停止守护并取消设备管理员")
                }
            }
        }
    }
}

@Composable
private fun TestLockCard(
    isAdminActive: Boolean,
    onShowTestLockDialog: () -> Unit,
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
                text = "锁屏功能测试",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "点击下方按钮可立即发起一次受控的手动测试锁屏。此测试严格通过统一 LockCoordinator 守卫，具备防抖（5秒最小间隔）与状态校验。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onShowTestLockDialog,
                enabled = isAdminActive,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("立即测试锁屏 (需二次确认)")
            }

            if (!isAdminActive) {
                Text(
                    text = "提示：请先激活上方设备管理员权限后再进行测试",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningOrange
                )
            }

            if (lastResultMsg != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = "最近操作结果: $lastResultMsg",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ImportantNoticeCard() {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "重要安全与体验说明",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "1. 锁屏密码提醒：DevicePolicyManager.lockNow() 之后，Android 系统出于安全考量会强制要求 PIN/密码/图案 解锁，生物识别（指纹/人脸）可能暂时不可用。\n\n" +
                            "2. 安全卸载方式：若需要卸载本应用，请先在上方点击「停止守护并取消设备管理员」，或者前往系统「设置 - 隐私与安全 - 设备管理应用」中取消激活后再卸载。",
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}
