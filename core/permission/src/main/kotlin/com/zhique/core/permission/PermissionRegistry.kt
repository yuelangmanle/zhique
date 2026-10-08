package com.zhique.core.permission

import com.zhique.core.project.ProjectRepository
import kotlinx.coroutines.CompletableDeferred

/** 能力×项目矩阵的授权状态（规格 §4.6 授权模型）。 */
enum class PState { NOT_ASKED, ASKING, GRANTED, DENIED }

/** 授权卡请求载荷：哪个项目、要什么、干什么（规格 §4.6）。 */
data class PermissionAsk(
    val projectId: String,
    val projectName: String,
    val capability: String,
    val why: String,
)

/**
 * 授权卡 UI 协议接口：显示原生授权卡，返回用户选择（true=授予）。
 * 实现卡在 :app（Compose 授权卡），注册表只依赖该抽象——纯逻辑可 JVM 测试。
 */
fun interface PermissionPrompt {
    suspend fun ask(ask: PermissionAsk): Boolean
}

/**
 * 权限注册表：`能力 × 项目` 二维矩阵的状态机（计划 Task 5.1 契约）。
 *
 * - 四态：NOT_ASKED → ASKING →（授权卡）→ GRANTED / DENIED；
 * - GRANTED/DENIED 是终态：再调直接返回原状态，**不重弹**；
 *   只有权限中心 [set] 直改或 [revoke] 才能离开终态；
 * - [revoke] → NOT_ASKED；正在等待授权卡的挂起调用按 denied 结算；
 * - [usage]/[recordUse]：运行期真实使用计数（授权≠使用），导出时据此生成
 *   最小权限建议清单 [suggestForExport]（manifest 变体映射见
 *   [Capability.manifestFor]）。
 *
 * 状态一律即时持久化进 project.json（meta.permissions），进程重启不丢；
 * 并发的同 (project, capability) 请求共乘同一张授权卡。
 */
