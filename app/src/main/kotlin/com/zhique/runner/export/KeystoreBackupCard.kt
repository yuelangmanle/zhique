package com.zhique.runner.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.zhique.core.export.BackupStatus
import com.zhique.core.export.ImportResult
import com.zhique.core.export.KeystoreManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val backupTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * 密钥库备份状态控制器（决策29-3 常驻状态的真值源）：
 * 导出中心与权限中心共用。restore 走 KeystoreManager.import，
 * 指纹与既有 ExportRecord.certSha256 不一致 → [restoreError] 提示条（拒绝生效）。
 */
class KeystoreBackupController(
    private val keystore: KeystoreManager,
    private val existingFingerprints: () -> List<String>,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onToast: (String) -> Unit = {},
) {

    private val _status = MutableStateFlow<BackupStatus?>(null)
    val status: StateFlow<BackupStatus?> = _status

    private val _restoreError = MutableStateFlow<String?>(null)
    val restoreError: StateFlow<String?> = _restoreError

    fun refresh() {
        scope.launch(ioDispatcher) {
            _status.value = runCatching { keystore.backupStatus() }.getOrNull()
        }
    }

    /** 产出 .jks 备份副本（UI 随后分享；分享动作落地即视为已备份）。 */
    fun prepareBackup(cacheDir: File): File? = runCatching {
        keystore.exportTo(File(File(cacheDir, "exports").apply { mkdirs() }, "zhique-release.jks"))
    }.onFailure { onToast("备份副本产出失败：${it.message}") }.getOrNull()

    fun markBackedUp() {
        scope.launch(ioDispatcher) {
            runCatching { keystore.markBackedUp() }
                .onSuccess { _status.value = keystore.backupStatus() }
                .onFailure { onToast("备份状态记录失败：${it.message}") }
        }
    }

    /**
     * 恢复：[path]（.jks 本地副本）+ 口令 → import。
     * 指纹与既有导出记录不一致 → Rejected → [restoreError]（密钥库保持原状）。
     */
    fun restore(path: String, pass: String) {
        scope.launch(ioDispatcher) { importInternal(path, pass) }
    }

    /**
     * 恢复（字节版）：卡片侧 SAF URI 立即读成字节后调用；临时 .jks 由本方法
     * 写入 cache 并在 finally 删除（质量审查 Minor-6：用后即清）。
     */
    fun restoreFromBytes(data: ByteArray, pass: String, cacheDir: File) {
        scope.launch(ioDispatcher) {
            val tmp = File(cacheDir, "restore-${System.nanoTime()}.jks")
            try {
                tmp.writeBytes(data)
                importInternal(tmp.absolutePath, pass)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun importInternal(path: String, pass: String) {
        val result = runCatching { keystore.import(path, pass, existingFingerprints()) }
            .getOrElse { _restoreError.value = "导入失败：口令错误或文件损坏"; return }
        when (result) {
            is ImportResult.Accepted -> {
                _restoreError.value = null
                _status.value = keystore.backupStatus()
                onToast("密钥库已恢复（指纹校验通过）")
            }
            is ImportResult.Rejected -> _restoreError.value =
                "已拒绝生效：导入证书指纹 ${result.certSha256.take(16)}… 与既有导出记录不一致" +
                    "（该密钥库签不出已装版本）。请导入与已装应用一致的 .jks，或先卸载旧包。"
        }
    }
}

/**
 * 密钥库备份状态卡（规格 §4.7-3 常驻组件）：lastBackupAt/超期提醒/备份到电脑
 * （分享 .jks）/恢复（文件选择器 → 口令 → import，指纹不一致拒绝的错误提示条）。
 * 导出中心与权限中心同款复用。
 */
@Composable
fun KeystoreBackupCard(
    controller: KeystoreBackupController,
    testPrefix: String = "center",
    modifier: Modifier = Modifier,
    onRestoreReadFailed: () -> Unit = {},
) {
    val status by controller.status.collectAsState()
    val restoreError by controller.restoreError.collectAsState()
    val context = LocalContext.current
    var pendingRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var restorePass by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { controller.refresh() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            restorePass = ""
            pendingRestore = uri
        }
    }

    val s = status
    Surface(
        color = if (s?.backupDue == true) Color(0xFFFFF3CD) else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().testTag("$testPrefix-backup"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                when {
                    s == null -> "密钥库状态未知"
                    !s.keystoreExists -> "签名密钥库：首次导出时自动生成"
                    s.backupDue -> "签名密钥超期未备份到电脑——手机丢失将无法更新已装应用"
                    else -> "签名密钥已备份：${backupTimeFormat.format(Date(s.lastBackupAt))}"
                },
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.testTag("$testPrefix-backup-status"),
            )
            s?.certSha256?.let {
                Text(
                    "证书指纹 $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            restoreError?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC0392B),
                    modifier = Modifier.testTag("$testPrefix-restore-error"),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(
                    onClick = {
                        val copy = controller.prepareBackup(context.cacheDir) ?: return@OutlinedButton
                        ExportDelivery.shareKeystore(context, copy)
                        controller.markBackedUp()
                    },
                    modifier = Modifier.testTag("$testPrefix-backup-btn"),
                ) { Text("备份到电脑") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.testTag("$testPrefix-restore-btn"),
                ) { Text("恢复密钥库") }
            }
        }
    }

    // 恢复口令弹层：URI 立即读、不存（读入缓存副本后即释放授权）
    if (pendingRestore != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("恢复签名密钥库") },
            text = {
                Column {
                    Text(
                        "输入该 .jks 的口令；证书指纹与既有导出记录不一致时将拒绝生效。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = restorePass,
                        onValueChange = { restorePass = it },
                        label = { Text("口令") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("$testPrefix-restore-pass"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val uri = pendingRestore
                        pendingRestore = null
                        if (uri != null) {
                            // 立即读、不存 URI：读成字节交控制器（临时 .jks 由控制器用后即删）
                            val bytes = runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                            if (bytes != null) {
                                controller.restoreFromBytes(bytes, restorePass, context.cacheDir)
                            } else {
                                onRestoreReadFailed()
                            }
                        }
                    },
                    modifier = Modifier.testTag("$testPrefix-restore-confirm"),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("取消") }
            },
        )
    }
}
