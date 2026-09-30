package com.zhique.core.permission.zq

import com.zhique.core.permission.PState
import com.zhique.core.web.debug.DebugEvent
import com.zhique.core.web.debug.TimelineReducer
import com.zhique.core.web.debug.ZqProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * zq_call 调度器（计划 Task 5.2 核心）：
 *
 * `zq_call 事件 → registry.request（授权卡）→ GRANTED 才执行 native →
 * `__zqResolve(id, ok, json)`；DENIED 返回 `{"code":"denied"}`（网页优雅降级）；
 * native 真实执行成功后 usage +1（运行期真实使用记录）。
 *
 * 经 [attachTo] 挂到宿主 [TimelineReducer.ZqCallRouter]；运行器的采集流把
 * zq_call 一次性路由进来，未注册的 ns.fn 由运行器按既有协议立即 reject。
 */
class ZqDispatcher(private val env: ZqEnv) {

    private val capabilities = LinkedHashMap<String, ZqCapability>()

    fun register(capability: ZqCapability) {
        capabilities[capability.ns] = capability
    }

    fun registered(): List<String> = capabilities.keys.toList()

    /** 把全部 ns.fn 注册到宿主路由；命中后异步进入 [handle]。 */
    fun attachTo(router: TimelineReducer.ZqCallRouter) {
        for (cap in capabilities.values) {
            for (fn in cap.methods) {
                router.register(cap.ns, fn) { event ->
                    env.scope.launch { handle(event) }
                }
            }
        }
    }

    /** 单次 zq_call 的完整调度（授权 → 执行 → 回写）。 */
    suspend fun handle(event: DebugEvent) {
        val id = event.id ?: return // 无 id 无法 settle，忽略
        val ns = event.ns
        val fn = event.fn
        if (ns.isNullOrBlank() || fn.isNullOrBlank()) {
            env.evaluateJs(ZqProtocol.rejectJs(id, "坏调用: 缺 ns/fn"))
            return
        }
        val cap = capabilities[ns]
        if (cap == null) {
            env.evaluateJs(ZqProtocol.rejectJs(id, "未注册能力: $ns.$fn"))
            return
        }
        try {
            val args = ZqArgs.firstObject(event.args)
            val state = env.registry.request(env.projectId, cap.required.id, cap.why(fn))
            if (state != PState.GRANTED) {
                // 拒绝是优雅信号：promise 以 {"code":"denied"} 结算，页面自行降级
                env.evaluateJs(ZqProtocol.resolveJs(id, ok = true, DENIED_JSON))
                return
            }
            val result = cap.call(fn, args, env)
            env.registry.recordUse(env.projectId, cap.required.id)
            env.evaluateJs(ZqProtocol.resolveJs(id, ok = true, result.toString()))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            env.evaluateJs(ZqProtocol.rejectJs(id, t.message ?: "能力执行失败"))
        }
    }

    companion object {
        const val DENIED_JSON = "{\"code\":\"denied\"}"
    }
}
