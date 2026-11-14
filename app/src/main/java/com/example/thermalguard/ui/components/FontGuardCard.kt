package com.example.thermalguard.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.thermalguard.data.FontGuardState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FontGuardCard(state: FontGuardState, onEnabledChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "字体防回退（实验）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Switch(checked = state.enabled, onCheckedChange = onEnabledChange)
            }
            Text(
                when {
                    !state.enabled -> "已关闭"
                    state.registrationError != null -> "监听失败：${state.registrationError}"
                    state.listening -> "监听中 · 已收到 ${state.receivedCount} 次校验广播"
                    else -> "已开启 · 等待守护服务运行"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.registrationError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
            )
            Text(
                "与锁屏守护共用后台。暂停锁屏时继续监听，停止守护后停止。系统版本可能影响效果。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "开启后需手动重新应用想用的字体，本功能不会自动换字体。",
                style = MaterialTheme.typography.bodySmall
            )
            state.lastEvent?.let { event ->
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(event.timestampMs))
                Text(
                    "最近${if (event.isSelfTest) "自检" else "一次"}：$time · ${event.result.outcome.displayName}" +
                        (event.result.errorMessage?.let { "\n$it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
