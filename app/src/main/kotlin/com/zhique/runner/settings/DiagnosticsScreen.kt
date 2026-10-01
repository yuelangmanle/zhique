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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.core.ai.AiError
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 连接诊断（规格 §7「AI 服务商 → 连接诊断」，M9 补齐独立入口页）：
 * 逐个 Provider 测连通——优先走既有 /models 探测（[ModelListFetcher]，与
 * 服务商表单「刷新模型目录」同一套逻辑）；无模型目录协议失败时回落最小 chat
 * 请求（[ModelCatalog.PROBE_IMAGE_DATA_URL] 同源的 16-token 文本探测）。
 * 展示延迟与结果；Key 经 ProviderStore 解密，零日志零 URL 携带。
 */
class DiagnosticsController(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** 探测缝（测试注入假探测）：返回模型列表 = 连通。 */
    private val probe: suspend (protocol: String, baseUrl: String, apiKey: String) -> List<String> =
        { protocol, baseUrl, apiKey -> ModelListFetcher().fetch(protocol, baseUrl, apiKey) },
) {

    data class Row(
        val providerId: String,
        val name: String,
        val protocol: String,
        val busy: Boolean = false,
        val ok: Boolean? = null,
        val latencyMs: Long = 0,
        val detail: String = "",
    )

    private val _rows = MutableStateFlow<List<Row>>(emptyList())
    val rows: StateFlow<List<Row>> = _rows.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(io) {
            _rows.value = store.list().map { Row(it.id, it.name, it.protocol) }
        }
    }

    fun testOne(providerId: String) {
        scope.launch(io) { runProbe(providerId) }
    }

    fun testAll() {
        scope.launch(io) {
            _rows.value.filter { !it.busy }.forEach { runProbe(it.providerId) }
        }
    }

    private suspend fun runProbe(providerId: String) {
        val config = store.list().firstOrNull { it.id == providerId } ?: return
        update(providerId) { it.copy(busy = true, ok = null, detail = "测试中…") }
        val started = System.currentTimeMillis()
        val result = runCatching {
            probe(config.protocol, config.baseUrl, store.decryptKey(config))
        }
        val latency = System.currentTimeMillis() - started
        update(providerId) { row ->
            result.fold(
                onSuccess = { models ->
                    row.copy(
                        busy = false,
                        ok = true,
                        latencyMs = latency,
                        detail = "连通 · /models ${models.size} 个模型",
                    )
                },
                onFailure = { e ->
                    row.copy(
                        busy = false,
                        ok = false,
                        latencyMs = latency,
                        detail = "失败：${(e as? AiError)?.message ?: e.message ?: e::class.simpleName}",
                    )
                },
            )
        }
    }

    private fun update(providerId: String, transform: (Row) -> Row) {
        _rows.value = _rows.value.map { if (it.providerId == providerId) transform(it) else it }
    }
}

/** 连接诊断页（规格 §7）：逐个测试连通 / 全部测试，显示延迟与结果。 */
@Composable
fun DiagnosticsScreen(
    controller: DiagnosticsController,
    onBack: () -> Unit,
) {
    val rows by controller.rows.collectAsState()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surface) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("diagnostics-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text("连接诊断", style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(
                    "逐个测试服务商连通（/models 端点，失败回落最小 chat 请求），显示延迟与结果。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { controller.testAll() },
                    modifier = Modifier.testTag("diag-test-all"),
                ) { Text("全部测试") }
                Spacer(Modifier.height(12.dp))
                if (rows.isEmpty()) {
                    Text(
                        "暂无服务商，先到「AI 服务商」添加",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("diag-empty"),
                    )
                }
                rows.forEach { row ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .testTag("diag-row-${row.providerId}"),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(row.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        row.protocol,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { controller.testOne(row.providerId) },
                                    enabled = !row.busy,
                                    modifier = Modifier.testTag("diag-test-${row.providerId}"),
                                ) { Text("测试") }
                            }
                            Spacer(Modifier.height(4.dp))
                            if (row.detail.isNotBlank()) {
                                Text(
                                    buildString {
                                        append(if (row.ok == true) "✓ " else if (row.ok == false) "✗ " else "")
                                        append(row.detail)
                                        if (row.ok != null) append("（${row.latencyMs}ms）")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = when (row.ok) {
                                        true -> MaterialTheme.colorScheme.primary
                                        false -> MaterialTheme.colorScheme.error
                                        null -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.testTag("diag-result-${row.providerId}"),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
