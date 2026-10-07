package com.zhique.core.telemetry

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 织雀本机调试后端：仅绑定 127.0.0.1 的极简 HTTP/1.1 服务。
 *
 * 安全边界（稳定性/兼容性前提）：
 * - 只绑回环地址——同机进程可读（调试语义），局域网/外部不可达；
 * - 每请求一线程（daemon）+ Connection: close，无状态无 keep-alive 复杂度；
 * - 所有 handler runCatching，任何异常返回 500 JSON 而非崩线程；
 * - 端口占用自动 +1 重试（默认 8791..8800），[port]=-1 表示未启动。
 *
 * 端点（宿主侧经 `adb forward tcp:N tcp:PORT` 后即可从电脑 curl）：
 * - GET /debug/health               → 会话/版本/当前屏幕/事件数
 * - GET /debug/events?since=&limit= → 增量事件 JSON
 * - POST /debug/mark?label=         → 在事件流打标（外部操作与事件对齐）
 * - POST /debug/toast?text=         → 触发一条设备 toast（真机可视化验证）
 * - POST /debug/clear               → 清空内存事件
 */
class DebugServer(
    private val basePort: Int = 8791,
    private val maxAttempts: Int = 10,
) {

    /** 宿主注入的能力（toast 需要环境，core 层不持 Context）。 */
    var onToast: ((String) -> Unit)? = null

    @Volatile private var server: ServerSocket? = null
    private val json = Json { encodeDefaults = false }

    /** 实际监听端口；-1 = 未运行。 */
    @Volatile
    var port: Int = -1
        private set

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        if (isRunning) return
        for (attempt in 0 until maxAttempts) {
            val candidate = basePort + attempt
            val socket = runCatching {
                ServerSocket(candidate, 8, InetAddress.getByName("127.0.0.1"))
            }.getOrNull() ?: continue
            socket.reuseAddress = true
            server = socket
            port = candidate
            isRunning = true
            thread(name = "zq-debug-server", isDaemon = true) { acceptLoop(socket) }
            DebugHub.event("bg", "debugserver.start", detail = mapOf("port" to "$candidate"))
            return
        }
        DebugHub.event("error", "debugserver.bindfail", detail = mapOf("base" to "$basePort"))
    }

    fun stop() {
        isRunning = false
        runCatching { server?.close() }
        server = null
        port = -1
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (isRunning) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            thread(name = "zq-debug-conn", isDaemon = true) { handle(client) }
        }
        isRunning = false
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // 读掉 header（端点全用 query 传参，无 body 需求）
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val response = route(requestLine)
            val body = response.second
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            val out = socket.getOutputStream()
            val status = if (response.first == 200) "OK" else if (response.first == 404) "Not Found" else "Internal Server Error"
            out.write(
                ("HTTP/1.1 ${response.first} $status\r\n" +
                    "Content-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8),
            )
            out.write(bytes)
            out.flush()
        } catch (_: Exception) {
            // 客户端断开/超时：调试连接不值得记录
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(requestLine: String): Pair<Int, String> {
        val parts = requestLine.split(" ")
        if (parts.size < 2) return 400 to errJson("bad_request")
        val method = parts[0]
        val path = parts[1]
        val (route, query) = path.split("?").let { it[0] to it.getOrElse(1) { "" } }
        val params = parseQuery(query)

        return runCatching {
            when {
                route == "/debug/health" && method == "GET" -> 200 to healthJson()
                route == "/debug/events" && method == "GET" -> 200 to eventsJson(params)
                route == "/debug/mark" && method == "POST" -> {
                    val label = params["label"].orEmpty().ifEmpty { "mark" }
                    DebugHub.event("flow", "debug.mark", detail = mapOf("label" to label))
                    200 to """{"ok":true}"""
                }
                route == "/debug/toast" && method == "POST" -> {
                    val text = params["text"].orEmpty()
                    if (text.isNotEmpty()) onToast?.invoke(text)
                    200 to """{"ok":true}"""
                }
                route == "/debug/clear" && method == "POST" -> {
                    DebugHub.clear()
                    200 to """{"ok":true}"""
                }
                else -> 404 to errJson("not_found", route)
            }
        }.getOrElse { 500 to errJson(it.message ?: "internal") }
    }

    private fun healthJson(): String = buildJsonObject {
        put("ok", true)
        put("app", "zhique")
        DebugHub.snapshot().forEach { (k, v) -> put(k, v) }
        put("serverPort", port)
        put("sinkEnabled", DebugHub.isSinkEnabled())
    }.let { json.encodeToString(JsonObject.serializer(), it) }

    private fun eventsJson(params: Map<String, String>): String {
        val since = params["since"]?.toLongOrNull() ?: 0
        val limit = params["limit"]?.toIntOrNull() ?: 200
        val list = DebugHub.recent(since, limit)
        val arr = JsonArray(list.map { Json.parseToJsonElement(DebugHub.encodeEvent(it)) })
        return buildJsonObject {
            put("count", list.size)
            put("total", DebugHub.count())
            put("events", arr)
        }.let { json.encodeToString(JsonObject.serializer(), it) }
    }

    private fun errJson(code: String, extra: String = ""): String =
        """{"error":"$code"${if (extra.isNotEmpty()) ",\"path\":\"$extra\"" else ""}}"""

    private fun parseQuery(query: String): Map<String, String> =
        query.split("&").filter { it.isNotEmpty() }.associate { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) kv to ""
            else URLDecoder.decode(kv.substring(0, i), "UTF-8") to
                URLDecoder.decode(kv.substring(i + 1), "UTF-8")
        }
}
