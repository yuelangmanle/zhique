package com.zhique.runner.agent

import com.zhique.core.agent.AgentContext
import com.zhique.core.agent.AgentEvent
import com.zhique.core.agent.AgentMemory
import com.zhique.core.agent.Budget
import com.zhique.core.agent.Compactor
import com.zhique.core.agent.Memory
import com.zhique.core.agent.MemoryContextAssembler
import com.zhique.core.agent.Orchestrator
import com.zhique.core.agent.ToolContext
import com.zhique.core.agent.ToolRegistry
import com.zhique.core.agent.WebControl
import com.zhique.core.agent.defaultTools
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.ToolCall
import com.zhique.core.ai.StreamEvent
import com.zhique.core.project.ProjectRepository
import com.zhique.core.project.Snapshot
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 预算三弧数据（轮/token/时长）。 */
data class BudgetUi(
    val roundsUsed: Int = 0,
    val maxRounds: Int = Budget.DEFAULT_MAX_ROUNDS,
    val tokensUsed: Long = 0,
    val elapsedMs: Long = 0,
)

/** 一轮模型输出。 */
data class RoundUi(val no: Int, val thinking: String, val content: String)

/** 一步工具执行（ok=null 进行中）。 */
data class StepUi(
    val tool: String,
    val ok: Boolean?,
    val detail: String,
    val diff: DiffUi? = null,
)

data class AgentUiState(
    val goal: String = "",
    val projectName: String = "",
    val running: Boolean = false,
    val finished: Boolean = false,
    val budgetHit: Boolean = false,
    val error: String? = null,
    val vision: Boolean = false,
    val autoApproved: Boolean = false,
    val rounds: List<RoundUi> = emptyList(),
    val steps: List<StepUi> = emptyList(),
    val snapshots: List<Snapshot> = emptyList(),
    val awaitConfirm: AgentEvent.AwaitConfirm? = null,
    val compression: Compactor.CompressionReport? = null,
    val contextUsage: Float = 0f,
    val budget: BudgetUi = BudgetUi(),
)

/**
 * Agent 会话控制器（HomeController 模式）：组装三层记忆 + 预算 + 编排器，
 * 事件流折算 UI 态；暂停=终止当前 run（上下文保留），续 5 轮=放宽轮数闸后重入。
 */
