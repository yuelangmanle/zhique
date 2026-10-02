package com.zhique.runner.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhique.core.publish.ReleaseInfo
import com.zhique.runner.export.ExportDelivery

/**
 * 关于织雀（规格 §5.3 屏 13 / §7）：版本号、检查更新（GitHub Releases，
 * stable/beta 通道）、更新日志、开源仓库、Apache-2.0。发现新版本→下载（进度）→
 * PackageInstaller 更新流（同签名约束天然满足，决策29）。
 */
@Composable
fun AboutScreen(
    controller: AboutController,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("about-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("关于织雀", style = MaterialTheme.typography.titleMedium)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text("织雀 Zhique", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "在手机上 vibe coding 的网页应用工厂",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "版本 ${state.version}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("about-version"),
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = controller::checkUpdate,
                    enabled = !state.checking,
                    modifier = Modifier.fillMaxWidth().testTag("about-check"),
                ) {
                    Text(if (state.checking) "正在检查…" else "检查更新（${state.channel}）")
                }
                state.updateMessage?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.update == null) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp).testTag("about-update-message"),
                    )
                }
                state.update?.let { update ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("about-update-card"),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "新版本 ${update.tagName}${if (update.prerelease) "（beta）" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("about-update-tag"),
                            )
                            if (update.notes.isNotBlank()) {
                                Text(
                                    update.notes.take(400),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (state.downloading) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { state.progress },
                                    modifier = Modifier.fillMaxWidth().testTag("about-download-progress"),
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            when {
                                state.downloadedApk != null -> Button(
                                    onClick = {
                                        runCatching { ExportDelivery.installApk(context, state.downloadedApk!!) }
                                            .onFailure { onToast("安装失败：${it.message}") }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("about-install"),
                                ) { Text("安装更新") }
                                !state.downloading -> OutlinedButton(
                                    onClick = controller::downloadUpdate,
                                    modifier = Modifier.fillMaxWidth().testTag("about-download"),
                                ) { Text("下载并安装") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("更新日志", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                // 内置日志优先展示（迭代纪律：每次交付都更新，无网可查）
                val local = state.localChangelog
                if (!local.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("about-changelog-local"),
                    ) {
                        Text(
                            local.trim(),
                            style = MaterialTheme.typography.bodySmall,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (state.changelogs.isEmpty() && local.isNullOrBlank()) {
                    Text(
                        "暂无发布记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("about-changelog-empty"),
                    )
                }
                state.changelogs.forEach { release ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("about-changelog-item"),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${release.tagName}${if (release.preRelease) " · beta" else ""}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            (release.body ?: "").take(200).let {
                                if (it.isNotBlank()) {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("开源与许可", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "开源仓库：github.com/${controller.repoFullName}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("about-repo"),
                )
                Text(
                    "许可证：Apache License 2.0",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp).testTag("about-license"),
                )
            }
        }
    }
}
