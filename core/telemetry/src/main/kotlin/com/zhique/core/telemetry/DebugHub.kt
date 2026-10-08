package com.zhique.core.telemetry

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 全链路调试事件中枢（每个按钮/流程/反馈的统一出口）。
 *
 * 设计约束（稳定性/兼容性/可持续性）：
 * - 全 API 不抛异常：任何失败静默降级——调试设施绝不反噬业务；
 * - 环形缓冲内存封顶（默认 1000 条），文件 JSONL 落盘带 1MiB 轮换；
 * - 纯 JVM 实现零 android.* 依赖，单元测试直接跑；
 * - 事件 schema 带版本（v 字段），后续扩展只加字段不改语义。
 *
 * 类别约定：ui=按钮/点击（tap 传感器兜底全覆盖）｜flow=屏幕与流程节点｜
 * feedback=toast/notice 等给用户的反馈｜error=异常/失败｜net=网络调用｜
 * bg=后台任务/服务。
 */
object DebugHub {

    /** schema v1：只加字段不改名；detail 值统一字符串（展示层无需类型分支）。 */
    @Serializable
    data class DebugEvent(
        val id: Long,
        val v: Int,
        val ts: Long,
        val session: String,
        val cat: String,
        val action: String,
        val screen: String? = null,
        val detail: Map<String, String> = emptyMap(),
    )

    const val SCHEMA_VERSION = 1
    const val RING_CAPACITY = 1000
    const val SINK_ROTATE_BYTES = 1024L * 1024

    private val lock = Any()
    private val ring = ArrayDeque<DebugEvent>(RING_CAPACITY)
    private val idSeq = newIdSeq()
    private val json = Json { encodeDefaults = false }

    @Volatile private var session: String = "boot"
    @Volatile private var appVersion: String = "?"
    @Volatile private var device: String = "?"
    @Volatile private var startedAt: Long = 0

    @Volatile private var sinkDir: File? = null
    @Volatile private var sinkEnabled: Boolean = false
    @Volatile private var sinkWriter: OutputStreamWriter? = null
    @Volatile private var sinkBytes: Long = 0

    @Volatile private var currentScreen: String? = null
    @Volatile private var lastScreenEventTs: Long = 0
    @Volatile private var lastScreenEventName: String? = null

    private val _events = MutableStateFlow<List<DebugEvent>>(emptyList())
    val events: StateFlow<List<DebugEvent>> = _events.asStateFlow()

    private val previousCrashHandler: Thread.UncaughtExceptionHandler? = null

    private fun newIdSeq() = AtomicLong(0)

    /**
     * 初始化（Application.onCreate 一次）。重复调用幂等——后到参数忽略，
     * 崩溃钩子只在首次安装（链式保留既有 handler，绝不吞掉系统行为）。
     */
    fun init(
        appVersion: String,
        device: String,
        sinkDir: File? = null,
        sinkEnabled: Boolean = sinkDir != null,
    ) {
        if (startedAt != 0L) return
        this.session = UUID.randomUUID().toString().take(8)
        this.appVersion = appVersion
        this.device = device
        this.startedAt = System.currentTimeMillis()
        this.sinkDir = sinkDir
        this.sinkEnabled = sinkEnabled && sinkDir != null
        installCrashHook()
        event("bg", "hub.init", detail = mapOf("version" to appVersion, "device" to device))
    }

    private fun installCrashHook() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                // 崩溃记录同步写透（进程将亡，协程/队列不可靠）
                event(
                    "error", "crash", detail = mapOf(
                        "thread" to thread.name,
                        "type" to throwable.javaClass.name,
                        "message" to (throwable.message ?: ""),
                    ),
                )
                synchronized(lock) { runCatching { sinkWriter?.flush() } }
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 记录事件。任意线程可调、非阻塞（文件写同步但小量、崩溃路径 flush 兜底）。 */
    fun event(cat: String, action: String, detail: Map<String, String> = emptyMap()) {
        runCatching {
            val e = DebugEvent(
                id = idSeq.incrementAndGet(),
                v = SCHEMA_VERSION,
                ts = System.currentTimeMillis(),
                session = session,
                cat = cat,
                action = action,
                screen = currentScreen,
                detail = detail,
            )
            val trimmed: List<DebugEvent>
            synchronized(lock) {
                ring.addLast(e)
                while (ring.size > RING_CAPACITY) ring.removeFirst()
                trimmed = ring.toList()
                appendSink(e)
            }
            _events.tryEmit(trimmed)
        }
    }

