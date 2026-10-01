package com.zhique.runner.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 应用锁控制器（规格 §6「应用锁：PIN + 生物识别」）：前台恢复即上锁（onResume
 * 触发 [onForeground]），PIN 验证/生物识别通过后放行。连续失败按 1s*2^n 递增
 * 冷却（封顶 30s），防暴力尝试。生物识别是加速通道——失败/不可用一律回落 PIN。
 */
class AppLockController(
    private val prefs: PrivacyPreferences,
    private val scope: CoroutineScope,
) {

    data class State(
        val locked: Boolean = false, // 当前是否拦在锁屏
        val pinConfigured: Boolean = false,
        val biometricAvailable: Boolean = false, // 设置页展示用
        val failCount: Int = 0,
        val cooldownMs: Long = 0, // 剩余冷却
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var unlockedThisSession = false

    init {
        scope.launch {
            val s = prefs.lockSnapshot()
            _state.update {
                it.copy(
                    pinConfigured = s.enabled && s.pinHash.isNotBlank(),
                    biometricAvailable = s.biometric,
                    // 锁开启 → 进程冷启动即处于锁定态（onResume 首触发同语义）
                    locked = s.enabled && s.pinHash.isNotBlank(),
                )
            }
        }
    }

    /** 前台恢复（Activity.onResume）：锁开启且本会话未验证 → 上锁。 */
    fun onForeground() {
        scope.launch {
            val s = prefs.lockSnapshot()
            _state.update {
                it.copy(
                    pinConfigured = s.enabled && s.pinHash.isNotBlank(),
                    biometricAvailable = s.biometric,
                )
            }
            if (s.enabled && !unlockedThisSession) {
                _state.update { it.copy(locked = true) }
            }
        }
    }

    /** 后台（ON_STOP）：下次回前台需要重新验证。 */
    fun onBackground() {
        unlockedThisSession = false
    }

    /** 验证 PIN（成功解锁并清零失败计数）。 */
    fun verifyPin(pin: String) {
        scope.launch {
            if (prefs.verifyPin(pin)) {
                unlockedThisSession = true
                _state.update { it.copy(locked = false, failCount = 0, cooldownMs = 0, error = null) }
            } else {
                val n = _state.value.failCount + 1
                _state.update {
                    it.copy(failCount = n, cooldownMs = cooldownFor(n), error = "PIN 不正确")
                }
            }
        }
    }

    /** 生物识别通过（UI 层 BiometricPrompt 回调成功后调用）。 */
    fun onBiometricSuccess() {
        unlockedThisSession = true
        _state.update { it.copy(locked = false, failCount = 0, cooldownMs = 0, error = null) }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    companion object {
        /** 连续失败冷却：第 n 次失败 → min(1000 * 2^(n-1), 30_000) ms。 */
        fun cooldownFor(failCount: Int): Long =
            if (failCount <= 0) 0L else (1000L shl (failCount - 1).coerceAtMost(5)).coerceAtMost(30_000L)
    }
}
