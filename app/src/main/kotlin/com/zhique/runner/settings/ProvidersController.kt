package com.zhique.runner.settings

import com.zhique.core.ai.AnthropicProvider
import com.zhique.core.ai.AiError
import com.zhique.core.ai.ChatMessage
import com.zhique.core.ai.ChatRequest
import com.zhique.core.ai.GeminiProvider
import com.zhique.core.ai.ModelCatalog
import com.zhique.core.ai.ModelListFetcher
import com.zhique.core.ai.Modality
import com.zhique.core.ai.OpenAiCompatProvider
import com.zhique.core.ai.Protocol
import com.zhique.core.ai.Provider
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 新增/编辑表单态（协议选择联动 base URL 预填；输出上限 0=默认）。 */
data class ProviderForm(
    val id: String? = null,
    val name: String = "",
    val protocol: String = Protocol.OPENAI_COMPATIBLE,
    val baseUrl: String = ProtocolDefaults.baseUrl(Protocol.OPENAI_COMPATIBLE),
    val apiKey: String = "",
    val model: String = "",
    val modalityManual: String? = null,
    val maxOutputManual: Int = 0,
    val modelOptions: List<String> = emptyList(),
    val busy: Boolean = false,
    val notice: String? = null,
) {
    val modalityBadge: String
        get() = when (modalityManual) {
            "vision" -> "vision"
            "text" -> "text"
            else -> if (ModelCatalog.modality(model) == Modality.VISION) "vision" else "text"
        }
}

/** 公开协议默认端点模板（非凭据）。 */
object ProtocolDefaults {
    fun baseUrl(protocol: String): String = when (protocol) {
        Protocol.ANTHROPIC_MESSAGES -> "https://api.anthropic.com"
        Protocol.GOOGLE_GENAI -> "https://generativelanguage.googleapis.com"
        else -> "https://api.openai.com"
    }
}

/** 按协议构造 Provider（探测与后续会话共用）。 */
fun providerFor(protocol: String): Provider = when (protocol) {
    Protocol.ANTHROPIC_MESSAGES -> AnthropicProvider()
    Protocol.GOOGLE_GENAI -> GeminiProvider()
    else -> OpenAiCompatProvider()
}

/**
 * AI 服务商管理控制器（HomeController 模式）。Key 明文只在表单内存中，落库即密文。
 */
class ProvidersController(
    private val store: ProviderStore,
    private val fetcher: ModelListFetcher,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _providers = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val providers: StateFlow<List<ProviderConfig>> = _providers.asStateFlow()

    private val _form = MutableStateFlow<ProviderForm?>(null)
    val form: StateFlow<ProviderForm?> = _form.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(io) { _providers.value = store.list() }
    }

    fun startNew() {
        _form.value = ProviderForm()
    }

    fun edit(config: ProviderConfig) {
        _form.value = ProviderForm(
            id = config.id,
            name = config.name,
            protocol = config.protocol,
            baseUrl = config.baseUrl,
            apiKey = store.decryptKey(config),
            model = config.model,
            modalityManual = config.modalityManual,
            maxOutputManual = config.maxOutputManual,
        )
    }

    fun cancelForm() {
        _form.value = null
    }

    fun updateForm(transform: (ProviderForm) -> ProviderForm) {
        _form.update { it?.let(transform) }
    }

    /** 协议切换：联动 base URL 预填（仅在用户未自定义时整段替换，简化为直接预填）。 */
    fun onProtocolChange(protocol: String) {
        updateForm { f ->
            f.copy(protocol = protocol, baseUrl = ProtocolDefaults.baseUrl(protocol))
        }
    }

    fun save() {
        val f = _form.value ?: return
        if (f.name.isBlank() || f.baseUrl.isBlank() || f.model.isBlank()) {
            _form.update { it?.copy(notice = "名称、Base URL 与模型必填") }
            return
        }
        scope.launch(io) {
            val id = f.id ?: UUID.randomUUID().toString()
            val keyPlain = f.apiKey.ifBlank { store.get(id)?.let(store::decryptKey) ?: "" }
            val config = ProviderConfig(
                id = id,
                name = f.name.trim(),
                protocol = f.protocol,
                baseUrl = f.baseUrl.trim().trimEnd('/'),
                keyCipher = store.encryptKey(keyPlain),
                model = f.model.trim(),
                modalityManual = f.modalityManual,
                maxOutputManual = f.maxOutputManual,
            )
            store.upsert(config)
            _providers.value = store.list()
            _form.value = null
        }
    }

    fun delete(id: String) {
        scope.launch(io) {
            store.remove(id)
            _providers.value = store.list()
        }
    }

    /** 拉取模型列表（三协议 /models）。 */
    fun fetchModels() {
        val f = _form.value ?: return
        if (f.baseUrl.isBlank() || f.apiKey.isBlank()) {
            _form.update { it?.copy(notice = "先填 Base URL 与 Key") }
            return
        }
        scope.launch(io) {
            _form.update { it?.copy(busy = true, notice = null) }
            val models = runCatching { fetcher.fetch(f.protocol, f.baseUrl, f.apiKey) }
                .onFailure { e ->
                    val msg = (e as? AiError)?.message ?: e.message
                    _form.update { it?.copy(busy = false, notice = "拉取失败：$msg") }
                }
                .getOrNull()
            if (models != null) {
                _form.update { it?.copy(busy = false, modelOptions = models, notice = null) }
            }
        }
    }

    /** 视觉能力探测：发最小图片请求（成功 true / 参数错 false / 未知 null）。 */
    fun probeVision() {
        val f = _form.value ?: return
        if (f.model.isBlank()) {
            _form.update { it?.copy(notice = "先填模型名") }
            return
        }
        scope.launch(io) {
            _form.update { it?.copy(busy = true, notice = null) }
            val req = ChatRequest(
                baseUrl = f.baseUrl,
                apiKey = f.apiKey,
                model = f.model,
                messages = listOf(ChatMessage("user", "回复 ok", images = listOf(ModelCatalog.PROBE_IMAGE_DATA_URL))),
                maxTokens = 16,
                temperature = 0.0,
            )
            val result = runCatching {
                ModelCatalog.probeVision({ providerFor(f.protocol).chatStream(it) }, req)
            }.getOrElse { null }
            _form.update {
                it?.copy(
                    busy = false,
                    notice = when (result) {
                        true -> "探测：支持视觉（vision）"
                        false -> "探测：不支持视觉（text）"
                        null -> "探测：网络/鉴权异常，能力未知"
                    },
                )
            }
        }
    }

    /** 手动覆盖 modality（null=跟知识库）。 */
    fun setModalityManual(value: String?) {
        updateForm { it.copy(modalityManual = value) }
    }
}
