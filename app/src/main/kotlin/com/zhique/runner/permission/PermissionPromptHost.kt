package com.zhique.runner.permission

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.permission.Capability

/**
 * 原生授权卡宿主（规格 §4.6）：哪个项目、要什么、干什么 + 授予/拒绝。
 * 挂在应用根；矩阵拒绝不崩溃，网页按 {"code":"denied"} 优雅降级。
 */
@Composable
fun PermissionPromptHost(prompt: AppPermissionPrompt) {
    val pending by prompt.current.collectAsState()
    val p = pending ?: return
    val cap = Capability.fromId(p.ask.capability)
    AlertDialog(
        onDismissRequest = { prompt.answer(false) },
        title = {
            Text(
                "「${p.ask.projectName}」想使用${cap?.title ?: p.ask.capability}",
                modifier = Modifier.testTag("perm-card-title"),
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    p.ask.why,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    "你可以随时在「设置 → 权限中心」更改或吊销。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { prompt.answer(true) },
                modifier = Modifier.testTag("perm-grant"),
            ) { Text("授予") }
        },
        dismissButton = {
            TextButton(
                onClick = { prompt.answer(false) },
                modifier = Modifier.testTag("perm-deny"),
            ) { Text("拒绝") }
        },
        modifier = Modifier.testTag("perm-card"),
    )
}
