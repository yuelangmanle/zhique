package com.zhique.runner.onboarding

import com.zhique.core.ai.AiError
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.ai.Protocol
import com.zhique.runner.settings.ProtocolDefaults
import com.zhique.runner.settings.ProviderConfig
import com.zhique.runner.settings.ProviderStore
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 引导屏状态（X4：90 秒两步）。 */
data class OnboardingState(
    val paste: String = "",
    val recognized: Boolean = false,
    val name: String = "",
    val protocol: String = Protocol.OPENAI_COMPATIBLE,
    val baseUrl: String = ProtocolDefaults.baseUrl(Protocol.OPENAI_COMPATIBLE),
    val apiKey: String = "",
    val model: String = "",
    val privacyAcked: Boolean = false,
    val busy: Boolean = false,
    val notice: String? = null,
    val finished: Boolean = false,
) {
    val canFinish: Boolean get() = privacyAcked
    val draftComplete: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 首启引导控制器：粘贴识别预填 → 测连通 → 隐私勾选 → 完成落 Provider；
 * 或跳过玩示例。隐私告知未勾选不得完成（规格 §6）。
 */
class OnboardingController(
    private val store: ProviderStore,
    private val prefs: OnboardingPreferences,
    private val fetcher: ModelListFetcher,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onDone: () -> Unit = {},
    private val onSkipPlaySample: () -> Unit = {},
) {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            val acked = runCatching { prefs.privacyAckedAt.first() > 0 }.getOrDefault(false)
            _state.update { it.copy(privacyAcked = acked) }
        }
    }

    fun setPaste(text: String) {
        _state.update { it.copy(paste = text) }
    }

    /** 步骤 1：粘贴识别 → 预填表单（识别不出也进入表单，用户手填）。 */
    fun recognize() {
        val raw = _state.value.paste
        if (raw.isBlank()) return
        val draft = ApiConfigParser.parse(raw)
        _state.update { s ->
            s.copy(
                recognized = true,
                protocol = draft.protocol ?: s.protocol,
                baseUrl = draft.baseUrl ?: s.baseUrl,
                apiKey = draft.apiKey ?: s.apiKey,
                model = draft.model ?: s.model,
                name = draft.apiKey?.let { "识别的服务商" } ?: s.name,
                notice = if (draft.hasSomething) "已识别并预填，请核对后测连通" else "未识别出配置字段，请手动填写",
            )
        }
    }

    fun update(transform: (OnboardingState) -> OnboardingState) {
        _state.update(transform)
    }

    /** 测连通：拉一次模型列表（三协议各自 /models）。 */
    fun testConnection() {
        val s = _state.value
        if (s.baseUrl.isBlank() || s.apiKey.isBlank()) {
            _state.update { it.copy(notice = "先填 Base URL 与 Key") }
            return
        }
        scope.launch(io) {
            _state.update { it.copy(busy = true, notice = null) }
            val models = runCatching { fetcher.fetch(s.protocol, s.baseUrl, s.apiKey) }
                .onFailure { e ->
                    val msg = (e as? AiError)?.message ?: e.message
                    _state.update { it.copy(busy = false, notice = "连通失败：$msg") }
                }
                .getOrNull()
            if (models != null) {
                _state.update {
                    it.copy(
                        busy = false,
                        notice = "连通成功，发现 ${models.size} 个模型",
                        model = it.model.ifBlank { models.firstOrNull() ?: "" },
                    )
                }
            }
        }
    }

    fun setPrivacyAcked(acked: Boolean) {
        _state.update { it.copy(privacyAcked = acked) }
        if (acked) scope.launch(io) { prefs.ackPrivacy() }
    }

    /** 完成：勾选了隐私告知才可完成；草稿完整则落一个 Provider。 */
    fun finish() {
        val s = _state.value
        if (!s.canFinish) {
            _state.update { it.copy(notice = "请先勾选隐私告知") }
            return
        }
        scope.launch(io) {
            if (s.draftComplete) {
                store.upsert(
                    ProviderConfig(
                        id = UUID.randomUUID().toString(),
                        name = s.name.ifBlank { "默认服务商" },
                        protocol = s.protocol,
                        baseUrl = s.baseUrl.trim().trimEnd('/'),
                        keyCipher = store.encryptKey(s.apiKey),
                        model = s.model.trim(),
                    ),
                )
            }
            prefs.markDone()
            _state.update { it.copy(finished = true) }
            onDone()
        }
    }

    /** 跳过，玩内置示例（无 Key 可玩，规格 X4）。 */
    fun skip() {
        scope.launch(io) {
            prefs.markDone()
            onSkipPlaySample()
        }
    }
}