class PermissionRegistry(
    private val repo: ProjectRepository,
    private val prompt: PermissionPrompt? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<PState>>()

    fun state(projectId: String, capability: String): PState = readState(projectId, capability)

    /**
     * 请求授权：NOT_ASKED → 弹卡；GRANTED/DENIED 直返（不重弹）；
     * ASKING → 等待在途的那张卡。授权卡缺失或抛错一律按拒绝结算——
     * 拒绝对网页是优雅信号而非悬挂/崩溃。
     */
    suspend fun request(projectId: String, capability: String, why: String): PState {
        require(Capability.fromId(capability) != null) { "unknown capability: $capability" }
        val key = key(projectId, capability)
        data class Entry(val deferred: CompletableDeferred<PState>, val isNew: Boolean)
        val entry = synchronized(lock) {
            when (readState(projectId, capability)) {
                PState.GRANTED -> return PState.GRANTED
                PState.DENIED -> return PState.DENIED
                PState.ASKING -> Entry(
                    deferred = pending[key] ?: return PState.DENIED, // 不变量破损，优雅拒绝
                    isNew = false,
                )
                PState.NOT_ASKED -> CompletableDeferred<PState>().let {
                    pending[key] = it
                    persist(projectId, capability, PState.ASKING)
                    Entry(it, isNew = true)
                }
            }
        }
        // 共乘：已有在途授权卡 → 等它的结果，不重弹
        if (!entry.isNew) return entry.deferred.await()
        // 取消 ≠ 拒绝：用户在授权卡停留时离开运行器（协程被取消）不得把
        // DENIED 写成终态落盘（该能力此后永不重弹）。取消路径回 NOT_ASKED。
        val granted = try {
            runCatching {
                prompt?.ask(PermissionAsk(projectId, projectName(projectId), capability, why))
            }.getOrNull() ?: false
        } catch (e: kotlinx.coroutines.CancellationException) {
            synchronized(lock) {
                if (pending.remove(key) != null) {
                    persist(projectId, capability, PState.NOT_ASKED)
                    entry.deferred.complete(PState.NOT_ASKED)
                }
            }
            throw e
        }
        val result = if (granted) PState.GRANTED else PState.DENIED
        synchronized(lock) {
            // revoke/set 已把在途卡结算掉时：丢弃迟到的卡答案，不覆盖吊销结果
            if (pending.remove(key) != null) {
                persist(projectId, capability, result)
                entry.deferred.complete(result)
            }
        }
        return readState(projectId, capability)
    }

    /**
     * 权限中心直改（绕过授权卡）。ASKING 只能由 [request] 进入；
     * 直改会把在途授权卡按目标状态一并结算。
     */
    fun set(projectId: String, capability: String, state: PState) {
        require(Capability.fromId(capability) != null) { "unknown capability: $capability" }
        require(state != PState.ASKING) { "ASKING 只能由 request() 进入" }
        synchronized(lock) {
            pending.remove(key(projectId, capability))?.complete(state)
            persist(projectId, capability, state)
        }
    }

    /** 吊销 → NOT_ASKED；等待中的授权卡按 denied 结算（运行中 zq 调用拿 denied）。 */
    fun revoke(projectId: String, capability: String) {
        set(projectId, capability, PState.NOT_ASKED)
    }

    /** 运行期真实使用计数（能力 → 次数；只含有记录的能力）。 */
    fun usage(projectId: String): Map<String, Int> =
        runCatching { repo.meta(projectId).permissionUsage.toMap() }.getOrDefault(emptyMap())

    /** 运行期真实使用 +1（native 能力真实执行成功才计）。 */
    fun recordUse(projectId: String, capability: String) {
        check(Capability.fromId(capability) != null) { "unknown capability: $capability" }
        repo.bumpPermissionUsage(projectId, capability)
    }

    /** 导出建议：usage>0 的能力 id 清单（升序）——最小权限 manifest 的输入。 */
    fun suggestForExport(projectId: String): List<String> =
        usage(projectId).filterValues { it > 0 }.keys.sorted()

    /** 导出建议的 manifest 变体映射（集中权限名并集）。 */
    fun manifestForExport(projectId: String): List<String> =
        Capability.manifestFor(suggestForExport(projectId))

    /** 权限中心矩阵：全部能力 × 当前状态（未记录的能力 = NOT_ASKED；孤儿 ASKING 复原）。 */
    fun matrix(projectId: String): Map<String, PState> {
        runCatching { repo.meta(projectId) }.getOrNull() ?: return emptyMap()
        val out = Capability.ALL.associate { it.id to PState.NOT_ASKED }.toMutableMap()
        for (cap in Capability.ALL) out[cap.id] = readState(projectId, cap.id)
        return out
    }

    // ---- internals ----

    // 读缓存（QA 审查 P1：sensor/location/camera 高频回调每次 readState 都全量读
    // project.json 落主线程——60Hz sensor 下每秒几十次磁盘读）。persist 写时更新；
    // meta 文件 mtime 变化（导入/外部修改）即失效重读。
    private val stateCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, PState>>()

    private fun readState(projectId: String, capability: String): PState {
        val k = key(projectId, capability)
        val metaFile = repo.metaFile(projectId)
        // 指纹 = mtime + 文件长度：同毫秒双写 length 几乎必变（导入覆盖 vs set 连写）
        val fingerprint = (metaFile?.lastModified() ?: 0L) to (metaFile?.length() ?: 0L)
        stateCache[k]?.let { (fp0, st) -> if (fp0 == fingerprint) return st }
        val record = runCatching { repo.meta(projectId).permissions[capability] }.getOrNull()
        val stored = runCatching { PState.valueOf(record?.state ?: "") }.getOrDefault(PState.NOT_ASKED)
        // 审查修复 I4：进程死亡可能把 ASKING 留在盘上——无在途授权卡的 ASKING
        // 是未完成请求，按 NOT_ASKED 对外并写回复原（下次 request 正常重弹）
        val resolved = if (stored == PState.ASKING) {
            val hasPending = synchronized(lock) { pending.containsKey(k) }
            if (!hasPending) {
                persist(projectId, capability, PState.NOT_ASKED)
                PState.NOT_ASKED
            } else stored
        } else stored
        stateCache[k] = fingerprint to resolved
        return resolved
    }

    private fun persist(projectId: String, capability: String, state: PState) {
        repo.setPermission(projectId, capability, state.name, now())
        val metaFile = repo.metaFile(projectId)
        val fingerprint = (metaFile?.lastModified() ?: 0L) to (metaFile?.length() ?: 0L)
        stateCache[key(projectId, capability)] = fingerprint to state
    }

    private fun projectName(projectId: String): String =
        runCatching { repo.meta(projectId).name }.getOrDefault(projectId)

    private fun key(projectId: String, capability: String) = "$projectId/$capability"
}
