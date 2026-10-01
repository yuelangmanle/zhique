package com.zhique.runner.export

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.permission.Capability
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 导出向导（规格 §5.3 屏 7）：①应用信息（名/图标色）②权限与签名（备份状态置顶 +
 * 真实使用驱动的建议清单 + min/full 变体）③打包进度 → 完成（安装/存下载/分享/快捷方式）。
 * 签名不一致时切阻断页（决策29-5：先卸载旧包或恢复正确密钥库）。
 */
@Composable
fun ExportWizardScreen(
    controller: ExportController,
    onDone: () -> Unit,
    onToast: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { if (state.result == null) controller.back() else onDone() },
                        modifier = Modifier.testTag("wizard-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("导出向导", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(
                        "第 ${state.step + 1}/3 步",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp).testTag("wizard-step"),
                    )
                }
            }

            when {
                state.mismatch -> MismatchBlock(state = state, onBack = { controller.back() })
                state.result != null -> DoneStep(
                    state = state,
                    onInstall = {
                        runCatching { ExportDelivery.installApk(context, state.result!!.apk) }
                            .onFailure { onToast("安装失败：${it.message}") }
                    },
                    onSave = {
                        val uri = ExportDelivery.saveToDownloads(
                            context, state.result!!.apk, "${state.result!!.record.packageName}-${state.result!!.record.versionCode}.apk",
                        )
                        onToast(if (uri != null) "已存到下载目录" else "保存失败")
                    },
                    onShare = { ExportDelivery.shareApk(context, state.result!!.apk) },
                    onShortcut = {
                        val ok = ExportDelivery.requestPinShortcut(
                            context, state.appName, state.result!!.record.packageName, state.iconColor,
                        )
                        onToast(if (ok) "已请求添加到桌面" else "桌面不支持固定快捷方式")
                    },
                    onFinish = onDone,
                )
                state.step == 0 -> InfoStep(controller = controller, state = state, onNext = controller::next)
                state.step == 1 -> SignStep(
                    controller = controller,
                    state = state,
                    onBackup = {
                        // 备份分享必须用白名单副本（cache/exports），直接分享
                        // filesDir/export/keystore 下的 .jks 会被 FileProvider 拒绝
                        val copy = controller.prepareBackup(context.cacheDir) ?: return@SignStep
                        runCatching { ExportDelivery.shareKeystore(context, copy) }
                            .onSuccess { controller.markBackedUp() }
                            .onFailure { onToast("备份分享失败：${it.message}") }
                    },
                    onNext = {
                        controller.next()
                        controller.run()
                    },
                )
                else -> PackStep(state = state)
            }
        }
    }
}

