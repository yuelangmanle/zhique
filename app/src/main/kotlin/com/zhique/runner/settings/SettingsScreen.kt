package com.zhique.runner.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.components.AuroraBackground
import com.zhique.runner.ui.components.AuroraDomain

/**
 * 设置根屏（规格 §7 全集，M9 逐项落地）：AI 服务商族（对话/服务商/路由/输出思考上下文/
 * Token 统计）/ 权限中心 / 发布与同步 / 通用 / 隐私与安全 / 关于 / 开发者。
 * 「导出与签名」经导出中心（底部 Tab）承载。管理域晨光 Aurora 底。
 */
@Composable
fun SettingsScreen(
    onOpenChat: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenRoleRouter: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenOutputContext: () -> Unit = {},
    onOpenTokenStats: () -> Unit = {},
    onOpenPermissionCenter: () -> Unit = {},
    onOpenPublishSync: () -> Unit = {},
    onOpenGeneral: () -> Unit = {},
    onOpenPrivacy: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenDeveloper: () -> Unit = {},
) {
    AuroraBackground(modifier.fillMaxSize(), domain = AuroraDomain.LIGHT) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 20.dp)
                .testTag("settings-screen"),
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SettingGroup("AI 服务商") {
                SettingRow("对话", "AI 对话面板（思考折叠 · 续写 · 用量）", "settings-chat", onOpenChat)
                SettingRow("AI 服务商", "多协议接入 · 密钥加密存储", "settings-providers", onOpenProviders)
                SettingRow("角色路由", "五槽模型分工 · 省钱/均衡/质量", "settings-router", onOpenRoleRouter)
                SettingRow("输出 · 思考 · 上下文", "输出上限 · 续写段数 · 预算占比 · 压缩阈值", "settings-output-context", onOpenOutputContext)
                SettingRow("Token 用量统计", "按服务商 / 按项目", "settings-token-stats", onOpenTokenStats)
            }
            SettingGroup("项目与产出") {
                SettingRow("权限中心", "项目×能力矩阵 · 运行中提醒 · 导出权限建议", "settings-permissions", onOpenPermissionCenter)
                SettingRow("发布与同步", "GitHub PAT · 推送偏好 · 自更新通道", "settings-publish", onOpenPublishSync)
                SettingRow("关于织雀", "版本 · 检查更新 · 更新日志 · Apache-2.0", "settings-about", onOpenAbout)
            }
            SettingGroup("偏好") {
                SettingRow("通用", "外观 · 运行器 · 智能粘贴 · 编辑器 · Web · 通知", "settings-general", onOpenGeneral)
                SettingRow("隐私与安全", "应用锁 · 审计日志 · 隐私告知记录", "settings-privacy", onOpenPrivacy)
                SettingRow("开发者", "调试开关 · 日志导出 · zq.* 协议文档 · 意图测试器", "settings-developer", onOpenDeveloper)
            }
        }
    }
}

/** 分组标题（M9 §7 信息架构：三段折叠分组）。 */
@Composable
private fun SettingGroup(title: String, content: @Composable () -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
    content()
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
