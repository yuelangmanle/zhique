package com.zhique.runner.publish

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.publish.PatStore
import com.zhique.runner.settings.PublishPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「发布与同步」设置页（规格 §7：GitHub 账号 PAT / 自更新通道），
 * 兼 PAT 首次引导页（Task 7.1）：说明仅 repo 权限 + 官方创建链接 + 加密存储口径。
 */
@Composable
fun PublishSyncScreen(
    patStore: PatStore,
    prefs: PublishPreferences,
    onBack: () -> Unit,
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var patInput by remember { mutableStateOf("") }
    var patStatus by remember { mutableStateOf("检查中…") }
    var hasPat by remember { mutableStateOf(false) }
    var channel by remember { mutableStateOf(PublishPreferences.CHANNEL_STABLE) }
    var commitLang by remember { mutableStateOf(PublishPreferences.COMMIT_ZH) }
    var defaultBranch by remember { mutableStateOf(PublishPreferences.DEFAULT_BRANCH) }
    var newRepoPrivate by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        hasPat = patStore.hasPat()
        patStatus = if (hasPat) "已配置（加密存储于本机）" else "未配置"
        channel = prefs.channelNow()
        commitLang = prefs.commitLanguage.first()
        defaultBranch = prefs.defaultBranch.first()
        newRepoPrivate = prefs.newRepoPrivate.first()
    }

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("publishsync-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("发布与同步", style = MaterialTheme.typography.titleMedium)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text("GitHub 访问令牌（PAT）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("pat-guide-card"),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(PatStore.GUIDE_SCOPES, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            PatStore.GUIDE_URL,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    patStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("pat-status"),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = patInput,
                    onValueChange = { patInput = it },
                    label = { Text(if (hasPat) "输入新令牌以替换" else "粘贴 fine-grained PAT") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().testTag("pat-input"),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    patStore.save(patInput.trim())
                                    patInput = ""
                                    hasPat = true
                                    patStatus = "已配置（加密存储于本机）"
                                }.onSuccess { onToast("PAT 已加密保存") }
                                    .onFailure { onToast("保存失败：${it.message}") }
                            }
                        },
                        enabled = patInput.isNotBlank(),
                        modifier = Modifier.testTag("pat-save"),
                    ) { Text("保存") }
                    Spacer(Modifier.width(8.dp))
                    if (hasPat) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    patStore.clear()
                                    hasPat = false
                                    patStatus = "未配置"
                                    onToast("已清除 PAT")
                                }
                            },
                            modifier = Modifier.testTag("pat-clear"),
                        ) { Text("清除") }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("自更新通道", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "stable=正式版；beta=包含预发布（prerelease）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("channel-picker")) {
                    RadioButton(
                        selected = channel == PublishPreferences.CHANNEL_STABLE,
                        onClick = {
                            channel = PublishPreferences.CHANNEL_STABLE
                            scope.launch { prefs.setChannel(channel) }
                        },
                        modifier = Modifier.testTag("channel-stable"),
                    )
                    Text("stable")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = channel == PublishPreferences.CHANNEL_BETA,
                        onClick = {
                            channel = PublishPreferences.CHANNEL_BETA
                            scope.launch { prefs.setChannel(channel) }
                        },
                        modifier = Modifier.testTag("channel-beta"),
                    )
                    Text("beta")
                }

                // ---- M9 补齐：推送偏好（commit 语言 / 默认分支 / 新仓默认公私） ----
                Spacer(Modifier.height(24.dp))
                Text("推送偏好", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "commit 语言影响 AI 生成的提交信息；默认分支与新仓可见性用于首次发布建仓。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("commit-lang-picker")) {
                    RadioButton(
                        selected = commitLang == PublishPreferences.COMMIT_ZH,
                        onClick = {
                            commitLang = PublishPreferences.COMMIT_ZH
                            scope.launch { prefs.setCommitLanguage(commitLang) }
                        },
                        modifier = Modifier.testTag("commit-lang-zh"),
                    )
                    Text("中文")
                    Spacer(Modifier.width(16.dp))
                    RadioButton(
                        selected = commitLang == PublishPreferences.COMMIT_EN,
                        onClick = {
                            commitLang = PublishPreferences.COMMIT_EN
                            scope.launch { prefs.setCommitLanguage(commitLang) }
                        },
                        modifier = Modifier.testTag("commit-lang-en"),
                    )
                    Text("English")
                }
                OutlinedTextField(
                    value = defaultBranch,
                    onValueChange = { defaultBranch = it },
                    label = { Text("默认分支") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("default-branch-input"),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("new-repo-private-row"),
                ) {
                    Text("新仓库默认私有", Modifier.weight(1f))
                    androidx.compose.material3.Switch(
                        checked = newRepoPrivate,
                        onCheckedChange = {
                            newRepoPrivate = it
                            scope.launch { prefs.setNewRepoPrivate(it) }
                        },
                        modifier = Modifier.testTag("new-repo-private-switch"),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        scope.launch {
                            runCatching { prefs.setDefaultBranch(defaultBranch.trim()) }
                                .onSuccess { onToast("推送偏好已保存") }
                                .onFailure { onToast("保存失败：${it.message}") }
                        }
                    },
                    enabled = defaultBranch.isNotBlank(),
                    modifier = Modifier.testTag("push-prefs-save"),
                ) { Text("保存推送偏好") }
            }
        }
    }
}
