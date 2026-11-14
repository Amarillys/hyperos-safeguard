package com.example.thermalguard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.thermalguard.data.IdleDimmingState

@Composable
fun IdleDimmingCard(
    state: IdleDimmingState,
    guardianRunning: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onRequestWriteSettings: () -> Unit,
    onCopyAdbCommand: () -> Unit,
    onRefreshPermissions: () -> Unit,
    onPreview: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "20 分钟无操作调暗", Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
                )
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                if (!state.enabled) "已关闭" else state.status.displayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "无触摸、滑动或按键时降至原亮度的 20%，操作后约 5 秒内恢复。以系统用户活动时间为准。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "暂停、停止守护或熄屏时恢复亮度。系统先熄屏时不会唤醒；原有自动锁屏规则仍生效。",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.enabled) {
                if (!state.hasDumpPermission) {
                    Text("需通过电脑 ADB 授权读取电源诊断；该权限也能读取其他系统诊断。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onCopyAdbCommand) { Text("复制 ADB 授权命令") }
                }
                if (!state.canWriteSettings) {
                    TextButton(onClick = onRequestWriteSettings) { Text("允许修改系统设置") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPreview,
                        enabled = guardianRunning && state.hasDumpPermission && state.canWriteSettings && !state.dimmed
                    ) { Text("试用调暗 10 秒") }
                    TextButton(onClick = onRefreshPermissions) { Text("重新检查") }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            state.lastMessage?.let { Text("最近：$it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
