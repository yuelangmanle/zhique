package com.zhique.runner.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 应用锁控制器（规格 §6「应用锁：PIN + 生物识别」）：
 * - 前台恢复即上锁（onResume 触发 [onForeground]）；PIN 验证/生物识别通过后放行。
 * - 防爆破（质量审查 Critical-1）：失败计数与冷却**截止时间戳**持久化进 DataStore
 *   （杀进程不清）；冷却由控制器协程逐秒递减 UI 展示（[State.cooldownMs]），
 *   [verifyPin] 内部同时校验冷却（不只靠 UI enabled）——双保险防绕过。
 * - 生物识别是加速通道——失败/不可用一律回落 PIN。
 */
class AppLockController(
    private val prefs: PrivacyPreferences,
    private val scope: CoroutineScope,
    /** 时钟缝（测试可注入虚拟时钟）。 */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    data class State(
        val locked: Boolean = false, // 当前是否拦在锁屏
        val pinConfigured: Boolean = false,
        val biometricAvailable: Boolean = false, // 设置页展示用
        val failCount: Int = 0,
        val cooldownMs: Long = 0, // 剩余冷却（秒级递减展示）
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var unlockedThisSession = false
    private var ticker: Job? = null

    init {
        scope.launch { reloadFromStore(initial = true) }
    }

    /** 从 DataStore 重建状态（init 与中途杀进程重建共用）。 */
    private suspend fun reloadFromStore(initial: Boolean) {
        val s = prefs.lockSnapshot()
        val remaining = (s.cooldownUntilMs - nowMs()).coerceAtLeast(0L)
        _state.update {
            it.copy(
                pinConfigured = s.enabled && s.pinHash.isNotBlank(),
                biometricAvailable = s.biometric,
                failCount = s.failCount,
                cooldownMs = remaining,
                // 锁开启 → 进程冷启动即处于锁定态（onResume 首触发同语义）
                locked = if (initial) s.enabled && s.pinHash.isNotBlank() else it.locked,
            )
        }
        startTickerIfNeeded()
    }

    /** 中途杀进程重建（进程恢复时由宿主再调 init 等价路径）。 */
    fun onProcessRestored() {
        scope.launch { reloadFromStore(initial = true) }
    }

    /** 前台恢复（Activity.onResume）：锁开启且本会话未验证 → 上锁。 */
    fun onForeground() {
        scope.launch { reloadFromStore(initial = false) }
        scope.launch {
            val s = prefs.lockSnapshot()
            if (s.enabled && !unlockedThisSession) {
                _state.update { it.copy(locked = true) }
            }
        }
    }

    /** 后台（ON_PAUSE）：立即重锁（质量审查 Important-4：分屏并排可见场景）。 */
    fun onBackground() {
        unlockedThisSession = false
        scope.launch {
            val s = prefs.lockSnapshot()
            _state.update {
                it.copy(locked = it.locked || (s.enabled && s.pinHash.isNotBlank()))
            }
        }
    }

    /** 验证 PIN：冷却期内直接拒绝（防 UI 绕过），成功清零防爆破状态。 */
    fun verifyPin(pin: String) {
        scope.launch {
            val s = prefs.lockSnapshot()
            val remaining = (s.cooldownUntilMs - nowMs()).coerceAtLeast(0L)
            if (remaining > 0) {
                _state.update {
                    it.copy(
                        cooldownMs = remaining,
                        error = "尝试过于频繁，请等待 ${remaining / 1000}s",
                    )
                }
                return@launch
            }
            if (prefs.verifyPin(pin)) {
                unlockedThisSession = true
                prefs.clearFailureState()
                _state.update { it.copy(locked = false, failCount = 0, cooldownMs = 0, error = null) }
            } else {
                val n = s.failCount + 1
                val cooldown = cooldownFor(n)
                val until = nowMs() + cooldown
                prefs.recordFailure(n, until)
                _state.update {
                    it.copy(failCount = n, cooldownMs = cooldown, error = "PIN 不正确")
                }
                startTickerIfNeeded()
            }
        }
    }

    /** 生物识别通过（UI 层 BiometricPrompt 回调成功后调用）。 */
    fun onBiometricSuccess() {
        scope.launch {
            unlockedThisSession = true
            prefs.clearFailureState()
        }
        _state.update { it.copy(locked = false, failCount = 0, cooldownMs = 0, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** 冷却递减协程：剩余 >0 时每秒刷新 [State.cooldownMs]，归零自停。 */
    private fun startTickerIfNeeded() {
        if (ticker?.isActive == true) return
        if (_state.value.cooldownMs <= 0) return
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                val until = runCatching { prefs.lockSnapshot().cooldownUntilMs }.getOrDefault(0L)
                val remaining = (until - nowMs()).coerceAtLeast(0L)
                _state.update { it.copy(cooldownMs = remaining) }
                if (remaining <= 0L) break
            }
        }
    }

    companion object {
        /** 连续失败冷却：第 n 次失败 → min(1000 * 2^(n-1), 30_000) ms。 */
        fun cooldownFor(failCount: Int): Long =
            if (failCount <= 0) 0L else (1000L shl (failCount - 1).coerceAtMost(5)).coerceAtMost(30_000L)
    }
}
