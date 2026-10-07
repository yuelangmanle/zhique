package com.zhique.runner.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.zhique.core.telemetry.DebugHub
import com.zhique.runner.ui.kit.ZqTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 事件类别 → 色点（克制灰阶界面里的唯一彩色语义）。 */
private fun catColor(cat: String): Color = when (cat) {
    "ui" -> Color(0xFF3E7C6F)
    "flow" -> Color(0xFF5470B8)
    "feedback" -> Color(0xFF8A6A2F)
    "error" -> Color(0xFFB3402E)
    "net" -> Color(0xFF6E6E68)
    "bg" -> Color(0xFF8A8A84)
    else -> Color(0xFF6E6E68)
}

private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.CHINA)

/**
 * 开发者页内嵌的「后端调试」区：本机 HTTP 后端开关 + 端口展示 + 文件汇开关 +
 * 调试事件流入口。开关走 DataStore（DebugPreferences），默认跟随构建类型。
 */
@Composable
fun DebugBackendCard(
    prefs: com.zhique.runner.settings.DebugPreferences,
    server: com.zhique.core.telemetry.DebugServer,
    debuggableDefault: Boolean,
    onOpenEvents: () -> Unit,
) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val serverOn by prefs.serverEnabled(debuggableDefault).collectAsState(initial = debuggableDefault)
    val sinkOn by prefs.sinkEnabled(debuggableDefault).collectAsState(initial = debuggableDefault)

    Column(Modifier.fillMaxWidth()) {
        Text("后端调试", style = MaterialTheme.typography.titleMedium)
        Text(
            "事件全覆盖（按钮/流程/反馈）+ 本机回环 HTTP 后端（仅 127.0.0.1 可达）。" +
                "电脑侧：adb forward tcp:${server.port.takeIf { it > 0 } ?: 8791} tcp:${server.port.takeIf { it > 0 } ?: 8791}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("调试后端 HTTP 服务", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (server.isRunning) "运行中 · 端口 ${server.port}" else "未运行",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("debug-server-port"),
                )
            }
            Switch(
                checked = serverOn,
                onCheckedChange = { on ->
                    scope.launch {
                        prefs.setServerEnabled(on, debuggableDefault)
                        if (on) server.start() else server.stop()
                        DebugHub.event("flow", "debugserver.toggle", detail = mapOf("on" to "$on"))
                    }
                },
                modifier = Modifier.testTag("dev-debug-server"),
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("事件落盘（JSONL）", style = MaterialTheme.typography.bodyMedium)
                Text(
                    DebugHub.sinkFile()?.absolutePath ?: "未启用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(
                checked = sinkOn,
                onCheckedChange = { on ->
                    scope.launch {
                        prefs.setSinkEnabled(on, debuggableDefault)
                        DebugHub.setSinkEnabled(on)
                    }
                },
                modifier = Modifier.testTag("dev-debug-sink"),
            )
        }
        OutlinedButton(
            onClick = onOpenEvents,
            modifier = Modifier.padding(top = 8.dp).testTag("dev-open-events"),
        ) { Text("查看调试事件流") }
    }
}

/**
 * 调试事件流（设置→开发者→调试事件）：实时滚动最近事件，
 * 类别过滤 + 后端状态行 + 清空。本页自身按钮也全部经 tap 传感器可见。
 */
@Composable
fun DebugEventsScreen(
    serverPort: () -> Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val events by DebugHub.events.collectAsState()
    var filter by remember { mutableStateOf<String?>(null) }
    val filtered = if (filter == null) events else events.filter { it.cat == filter }
    val visible = filtered.takeLast(300).reversed()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ZqTopBar("调试事件流") {
            Button(
                onClick = { DebugHub.clear() },
                modifier = Modifier.testTag("debug-events-clear"),
            ) { Text("清空") }
        }

        // 后端状态行：端口与文件汇位置（adb forward 提示）
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val port = serverPort()
            Text(
                if (port > 0) "后端 http://127.0.0.1:$port ｜ 电脑侧 adb forward tcp:$port tcp:$port"
                else "后端未启动（设置→开发者→调试后端）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("debug-server-state"),
            )
        }

        // 类别过滤
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(null, "ui", "flow", "feedback", "error", "net", "bg").forEach { c ->
                val selected = filter == c
                Surface(
                    onClick = { filter = c },
                    shape = MaterialTheme.shapes.small,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 0.dp,
                    modifier = Modifier.testTag("debug-filter-${c ?: "all"}"),
                ) {
                    Text(
                        c ?: "全部",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }

        if (visible.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("暂无事件——点按任意界面按钮后回到这里查看", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
                items(visible, key = { it.id }) { e ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(8.dp).height(8.dp).background(
                                catColor(e.cat), MaterialTheme.shapes.small,
                            ),
                        )
                        Text(
                            timeFmt.format(Date(e.ts)),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp).width(88.dp),
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(
                                "${e.cat} · ${e.action}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.testTag("debug-event-${e.action}"),
                            )
                            val extra = buildString {
                                e.screen?.let { append("@$it ") }
                                e.detail.forEach { (k, v) -> if (v.length <= 48) append("$k=$v ") }
                            }.trim()
                            if (extra.isNotEmpty()) {
                                Text(
                                    extra,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
