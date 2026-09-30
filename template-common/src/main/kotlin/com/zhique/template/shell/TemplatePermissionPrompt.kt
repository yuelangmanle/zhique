package com.zhique.template.shell

import com.zhique.core.permission.PermissionAsk
import com.zhique.core.permission.PermissionPrompt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 模板壳授权卡状态持有者（PermissionPrompt 实现，与 :app 的 AppPermissionPrompt
 * 同协议同语义：Mutex 排队、授予/拒绝经 [answer] 结算；UI 出口是原生卡片
 * [TemplateShellActivity] 内的授权卡视图——壳内授权卡与织雀同体验）。
 */
class TemplatePermissionPrompt : PermissionPrompt {

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
