package com.zhique.core.agent

import com.zhique.core.agent.tools.FileTools
import com.zhique.core.ai.ToolCall
import com.zhique.core.project.HistoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 快照策略（计划 Task 4.2「快照先行」）：变更类工具执行前，先对目标文件
 * 留 HistoryStore 快照（label 形如 `step-r1-edit_file`），任意步可回滚。
 * 只读工具不留快照（避免快照爆炸）。
 */
class SnapshotPolicy(mutatingTools: Set<String> = setOf(FileTools.EDIT_FILE)) {

    private val mutating = mutatingTools

    fun needsSnapshot(toolName: String): Boolean = toolName in mutating

    /**
     * 变更前快照：内容经 [readCurrent]（编排器注入的仓库沙箱读）取目标文件当前内容。
     * 快照失败返回 null 不阻断执行；快照存在与否只影响可回滚性。
     */
    fun snapshotBefore(
        history: HistoryStore,
        projectId: String,
        call: ToolCall,
        round: Int,
        readCurrent: (path: String) -> String,
    ): String? {
        if (!needsSnapshot(call.name)) return null
        val path = runCatching {
            // JsonNull 是 JsonPrimitive 子类：显式排除，"path":null 不得落成字面 "null"
            ((Json.parseToJsonElement(call.argumentsJson) as? JsonObject)
                ?.get("path") as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }
        }.getOrNull()?.content ?: return null
        return runCatching {
            history.append(projectId, "step-r$round-${call.name}", readCurrent(path)).id
        }.getOrNull()
    }
}
