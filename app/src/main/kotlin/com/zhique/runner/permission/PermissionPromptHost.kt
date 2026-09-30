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
 * 文案（审查修复 #6）：系统权限申请在授权流程内一并完成（点授予即弹系统
 * 确认框），不引导「去系统设置」为唯一路径。
 */
@Composable
fun PermissionPromptHost(prompt: AppPermissionPrompt) {
    val pending by prompt.current.collectAsState()
    val p = pending ?: return
    val cap = Capability.fromId(p.ask.capability)
    val needsSystem = !cap?.manifestPermissions.isNullOrEmpty()
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
                    if (needsSystem) {
                        "点「授予」后会立即弹出系统权限确认，一并允许即可使用；" +
                            "如系统权限被拒，可重试授权或在系统设置中开启。" +
                            "你随时可在「设置 → 权限中心」更改或吊销。"
                    } else {
                        "你随时可在「设置 → 权限中心」更改或吊销。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (needsSystem) Modifier.testTag("perm-card-system-hint") else Modifier,
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
