package com.zhique.runner.publish

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.publish.ReleaseStage

/**
 * 发布向导（规格 §5.3 屏 12 / §4.8）：
 * - 日常 2 步：①变更检查 + AI commit message（可改）②确认推送 → 进度 → 完成；
 * - 首次 4 步：①包信息 ②选/建仓库（预填名称/公私/README/LICENSE）③确认 ④推送；
 * - 断点续跑横幅（「进行到 X，继续？」）、失败重试、取消（≠失败）。
 */
@Composable
fun PublishWizardScreen(
    controller: PublishController,
    onDone: () -> Unit,
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onOpenPatGuide: () -> Unit = {},
) {
    val state by controller.state.collectAsState()

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (state.done == null) controller.back() else onDone() },
                        modifier = Modifier.testTag("publish-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("发布", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "第 ${state.step + 1}/${state.totalSteps} 步",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp).testTag("publish-step"),
                    )
                }
            }

            // 断点续跑横幅（规格 §4.8「重进提示进行到 X，继续？」）
            state.resumeStage?.let { stage ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("publish-resume-banner"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (state.resumeCanceled) "上次发布已取消（停在 ${stageLabel(stage)}），可恢复"
                                else "上次发布进行到「${stageLabel(stage)}」，继续？",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.testTag("publish-resume-text"),
                            )
                        }
                        OutlinedButton(
                            onClick = controller::dismissResume,
                            modifier = Modifier.testTag("publish-resume-dismiss"),
                        ) { Text("暂不") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = controller::resumeLast,
                            modifier = Modifier.testTag("publish-resume-continue"),
                        ) { Text("继续") }
                    }
                }
            }

            when {
                state.done != null -> DoneStep(
                    state = state,
                    onFinish = onDone,
                )
                state.running -> ProgressStep(
                    state = state,
                    onCancel = controller::cancel,
                )
                state.step == 0 -> Step0(controller, state, onOpenPatGuide)
                state.step == 1 && state.firstTime -> RepoStep(controller, state, onOpenPatGuide)
                state.step >= 1 -> ConfirmStep(controller, state)
            }
        }
    }
}

@Composable
private fun Step0(controller: PublishController, state: PublishUiState, onOpenPatGuide: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        if (state.firstTime) {
            Text("包信息", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "将发布项目「${state.projectName}」到 GitHub；仓库名等下一步可调。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!state.hasPat) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("publish-pat-missing"),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "还没配置 GitHub 访问令牌（PAT），推送前需要一次引导。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = onOpenPatGuide, modifier = Modifier.testTag("publish-pat-open")) {
                            Text("去配置")
                        }
                    }
                }
            }
        } else {
            Text("变更检查", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val status = state.status
            if (status == null || status.clean) {
                Text(
                    "没有检测到未提交的变更——仍可推送以同步远端。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("publish-status"),
                )
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("publish-status"),
                ) {
                    Text(
                        status.summary(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("提交说明（AI 生成，可修改）", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = state.commitMessage,
                onValueChange = controller::setCommitMessage,
                label = { Text("commit message") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("publish-commit-input"),
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = controller::next,
            enabled = state.canGoNext,
            modifier = Modifier.fillMaxWidth().testTag("publish-next"),
        ) { Text("下一步") }
    }
}

/** 首次步②：选/建仓库（预填名称、公私、README/LICENSE）。 */
@Composable
private fun RepoStep(controller: PublishController, state: PublishUiState, onOpenPatGuide: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("选择 / 创建仓库", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "将新建远端仓库（以本地项目为准，不会带入模板文件）；已有仓库请改在 GitHub 侧合并。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.repoName,
            onValueChange = controller::setRepoName,
            label = { Text("仓库名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("publish-repo-name"),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("publish-visibility")) {
            RadioButton(
                selected = state.repoPrivate,
                onClick = { controller.setRepoPrivate(true) },
                modifier = Modifier.testTag("publish-private"),
            )
            Text("私有")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = !state.repoPrivate,
                onClick = { controller.setRepoPrivate(false) },
                modifier = Modifier.testTag("publish-public"),
            )
            Text("公开")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.readme, onCheckedChange = controller::setReadme,
                modifier = Modifier.testTag("publish-readme"))
            Spacer(Modifier.width(8.dp))
            Text("生成 README.md")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.license, onCheckedChange = controller::setLicense,
                modifier = Modifier.testTag("publish-license"))
            Spacer(Modifier.width(8.dp))
            Text("生成 LICENSE（Apache-2.0）")
        }
        if (!state.hasPat) {
            Spacer(Modifier.height(8.dp))
            Text(
                "提示：尚未配置 PAT，创建仓库前需要先完成引导。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("publish-pat-hint"),
            )
            OutlinedButton(onClick = onOpenPatGuide, modifier = Modifier.testTag("publish-pat-open-2")) {
                Text("去配置 PAT")
            }
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = controller::next,
            enabled = state.canGoNext,
            modifier = Modifier.fillMaxWidth().testTag("publish-next"),
        ) { Text("下一步") }
    }
}

/** 确认步（日常步② / 首次步③）：参数全预览，一触确认。 */
@Composable
private fun ConfirmStep(controller: PublishController, state: PublishUiState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text("确认推送", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val binding = state.binding
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("publish-confirm-card"),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    if (binding != null) "${binding.owner}/${binding.repo}" else "${state.repoName}（新建）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.testTag("publish-confirm-repo"),
                )
                Text(
                    "分支 ${binding?.branch ?: "main"} · ${if (binding != null) "已有仓库" else if (state.repoPrivate) "私有" else "公开"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            state.commitMessage,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("publish-confirm-message"),
        )
        state.error?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                "上次失败：$it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("publish-error"),
            )
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = controller::confirmPush,
            modifier = Modifier.fillMaxWidth().testTag("publish-push"),
        ) { Text("确认推送") }
        state.error?.let {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = controller::confirmPush, modifier = Modifier.fillMaxWidth()
                .testTag("publish-retry")) { Text("重试") }
        }
    }
}

/** 推送进度：阶段时间线 + 取消（取消≠失败）。 */
@Composable
private fun ProgressStep(state: PublishUiState, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(Modifier.size(48.dp).testTag("publish-progress"))
        Spacer(Modifier.height(16.dp))
        Text(
            "正在发布：${stageLabel(state.stage ?: ReleaseStage.PLANNED)}",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("publish-progress-text"),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onCancel, modifier = Modifier.testTag("publish-cancel")) { Text("取消") }
    }
}

/** 完成：sha / 仓库 / tag 证据 + 收尾。 */
@Composable
private fun DoneStep(state: PublishUiState, onFinish: () -> Unit) {
    val job = state.done!!
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("发布完成", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("publish-done"))
        Spacer(Modifier.height(8.dp))
        Text(
            buildString {
                append(stageLabel(job.stage))
                job.commitSha?.let { append(" · ${it.take(7)}") }
                job.tag?.let { append(" · $it") }
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("publish-done-detail"),
        )
        Text(
            state.binding?.let { "${it.owner}/${it.repo}" } ?: "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onFinish, modifier = Modifier.fillMaxWidth().testTag("publish-finish")) { Text("完成") }
    }
}

internal fun stageLabel(stage: ReleaseStage): String = when (stage) {
    ReleaseStage.PLANNED -> "计划"
    ReleaseStage.CHECKED -> "已核对"
    ReleaseStage.COMMITTED -> "已提交"
    ReleaseStage.PUSHED -> "已推送"
    ReleaseStage.RELEASED -> "已发布"
}
