package com.zhique.runner.settings

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.zhique.core.apilot.ApilotAuditStore
import com.zhique.core.apilot.ApilotBridge
import com.zhique.core.apilot.ApiProfile
import com.zhique.core.apilot.ApiProfilesPayload
import com.zhique.core.apilot.PickOutcome
import com.zhique.core.apilot.ProfileConnection
import com.zhique.core.apilot.ProfileMapper
import com.zhique.core.apilot.ProfileModels
import com.zhique.core.apilot.ProfileOrigin
import com.zhique.core.apilot.ProfileProtocol
import com.zhique.core.apilot.ProfileProvider
import com.zhique.core.apilot.ProfileSecrets
import com.zhique.core.ai.Protocol
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Apilot 双向流转的 App 侧状态（上次同步时间；DataStore 与设置域共用）。
 */
class ApilotSyncStore(private val store: DataStore<Preferences>) {

    suspend fun lastImportAt(): Long? = store.data.first()[KEY_LAST_IMPORT]

    suspend fun lastExportAt(): Long? = store.data.first()[KEY_LAST_EXPORT]

    suspend fun setLastImport(at: Long) {
        store.edit { it[KEY_LAST_IMPORT] = at }
    }

    suspend fun setLastExport(at: Long) {
        store.edit { it[KEY_LAST_EXPORT] = at }
    }

    companion object {
        val KEY_LAST_IMPORT = longPreferencesKey("apilot_last_import_at")
        val KEY_LAST_EXPORT = longPreferencesKey("apilot_last_export_at")
    }
}

/**
 * Apilot 双向流转控制器（Task 8.1b，规格 §4.9/§7 AI 服务商）：
 *
 * - 「从 Apilot 接入」：[handleActivityResult] 解析 PICK 结果并同构落库
 *   （provider.id×protocol.id 直通；无 `secret.api_key` scope → 无 Key 方案）；
 * - 「同步到 Apilot」：[buildSyncIntent] 用 V2 apiProfiles payload 推送
 *   （含 Key；大负载由 [uriProvider] 落一次性 content URI）；
 * - 审计记录落库可清（[clearRecords]，对齐 Apilot「设置中可清除」的口径）。
 *
 * 协议纪律：取消不是失败（提示中性文案）；桥接期间不做超时重试
 * （应用锁 PIN 停留不算无响应，[com.zhique.core.apilot.ApilotProtocol.PICK_TIMEOUT_MS]）。
 */