/** 步①：应用信息（名/图标色，预览图标）。 */
@Composable
private fun InfoStep(controller: ExportController, state: ExportWizardState, onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("应用信息", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.appName,
            onValueChange = controller::setAppName,
            label = { Text("应用名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("wizard-name"),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.iconColor,
            onValueChange = controller::setIconColor,
            label = { Text("图标色（#RRGGBB）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("wizard-color"),
        )
        Spacer(Modifier.height(16.dp))
        // 图标预览：项目卡图标色圆 + 首字
        val color = runCatching { Color(android.graphics.Color.parseColor(state.iconColor)) }
            .getOrDefault(Color(0xFF46509F))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(color)
                    .testTag("wizard-icon-preview"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.appName.take(1).ifBlank { "应" },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text("桌面图标预览", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onNext,
            enabled = state.canGoStep2,
            modifier = Modifier.fillMaxWidth().testTag("wizard-next"),
        ) { Text("下一步") }
    }
}

/**
 * 步②：权限与签名。密钥库备份状态置顶（已备份时间/超期提醒/「备份到电脑」），
 * 导出建议来自运行期真实使用记录，min/full 变体选择。
 */
@Composable
private fun SignStep(controller: ExportController, state: ExportWizardState, onBackup: () -> Unit, onNext: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        // 备份状态置顶（决策29-3）
        val backup = state.backup
        Surface(
            color = if (backup?.backupDue == true) Color(0xFFFFF3CD) else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("wizard-backup"),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            backup == null -> "密钥库状态未知"
                            !backup.keystoreExists -> "密钥库将在首次导出时生成"
                            backup.backupDue -> "签名密钥尚未备份到电脑（超期提醒）"
                            else -> "密钥库已备份：${timeFormat.format(Date(backup.lastBackupAt))}"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag("wizard-backup-status"),
                    )
                    backup?.certSha256?.let {
                        Text(
                            "证书指纹 ${it.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedButton(onClick = onBackup, modifier = Modifier.testTag("wizard-backup-btn")) {
                    Text("备份到电脑")
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("导出权限建议（按真实使用记录）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            if (state.suggestions.isEmpty()) "运行期未使用任何能力——建议选择精简变体（min）"
            else state.suggestions.joinToString("、") { Capability.fromId(it)?.title ?: it },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("wizard-suggest"),
        )

        Spacer(Modifier.height(16.dp))
        Text("变体", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("wizard-variant")) {
            RadioButton(
                selected = state.variant == "min",
                onClick = { controller.setVariant("min") },
                modifier = Modifier.testTag("wizard-variant-min"),
            )
            Text("精简（仅网络）")
            Spacer(Modifier.width(16.dp))
            RadioButton(
                selected = state.variant == "full",
                onClick = { controller.setVariant("full") },
                modifier = Modifier.testTag("wizard-variant-full"),
            )
            Text("完整（全量桥权限）")
        }
        Text(
            "签名使用织雀统一密钥库（一次生成永久复用），同包名覆盖安装、数据保留。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Button(onClick = onNext, modifier = Modifier.fillMaxWidth().testTag("wizard-pack")) {
            Text("打包")
        }
    }
}

/** 步③：打包进度（含失败可见性：错误必须显形，不得吞成"等待开始"——真机夜间循环发现）。 */
@Composable
private fun PackStep(state: ExportWizardState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val err = state.error
        val mismatch = state.mismatch
        when {
            state.result != null -> Unit // DoneStep 接管
            mismatch -> {
                Text("签名不匹配：已安装版本由其他密钥签名", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text(
                    err ?: "请先卸载旧包或恢复正确密钥库后重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            err != null -> {
                Text("导出失败", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "返回上一步重试；若持续失败请在开发者页导出日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                CircularProgressIndicator(Modifier.size(48.dp).testTag("wizard-progress"))
                Spacer(Modifier.height(16.dp))
                Text(
                    if (state.running) "正在打包（注入 → 签名 → 校验）…" else "准备中…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("wizard-progress-text"),
                )
            }
        }
    }
}

/** 完成：包信息 + 交付动作（安装/存下载/分享/快捷方式）。 */
@Composable
private fun DoneStep(
    state: ExportWizardState,
    onInstall: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onShortcut: () -> Unit,
    onFinish: () -> Unit,
) {
    val record = state.result!!.record
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("导出完成", style = MaterialTheme.typography.titleLarge, modifier = Modifier.testTag("wizard-done"))
        Spacer(Modifier.height(8.dp))
        Text("${record.packageName} · v${record.versionCode}（${record.versionName}）· ${record.variant}",
            style = MaterialTheme.typography.bodyMedium)
        Text(
            "证书 ${record.certSha256.take(16)}…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onInstall, modifier = Modifier.fillMaxWidth().testTag("wizard-install")) { Text("安装") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSave, modifier = Modifier.fillMaxWidth().testTag("wizard-save")) { Text("存到下载目录") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth().testTag("wizard-share")) { Text("分享安装包") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onShortcut, modifier = Modifier.fillMaxWidth().testTag("wizard-shortcut")) {
            Text("添加桌面快捷方式")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onFinish, modifier = Modifier.fillMaxWidth().testTag("wizard-finish")) { Text("完成") }
    }
}

/** 签名不一致阻断页（决策29-5）：导出被机制性拦截，说明原因与出路。 */
@Composable
private fun MismatchBlock(state: ExportWizardState, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "无法导出：签名不一致",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("wizard-mismatch"),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            state.error ?: "手机上已安装的同名应用与当前密钥库签名不同，更新将无法安装。",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "请先卸载旧包，或通过「导出中心 → 恢复密钥库」导入与已装版本一致的密钥库。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack, modifier = Modifier.testTag("wizard-mismatch-back")) { Text("返回处理") }
    }
}
