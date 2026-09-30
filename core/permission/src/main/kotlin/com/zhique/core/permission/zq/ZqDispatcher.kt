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

    /**
     * 运行器销毁时的全量关停（审查修复 I2）：逐能力注销系统监听/相机，
     * 取消全部订阅句柄与取景浮层——杜绝退出后 GPS/传感器仍向已销毁 WebView 推送。
     */
    fun shutdown() {
        capabilities.values.forEach { runCatching { it.shutdown() } }
        env.subs.cancelAll()
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
                // 矩阵拒绝是优雅信号：promise 以 {"code":"denied"} 结算，页面自行降级
                env.evaluateJs(ZqProtocol.resolveJs(id, ok = true, DENIED_JSON))
                return
            }
            // 矩阵授予 → 立即发起系统申请（申请流程内完成）；OS 拒 → 矩阵回 DENIED
            if (com.zhique.core.permission.OsGate.ensure(
                    env.registry, env.projectId, cap.required, env.osPermissions,
                ) == com.zhique.core.permission.OsGateResult.SYSTEM_DENIED
            ) {
                env.evaluateJs(ZqProtocol.resolveJs(id, ok = true, SYSTEM_DENIED_JSON))
                return
            }
            // native 侧同表分级超时（页面侧只做兜底）：超时回 rejected 防 pending 泄漏
            val budget = event.timeout?.takeIf { it > 0 } ?: ZqProtocol.timeoutMs(event.ns, event.fn)
            val result = try {
                kotlinx.coroutines.withTimeout(budget) { cap.call(fn, args, env) }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                throw IllegalStateException("timeout: $ns.$fn 超过 ${budget}ms")
            }
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

        /** 矩阵授予但系统权限被拒：页面可提示「去设置开启」。 */
        const val SYSTEM_DENIED_JSON = "{\"code\":\"denied\",\"reason\":\"system\"}"
    }
}