class ApilotController(
    private val store: ProviderStore,
    private val audit: ApilotAuditStore,
    private val sync: ApilotSyncStore,
    private val bridge: ApilotBridge = ApilotBridge(),
    /** Apilot 安装检测（packageManager 查包名）。 */
    private val checkInstalled: () -> Boolean = { false },
    /** 本包签名 SHA-256（导入声明用；测试可注入）。 */
    private val signatureProvider: () -> String? = { null },
    /** 大负载 URI 通道（生产为 FileProvider；null 时桥回落 JSON extra）。 */
    private val uriProvider: ((String) -> Uri)? = null,
    private val selfPackageName: String = "com.zhique.runner",
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    data class UiState(
        val installed: Boolean = false,
        val lastImportAt: Long? = null,
        val lastExportAt: Long? = null,
        val providerCount: Int = 0,
        val busy: Boolean = false,
        val notice: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        scope.launch(io) {
            val providers = store.list()
            _state.value = _state.value.copy(
                installed = checkInstalled(),
                lastImportAt = sync.lastImportAt(),
                lastExportAt = sync.lastExportAt(),
                providerCount = providers.size,
            )
        }
    }

    fun setNotice(text: String?) {
        _state.value = _state.value.copy(notice = text)
    }

    /** PICK 请求 Intent（四档 scope，Key 是否给由用户在 Apilot 授权页勾选）。 */
    fun pickIntent(): Intent = bridge.buildPickIntent()

    /**
     * 同步 Intent：把已接入服务商转成 V2 apiProfiles（connection/provider/protocol/
     * models/secrets/origin）。无服务商 → null（UI 提示先接入）。
     */
    suspend fun buildSyncIntent(): Intent? {
        val providers = store.list()
        if (providers.isEmpty()) return null
        val profiles = providers.map { p ->
            ApiProfile(
                connection = ProfileConnection(name = p.name, baseUrl = p.baseUrl),
                provider = ProfileProvider(id = ProfileMapper.hostProvider(p.baseUrl)),
                protocol = ProfileProtocol(id = p.protocol.takeIf { it in ProfileMapper.PROTOCOL_IDS } ?: Protocol.OPENAI_COMPATIBLE),
                models = ProfileModels(selectedModel = p.model.ifBlank { null }),
                secrets = ProfileSecrets(apiKey = store.decryptKey(p).ifBlank { null }),
                origin = ProfileOrigin(appName = bridge.sourceName),
            )
        }
        val payload = bridge.buildImportPayload(
            profiles = profiles,
            selfPackageName = selfPackageName,
            signatureSha256 = signatureProvider(),
        )
        return bridge.buildImportIntent(payload, signatureSha256 = signatureProvider(), uriProvider = uriProvider).intent
    }

    /**
     * 处理 PICK 的 Activity Result：解析 → 同构落库 → 审计 → 刷新。
     * 返回面向用户的提示（取消=中性提示，绝不重试）。
     */
    fun handleActivityResult(resultCode: Int, data: Intent?, resolver: ContentResolver) {
        val outcome = bridge.parsePickResult(resultCode, data, resolver)
        when (outcome) {
            is PickOutcome.Canceled -> _state.value = _state.value.copy(notice = "已取消：未做任何更改")
            is PickOutcome.Invalid -> _state.value = _state.value.copy(notice = "结果无效：请在 Apilot 中重新授权")
            is PickOutcome.Malformed -> _state.value = _state.value.copy(notice = "无法解析 Apilot 返回（${outcome.reason}）")
            is PickOutcome.V2 -> applyMapped(ProfileMapper.mapV2(outcome.result))
            is PickOutcome.V1 -> applyMapped(ProfileMapper.mapV1(outcome.result))
        }
    }

    private fun applyMapped(mapped: ProfileMapper.MappedProvider) {
        scope.launch(io) {
            val config = ProviderConfig(
                id = UUID.randomUUID().toString(),
                name = mapped.name,
                protocol = mapped.protocol,
                baseUrl = mapped.baseUrl.trim().trimEnd('/'),
                keyCipher = store.encryptKey(mapped.apiKey ?: ""),
                model = mapped.model ?: "",
            )
            store.upsert(config)
            audit.record("read", mapped.name, hasKey = mapped.hasKey)
            sync.setLastImport(now())
            _state.value = _state.value.copy(
                installed = checkInstalled(),
                lastImportAt = sync.lastImportAt(),
                providerCount = store.list().size,
                notice = "已从 Apilot 导入「${mapped.name}」" + if (mapped.hasKey) "（含 Key）" else "（无 Key）",
            )
        }
    }

    /** 同步已发起（Intent 已交给系统）：立即记审计（只记方向/数量，不含 payload）。 */
    fun markSyncLaunched() {
        scope.launch(io) {
            audit.record("write", "推送 ${store.list().size} 个服务商", hasKey = true)
        }
    }

    /** 同步返回：RESULT_OK 记上次导出时间；取消=中性提示。 */
    fun handleSyncResult(resultCode: Int) {
        if (resultCode == android.app.Activity.RESULT_OK) {
            scope.launch(io) {
                sync.setLastExport(now())
                _state.value = _state.value.copy(lastExportAt = now(), notice = "已交给 Apilot 处理同步")
            }
        } else {
            _state.value = _state.value.copy(notice = "同步已取消：未做任何更改")
        }
    }

    /** 清除本 App 的桥接审计记录（对齐「审计可清」）。 */
    fun clearRecords() {
        audit.clear()
        _state.value = _state.value.copy(notice = "桥接记录已清除")
    }
}

/** 时间展示（MM-dd HH:mm；空值 →「从未」）。 */
fun formatSyncTime(at: Long?): String {
    if (at == null || at <= 0) return "从未"
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    return fmt.format(java.util.Date(at))
}
