package com.example.thermalguard.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.thermalguard.data.GuardianSettings
import com.example.thermalguard.ui.theme.ErrorRed

/**
 * 守护规则参数修改对话框
 */
@Composable
fun SettingsEditDialog(
    currentSettings: GuardianSettings,
    onDismiss: () -> Unit,
    onSave: (GuardianSettings) -> Unit
) {
    var warningTemp by remember { mutableStateOf((currentSettings.warningTempTenthsC / 10.0).toString()) }
    var lockTemp by remember { mutableStateOf((currentSettings.lockTempTenthsC / 10.0).toString()) }
    var chargingLockTemp by remember { mutableStateOf((currentSettings.chargingLockTempTenthsC / 10.0).toString()) }
    var recoveryTemp by remember { mutableStateOf((currentSettings.recoveryTempTenthsC / 10.0).toString()) }
    var antiShakeSec by remember { mutableStateOf(currentSettings.antiShakeSeconds.toString()) }
    var maxScreenOnMin by remember { mutableStateOf(currentSettings.maxScreenOnMinutes.toString()) }
    var thermalLinkage by remember { mutableStateOf(currentSettings.isThermalLinkageEnabled) }

    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("守护规则参数配置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = warningTemp,
                    onValueChange = { warningTemp = it },
                    label = { Text("预警温度 (°C)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = lockTemp,
                    onValueChange = { lockTemp = it },
                    label = { Text("未充电锁屏温度 (°C)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = chargingLockTemp,
                    onValueChange = { chargingLockTemp = it },
                    label = { Text("充电中锁屏温度 (°C)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = recoveryTemp,
                    onValueChange = { recoveryTemp = it },
                    label = { Text("恢复阈值温度 (°C)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = antiShakeSec,
                    onValueChange = { antiShakeSec = it },
                    label = { Text("抗抖秒数 (5~60秒)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxScreenOnMin,
                    onValueChange = { maxScreenOnMin = it },
                    label = { Text("连续亮屏上限 (1~240分钟)") },
                    singleLine = true
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("系统热状态联动 (CRITICAL锁屏)")
                    Switch(checked = thermalLinkage, onCheckedChange = { thermalLinkage = it })
                }
                if (errorMessage != null) {
                    Text(text = errorMessage!!, color = ErrorRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    try {
                        val w = (warningTemp.toDouble() * 10).toInt()
                        val l = (lockTemp.toDouble() * 10).toInt()
                        val cl = (chargingLockTemp.toDouble() * 10).toInt()
                        val r = (recoveryTemp.toDouble() * 10).toInt()
                        val a = antiShakeSec.toInt()
                        val m = maxScreenOnMin.toInt()

                        val candidate = GuardianSettings(
                            warningTempTenthsC = w,
                            lockTempTenthsC = l,
                            chargingLockTempTenthsC = cl,
                            recoveryTempTenthsC = r,
                            antiShakeSeconds = a,
                            maxScreenOnMinutes = m,
                            isThermalLinkageEnabled = thermalLinkage
                        )
                        val validation = candidate.validate()
                        if (validation.isValid) {
                            onSave(candidate)
                        } else {
                            errorMessage = (validation as com.example.thermalguard.data.ValidationResult.Invalid).reason
                        }
                    } catch (e: Exception) {
                        errorMessage = "输入格式错误，请输入合法数字"
                    }
                }
            ) {
                Text("保存设置")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}