class AgentController(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val deps: Deps,
) {
    data class Deps(
        val projectId: String,
        val projectName: String,
        val repo: ProjectRepository,
        val vision: Boolean,

        /** 上下文上限真值（AiWiring 三层解析：手动覆盖 > 模型目录 > 默认）。 */
        val contextLimit: Int = com.zhique.core.ai.ModelCatalog.DEFAULT_CONTEXT_WINDOW,
        val llm: suspend (ChatRequest) -> Flow<StreamEvent>,
        val fastChat: suspend (ChatRequest) -> Flow<StreamEvent>,
        val template: ChatRequest,
        val fastTemplate: ChatRequest,
        val memory: Memory? = null,
        val web: WebControl? = null,
        val recordUsage: (suspend (Int) -> Unit)? = null,
        val onToast: (String) -> Unit = {},
        /** 通知三事件挂点（M9）：Agent 完成触发，开关过滤在容器侧。 */
        val onNotify: (channel: String, title: String, body: String) -> Unit = { _, _, _ -> },
    )

    private val _state = MutableStateFlow(AgentUiState(goal = "", projectName = deps.projectName, vision = deps.vision))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var assembler: MemoryContextAssembler? = null
    private var budget: Budget? = null
    private var compactor: Compactor? = null
    private var pendingEdit: Pair<String, String>? = null // path → before content
    private val registry = ToolRegistry(defaultTools())
    private val confirmGate = com.zhique.core.agent.ConfirmGate()

    fun setGoal(text: String) {
        _state.update { it.copy(goal = text) }
    }

    /** 「全自动模式」：开时 requiresConfirm 工具免批准直接执行（默认关）。 */
    fun setAutoApproved(enabled: Boolean) {
        _state.update { it.copy(autoApproved = enabled) }
    }

    /** 开始一次会话（预算默认 5 轮）。 */
    fun start() {
        if (_state.value.running || _state.value.goal.isBlank()) return
        budget = Budget(maxRounds = Budget.DEFAULT_MAX_ROUNDS).also { it.start() }
        assembler = newAssembler()
        _state.update {
            it.copy(running = true, finished = false, budgetHit = false, error = null,
                rounds = emptyList(), steps = emptyList(), compression = null)
        }
        launchRun()
    }

    /** 终止/暂停：取消当前 run，会话上下文保留（可续跑）。 */
    fun stop() {
        job?.cancel()
        job = null
        // M4 债务收敛：暂停时清掉挂起中的批准闸与残留批准卡——
        // 取消的编排协程不会再消费批准结果，卡片留着点不出任何效果还误导用户
        confirmGate.denyCurrent()
        _state.update {
            it.copy(running = false, error = "已暂停（上下文保留，可续 5 轮）", awaitConfirm = null)
        }
    }

    /** 续 5 轮：放宽预算闸后重入同一会话（记忆/报错时间线保留）。 */
    fun resumeFive() {
        if (_state.value.running) return
        val b = budget ?: Budget().also { budget = it; it.start() }
        b.extendRounds(RESUME_ROUNDS)
        _state.update { it.copy(running = true, budgetHit = false, error = null) }
        launchRun()
    }

    /** 回滚到任意快照。 */
    fun rollback(snapshotId: String) {
        scope.launch(io) {
            runCatching { deps.repo.history.restore(deps.projectId, snapshotId) }
                .onSuccess {
                    refreshSnapshots()
                    deps.onToast("已回滚到 $snapshotId")
                }
                .onFailure { deps.onToast("回滚失败：${it.message}") }
        }
    }

    /** 手动压缩上下文 → 摘要卡数据。 */
    fun compactNow() {
        val asm = assembler ?: newAssembler().also { assembler = it }
        val comp = compactor ?: Compactor(deps.fastChat, deps.fastTemplate).also { compactor = it }
        scope.launch(io) {
            val report = runCatching { comp.compactNow(asm, manual = true) }.getOrNull()
            _state.update { it.copy(compression = report) }
            if (report == null) deps.onToast("暂无可压缩的会话内容")
        }
    }

    /**
     * AwaitConfirm 批准：恢复挂起的编排循环，工具由编排器执行（挂起期间轮数/预算定格，
     * 批准卡不会被重复请求覆盖）。执行/审计/回写均在编排器侧。
     */
    fun approve() {
        if (_state.value.awaitConfirm == null) return
        _state.update { it.copy(awaitConfirm = null) }
        confirmGate.approveCurrent()
    }

    /** 拒绝：恢复循环但不执行；「用户拒绝」回写由编排器落会话记忆。 */
    fun deny() {
        if (_state.value.awaitConfirm == null) return
        _state.update { it.copy(awaitConfirm = null) }
        confirmGate.denyCurrent()
    }

    fun dismissCompression() {
        _state.update { it.copy(compression = null) }
    }

    // ---- internals ----

    private fun newAssembler(): MemoryContextAssembler {
        return MemoryContextAssembler(
            budget = ContextBudgetOf(),
            requestTemplate = deps.template,
            memory = deps.memory,
            projectId = deps.projectId,
            fileMapProvider = {
                val files = listProjectFiles()
                com.zhique.core.agent.FileMap.build(files)
            },
            autoCompact = { asm ->
                val comp = compactor ?: Compactor(deps.fastChat, deps.fastTemplate).also { compactor = it }
                comp.compactNow(asm, manual = false)
            },
            autoScope = scope,
        )
    }

    /** 上下文预算：上限由 wiring 三层解析（手动覆盖第三层填充对 Agent 生效）。 */
    private fun ContextBudgetOf(): com.zhique.core.agent.ContextBudget =
        com.zhique.core.agent.ContextBudget(contextLimit = deps.contextLimit)

    private fun listProjectFiles(): List<Pair<String, String>> =
        com.zhique.core.agent.tools.ProjectFiles.walk(deps.repo, deps.projectId)
            .map { path -> path to (runCatching { deps.repo.readFile(deps.projectId, path) }.getOrDefault("")) }

    private fun launchRun() {
        if (job?.isActive == true) return
        val asm = assembler ?: newAssembler().also { assembler = it }
        val b = budget ?: Budget().also { budget = it; it.start() }
        job = scope.launch(io) {
            val web = deps.web ?: NullWebControl
            val ctx = AgentContext(
                projectId = deps.projectId,
                toolCtx = ToolContext(deps.projectId, web, deps.repo, deps.repo.history, deps.vision),
                assembler = asm,
                memory = deps.memory,
                autoApproved = _state.value.autoApproved,
            )
            val orch = Orchestrator(
                llm = deps.llm,
                tools = registry,
                budget = b,
                recordUsage = deps.recordUsage,
                confirmGate = confirmGate,
            )
            try {
                orch.run(_state.value.goal, ctx).collect { onEvent(it, asm, b) }
            } catch (e: CancellationException) {
                _state.update { it.copy(running = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: "会话异常") }
            } finally {
                refreshSnapshots()
            }
        }
    }

    private fun onEvent(e: AgentEvent, asm: MemoryContextAssembler, b: Budget) {
        when (e) {
            is AgentEvent.Round -> _state.update {
                it.copy(
                    rounds = it.rounds + RoundUi(e.no, e.thinking, e.content),
                    contextUsage = asm.usage(),
                    budget = budgetUi(b),
                )
            }
            is AgentEvent.Step -> {
                if (e.tool == "edit_file") {
                    val path = runCatching {
                        com.zhique.core.agent.ToolRegistry.parseArgs(e.argsJson).let { args ->
                            ToolRegistry.str(args, "path") ?: ""
                        }
                    }.getOrDefault("")
                    // before 内容取编辑前快照（内容在工具执行前已定格，避开读写竞态）
                    val before = e.snapshotId
                        ?.let { sid -> runCatching { deps.repo.history.content(deps.projectId, sid) }.getOrNull() }
                        ?: runCatching { deps.repo.readFile(deps.projectId, path) }.getOrDefault("")
                    pendingEdit = path to before
                }
                _state.update {
                    it.copy(steps = it.steps + StepUi(e.tool, null, "执行中…"), budget = budgetUi(b))
                }
            }
            is AgentEvent.StepResult -> {
                _state.update { s ->
                    val idx = s.steps.indexOfLast { it.ok == null }
                    var diff: DiffUi? = null
                    if (idx >= 0 && s.steps[idx].tool == "edit_file" && e.ok) {
                        val (path, before) = pendingEdit ?: ("index.html" to "")
                        val after = runCatching { deps.repo.readFile(deps.projectId, path) }.getOrDefault(after0())
                        diff = DiffUi(path, SimpleDiff.diff(before, after))
                        pendingEdit = null
                    }
                    val steps = if (idx >= 0) {
                        s.steps.toMutableList().also { it[idx] = s.steps[idx].copy(ok = e.ok, detail = e.detail, diff = diff ?: s.steps[idx].diff) }
                    } else {
                        s.steps + StepUi(e.tool, e.ok, e.detail)
                    }
                    s.copy(steps = steps, budget = budgetUi(b))
                }
                refreshSnapshots()
            }
            is AgentEvent.AwaitConfirm -> {
                // 待批状态回写会话记忆：防模型下一轮重复发起同一外发工具烧预算
                asm.appendTurn("tool", "工具 ${e.tool} 为外发动作，已提交用户批准；批准前不得再次发起")
                _state.update { it.copy(awaitConfirm = e) }
            }
            AgentEvent.BudgetHit -> _state.update { it.copy(running = false, budgetHit = true, budget = budgetUi(b)) }
            is AgentEvent.Finished -> {
                _state.update {
                    it.copy(running = false, finished = true, contextUsage = asm.usage(), budget = budgetUi(b))
                }
                deps.onNotify(
                    com.zhique.runner.notify.ZhiqueNotifications.CHANNEL_AGENT_DONE,
                    "Agent 完成",
                    "「${_state.value.goal.take(24)}」任务已完成",
                )
            }
            is AgentEvent.Failed -> _state.update { it.copy(running = false, error = e.message, budget = budgetUi(b)) }
        }
    }

    private fun after0(): String = ""

    private fun budgetUi(b: Budget) = BudgetUi(
        roundsUsed = b.roundsUsed,
        maxRounds = b.maxRounds,
        tokensUsed = b.usedTokens,
        elapsedMs = b.elapsedMs,
    )

    private fun refreshSnapshots() {
        val snaps = runCatching { deps.repo.history.list(deps.projectId) }.getOrDefault(emptyList())
        _state.update { it.copy(snapshots = snaps) }
    }

    /** 测试观察口：当前会话组装器（预算/拒绝回写断言用）。 */
    internal fun currentAssembler(): MemoryContextAssembler? = assembler

    companion object {
        const val RESUME_ROUNDS = 5
    }
}