    /** 屏幕切换（去重：同名不重复记）。导航单点调用即可覆盖全部屏幕。 */
    fun screen(name: String) {
        if (name == currentScreen) return
        currentScreen = name
        // 防抖：50ms 内的同目标闪烁（重组抖动）不记；异屏快速切换是合法事件照记
        val now = System.currentTimeMillis()
        if (name == lastScreenEventName && now - lastScreenEventTs < 50) return
        lastScreenEventName = name
        lastScreenEventTs = now
        event("flow", "screen", detail = mapOf("to" to name))
    }

    // ---- 查询（HTTP 后端与调试页共用） ----

    fun recent(sinceId: Long = 0, limit: Int = 200): List<DebugEvent> = synchronized(lock) {
        ring.filter { it.id > sinceId }.takeLast(limit.coerceIn(1, RING_CAPACITY))
    }

    fun count(): Int = synchronized(lock) { ring.size }

    fun snapshot(): Map<String, String> = mapOf(
        "session" to session,
        "version" to appVersion,
        "device" to device,
        "screen" to (currentScreen ?: ""),
        "uptimeMs" to (System.currentTimeMillis() - startedAt).toString(),
        "events" to count().toString(),
    )

    fun clear() {
        synchronized(lock) {
            ring.clear()
            _events.tryEmit(emptyList())
        }
    }

    // ---- JSONL 文件汇（files/debug/debug-events.jsonl，1MiB 轮换保留一份 .old） ----

    fun sinkFile(): File? = sinkDir?.let { File(it, "debug-events.jsonl") }

    fun setSinkEnabled(enabled: Boolean) {
        synchronized(lock) {
            sinkEnabled = enabled
            if (!enabled) closeSinkLocked()
        }
    }

    fun isSinkEnabled(): Boolean = sinkEnabled

    /** 等待异步写队列清空（测试断言前同步化；返回即代表文件已落盘）。 */
    fun flushSink() {
        runCatching { sinkExecutor.submit { }.get() }
    }

    // 单线程串行写盘：事件源全在主线程（tap/screen/web 镜像），同步写+flush
    // 在低端机 render loop 里必掉帧。崩溃路径的 flush 在 uncaught hook 里保留同步兜底。
    private val sinkExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "zq-debug-sink").apply { isDaemon = true }
    }

    private fun appendSink(e: DebugEvent) {
        if (!sinkEnabled) return
        val dir = sinkDir ?: return
        val line = json.encodeToString(e) // 只序列化一次（此前每事件两次）
        sinkExecutor.execute {
            runCatching {
                synchronized(lock) {
                    if (!sinkEnabled) return@execute
                    if (!dir.exists()) dir.mkdirs()
                    if (sinkWriter == null) openSinkLocked()
                    if (sinkBytes > SINK_ROTATE_BYTES) rotateSinkLocked()
                    val w = sinkWriter ?: return@execute
                    w.write(line)
                    w.write("\n")
                    w.flush() // 异步任务边界必须落盘（队列空≠缓冲落盘）
                    sinkBytes += line.toByteArray(Charsets.UTF_8).size + 1 // 字节数而非字符数（中文 3B/字）
                }
            }.onFailure { synchronized(lock) { closeSinkLocked() } }
        }
    }

    private fun openSinkLocked() {
        val dir = sinkDir ?: return
        val f = File(dir, "debug-events.jsonl")
        sinkWriter = OutputStreamWriter(FileOutputStream(f, true), StandardCharsets.UTF_8)
        sinkBytes = if (f.exists()) f.length() else 0
    }

    private fun rotateSinkLocked() {
        closeSinkLocked()
        val dir = sinkDir ?: return
        val f = File(dir, "debug-events.jsonl")
        val old = File(dir, "debug-events.old.jsonl")
        if (old.exists()) old.delete()
        if (f.exists()) f.renameTo(old)
        sinkBytes = 0
        openSinkLocked()
    }

    private fun closeSinkLocked() {
        runCatching { sinkWriter?.flush() }
        runCatching { sinkWriter?.close() }
        sinkWriter = null
    }

    /** 测试辅助：清空全部内存态（不重复装崩溃钩子）。 */
    fun resetForTest() {
        synchronized(lock) {
            ring.clear()
            closeSinkLocked()
            sinkBytes = 0
            _events.tryEmit(emptyList())
            idSeq.set(0)
            currentScreen = null
            startedAt = 0 // 允许重新 init
            sinkEnabled = false
        }
    }

    /** 线程安全的只读视图（DebugServer 序列化用）。 */
    fun encodeEvent(e: DebugEvent): String = json.encodeToString(e)
}
