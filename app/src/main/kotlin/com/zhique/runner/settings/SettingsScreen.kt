package com.zhique.runner.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * 设置根屏（规格 §5.3 底部 Tab 3 的 M4 版）：M3 三屏入口 + M5 权限中心 + 后续里程碑占位行。
 * 全集（导出与签名/发布与同步/通用/隐私）按 §7 里程碑推进逐步接入。
 */
@Composable
fun SettingsScreen(
    onOpenChat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenRoleRouter: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPermissionCenter: () -> Unit = {},
    onOpenPublishSync: () -> Unit = {},
) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.padding(top = 20.dp).testTag("settings-screen")) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SettingRow("对话", "AI 对话面板（思考折叠 · 续写 · 用量）", "settings-chat", onOpenChat)
            SettingRow("AI 服务商", "多协议接入 · 密钥加密存储", "settings-providers", onOpenProviders)
            SettingRow("角色路由", "五槽模型分工 · 省钱/均衡/质量", "settings-router", onOpenRoleRouter)
            SettingRow("权限中心", "项目×能力矩阵 · 运行中提醒 · 导出权限建议", "settings-permissions", onOpenPermissionCenter)
            SettingRow("发布与同步", "GitHub PAT · 自更新通道", "settings-publish", onOpenPublishSync)
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}
