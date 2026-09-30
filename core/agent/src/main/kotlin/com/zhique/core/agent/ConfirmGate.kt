package com.zhique.core.agent

import com.zhique.core.ai.ToolCall
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/**
 * 外发工具批准闸（规格 §6：默认逐项确认）：编排器在 requiresConfirm 工具上真挂起，
 * 批准卡按钮经 [approveCurrent]/[denyCurrent] 恢复循环——挂起期间不烧轮数/预算，
 * 模型不会被再次调用，批准卡不会被重复请求覆盖。
 */
class ConfirmGate {
    private val pending = AtomicReference<Pair<ToolCall, CompletableDeferred<Boolean>>?>(null)

    /** 当前挂起中的工具调用（无挂起为 null）。 */
    val pendingCall: ToolCall? get() = pending.get()?.first

    /** 编排器侧：挂起等待用户裁决。 */
    suspend fun await(call: ToolCall): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending.set(call to deferred)
        try {
            return deferred.await()
        } finally {
            pending.compareAndSet(call to deferred, null)
        }
    }

    /** 批准卡「批准」：恢复挂起的编排循环并执行该工具。返回是否有挂起被恢复。 */
    fun approveCurrent(): Boolean = resume(true)

    /** 批准卡「拒绝」：恢复循环但跳过执行（编排器回写「用户拒绝」）。返回是否有挂起被恢复。 */
    fun denyCurrent(): Boolean = resume(false)

    private fun resume(approved: Boolean): Boolean {
        val entry = pending.getAndSet(null) ?: return false
        entry.second.complete(approved)
        return true
    }
}
