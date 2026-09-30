package com.zhique.runner.permission

import com.zhique.core.permission.PermissionAsk
import com.zhique.core.permission.PermissionPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 授权卡状态持有者（PermissionPrompt 的 :app 实现，规格 §4.6「哪个项目、
 * 要什么、干什么」）。[ask] 由注册表挂起调用；授权卡点「授予/拒绝」经
 * [answer] 结算。Mutex 串行化：多张请求排队逐张弹，不叠卡不丢卡。
 */
class AppPermissionPrompt : PermissionPrompt {

    data class Pending(val ask: PermissionAsk, val gate: CompletableDeferred<Boolean>)

    private val mutex = Mutex()
    private val _current = MutableStateFlow<Pending?>(null)

    /** 当前展示中的授权卡（null=无）。 */
    val current: StateFlow<Pending?> = _current

    override suspend fun ask(ask: PermissionAsk): Boolean = mutex.withLock {
        val gate = CompletableDeferred<Boolean>()
        _current.value = Pending(ask, gate)
        try {
            gate.await()
        } finally {
            if (_current.value?.gate === gate) _current.value = null
        }
    }

    /** 授权卡按钮回调（授予=true）。 */
    fun answer(ok: Boolean) {
        _current.value?.gate?.complete(ok)
    }
}
