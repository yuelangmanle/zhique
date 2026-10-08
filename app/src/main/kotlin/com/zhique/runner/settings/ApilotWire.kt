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
import com.zhique.core.apilot.ApilotProtocol
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
    /** 大负载 URI 通道（生产为 FileProvider；null 时 buildSync 直接拒绝大负载）。 */
    private val uriProvider: ((String) -> Uri)? = null,
    /** 同步流程结束后的临时负载清理（finally 语义：OK/取消/异常都执行）。 */
    private val tempFileCleanup: (() -> Unit)? = null,
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

    /** launch 前记住的 PICK REQUEST_ID（内存即可）：回传不匹配按无效结果处理，防串话。 */
    private var pendingPickRequestId: String? = null

    /** launch 前记住的网关授权 REQUEST_ID（防串话；进程死亡 → 安全侧失败）。 */
    private var pendingGatewayRequestId: String? = null

    /**
     * Activity 结果分发通道：MainActivity.onCreate → [attachActivity]；
     * [launchViaActivity] 用传统 startActivityForResult（固定低 16 位 requestCode）
     * 发起，结果经 MainActivity.onActivityResult 转回 [onActivityResult]。
     *
     * 为什么不用 Activity Result API：androidx.activity 1.11.0 起 registry 随机生成
     * 高位 requestCode（nextInt(0x7FFF0000)+0x10000），而 framework 的
     * Activity.startActivityForResult 强校验 requestCode 只能低 16 位 → launch
     * 必抛 "Can only use lower 16 bits for requestCode"（TV 实测实锤，即用户
     * 真机反馈的同款错误）。传统固定 requestCode 完全绕开该冲突。
     */
    private var hostActivity: android.app.Activity? = null
    private var hostResolver: ContentResolver? = null

    fun attachActivity(activity: android.app.Activity) {
        hostActivity = activity
        hostResolver = activity.contentResolver
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val resolver = hostResolver
        when (requestCode) {
            com.zhique.runner.MainActivity.APILOT_RC_PICK -> {
                if (resolver == null) {
                    setNotice("宿主未就绪：请重新进入后重试")
                } else {
                    handleActivityResult(resultCode, data, resolver)
                }
            }
            com.zhique.runner.MainActivity.APILOT_RC_GATEWAY ->
                handleGatewayResult(resultCode, data)
        }
    }

    /** 传统通道发起 PICK；宿主 Activity 缺失 → false（UI 提示重新进入）。 */
    fun launchPickViaActivity(): Boolean {
        val activity = hostActivity ?: return false
        pendingPickRequestId = null
        val intent = pickIntent()
        return runCatching {
            activity.startActivityForResult(intent, com.zhique.runner.MainActivity.APILOT_RC_PICK)
        }.isSuccess
    }

    /** 传统通道发起网关授权。 */
    fun launchGatewayViaActivity(): Boolean {
        val activity = hostActivity ?: return false
        val intent = gatewayIntent()
        pendingGatewayRequestId = intent.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID)
        return runCatching {
            activity.startActivityForResult(intent, com.zhique.runner.MainActivity.APILOT_RC_GATEWAY)
        }.isSuccess
    }

    /**
     * 发起同步：Apilot 的 IMPORT 协议是「发起后无回传」（官方文档示例即普通
     * startActivity，确认导入全在 Apilot 侧完成），所以这里成功拉起即记账——
     * 不用 startActivityForResult（永远只会等到默认 CANCELED，误导用户）。
     * 大负载经一次性 content URI 交付，Apilot 的读取窗口由用户审查节奏决定
     * （可达数分钟），清理延迟 15 分钟兜底。
     */
    fun launchSync(plan: SyncPlan): Boolean {
        val activity = hostActivity ?: return false
        val launched = runCatching {
            activity.startActivity(plan.intent)
        }.isSuccess
        if (!launched) return false
        scope.launch(io) {
            sync.setLastExport(now())
            audit.record("write", "推送 ${plan.providerCount} 个服务商", hasKey = plan.hasKey)
            _state.value = _state.value.copy(
                lastExportAt = now(),
                notice = "已发起推送（${plan.providerCount} 个）：到 Apilot 确认「导入」完成",
            )
            tempFileCleanup?.let { cleanup ->
                kotlinx.coroutines.delay(java.util.concurrent.TimeUnit.MINUTES.toMillis(15))
                // Apilot 读取窗口 10 分钟已过：只删旧文件，绝不动新 payload
                runCatching { cleanup() }
            }
        }
        return true
    }

    /**
     * 旧说明（已被上方的传统通道取代）：此前用 Activity Result API + 稳定 key
     * 注册，androidx.activity 1.11.0 起 registry 随机生成高位 requestCode 与
     * framework 校验冲突，详见上方传统通道注释。
     */

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
        com.zhique.core.telemetry.DebugHub.event(
            "feedback", "apilot.notice", detail = mapOf("text" to (text ?: "cleared")),
        )
        _state.value = _state.value.copy(notice = text)
    }

    /** PICK 请求 Intent（四档 scope，Key 是否给由用户在 Apilot 授权页勾选）；同时钉住 REQUEST_ID。 */
    fun pickIntent(): Intent = bridge.buildPickIntent().also { intent ->
        pendingPickRequestId = intent.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID)
    }

    /** 网关一键授权 Intent（Apilot v2.5.0+ GRANT_GATEWAY；loopback 默认）。 */
    fun gatewayIntent(): Intent = bridge.buildGrantGatewayIntent().also { intent ->
        pendingGatewayRequestId = intent.getStringExtra(ApilotProtocol.EXTRA_REQUEST_ID)
    }

    /**
     * 处理网关授权回传：把 baseUrl/model 直接落成 Provider（openai_compatible，
     * Key=网关 apiKey 占位或 lan 模式的 token），桥接期间零手抄。
     * 非法/取消 → notice 提示；成功 → 审计 + 状态刷新。
     */
    fun handleGatewayResult(resultCode: Int, data: Intent?) {
        scope.launch(io) {
            val expected = pendingGatewayRequestId
            pendingGatewayRequestId = null
            val grant = bridge.parseGatewayGrant(resultCode, data, expectedRequestId = expected)
            if (grant == null) {
                setNotice("网关授权已取消或无效")
                return@launch
            }
            val config = ProviderConfig(
                id = UUID.randomUUID().toString(),
                name = "Apilot 网关（${grant.model}）",
                protocol = com.zhique.core.ai.Protocol.OPENAI_COMPATIBLE,
                baseUrl = grant.baseUrl.trimEnd('/'),
                keyCipher = store.encryptKey(grant.apiKey ?: grant.token ?: "gateway"),
                model = grant.model,
            )
            store.upsert(config)
            audit.record("gateway", config.name, hasKey = true)
            _state.value = _state.value.copy(
                installed = checkInstalled(),
                lastImportAt = now(),
                providerCount = store.list().size,
                notice = "已接入 Apilot 网关：${grant.baseUrl} · ${grant.model}",
            )
        }
    }

    /**
     * 同步到 Apilot 的完整计划：intent + 真实是否含 Key（审计用）+ 服务商数。
     * 无服务商 → null（UI 提示先接入）。
     */
    data class SyncPlan(val intent: Intent, val hasKey: Boolean, val providerCount: Int)

    /**
     * 构建同步计划：把已接入服务商转成 V2 apiProfiles（connection/provider/protocol/
     * models/secrets/origin）。含 Key 或大负载由 [uriProvider] 落一次性 content URI。
     */
    suspend fun buildSync(): SyncPlan? {
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
        val hasKey = providers.any { store.decryptKey(it).isNotBlank() }
        val payload = bridge.buildImportPayload(
            profiles = profiles,
            selfPackageName = selfPackageName,
            signatureSha256 = signatureProvider(),
        )
        val plan = bridge.buildImportIntent(payload, signatureSha256 = signatureProvider(), uriProvider = uriProvider)
        return SyncPlan(intent = plan.intent, hasKey = hasKey, providerCount = providers.size)
    }

    /**
     * 处理 PICK 的 Activity Result：解析（IO 线程，URI 读取有 1MiB 上限）→
     * REQUEST_ID 防串话校验 → 同构落库 → 审计 → 刷新。
     * 取消=中性提示绝不重试；requestId 不匹配或缺 connection scope=无效结果。
     */
    fun handleActivityResult(resultCode: Int, data: Intent?, resolver: ContentResolver) {
        scope.launch(io) {
            val expected = pendingPickRequestId
            pendingPickRequestId = null
            when (val outcome = bridge.parsePickResult(resultCode, data, resolver, expectedRequestId = expected)) {
                is PickOutcome.Canceled -> _state.value = _state.value.copy(notice = "已取消：未做任何更改")
                is PickOutcome.Invalid -> _state.value = _state.value.copy(notice = "结果无效：请在 Apilot 中重新授权")
                is PickOutcome.Malformed -> _state.value = _state.value.copy(notice = "无法解析 Apilot 返回（${outcome.reason}）")
                is PickOutcome.V2 -> applyMapped(ProfileMapper.mapV2(outcome.result))
                is PickOutcome.V1 -> applyMapped(ProfileMapper.mapV1(outcome.result))
            }
        }
    }

    private suspend fun applyMapped(mapped: ProfileMapper.MappedProvider) {
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
