package com.zhique.core.apilot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.zhique.core.apilot.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Apilot V2 双向桥（Task 8.1，规格 §4.9）。
 *
 * 读（[buildPickIntent] → [parsePickResult]）：`PICK_API_CONFIG` Activity Result，
 * 回传是 extra JSON 或 content URI——**收到后立即读取，绝不持久保存 URI**
 * （Apilot 10 分钟后删除缓存文件）。
 *
 * 写（[buildImportIntent]）：`IMPORT_API_CONFIGS`，V2 `apiProfiles` payload；
 * 小负载 JSON extra，含 Key / 大负载（>64KiB）走一次性 content URI +
 * `FLAG_GRANT_READ_URI_PERMISSION`（URI 由调用方经 [uriProvider] 提供，通常 FileProvider）。
 *
 * 协议纪律：
 * - `RESULT_CANCELED` ≠ 失败：映射为 [PickOutcome.Canceled]，调用方不得重试或静默回退；
 * - `RESULT_OK` 但 extra 与 data 都缺 → [PickOutcome.Invalid]，提示用户重新操作；
 * - 应用锁 PIN 解锁停留不算无响应（[ApilotProtocol.PICK_TIMEOUT_MS] 已放宽）。
 *
 * 零凭据纪律：本类不打印任何 payload/Key 日志；toString 不含请求内容。
 */
class ApilotBridge(
    /** Apilot 包名（Apilot 改名时改 BuildConfig.APILOT_PACKAGE 一处）。 */
    val apilotPackage: String = BuildConfig.APILOT_PACKAGE,
    val sourceName: String = ApilotProtocol.SOURCE_NAME,
    private val requestId: () -> String = { UUID.randomUUID().toString() },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun newRequestId(): String = requestId()

    /** 本方声明签名 SHA-256（PackageManager 取本包签名，冒号分隔大写十六进制）。 */
    fun ownSignatureSha256(context: Context): String? =
        packageSignatureSha256(context.packageManager, context.packageName)

    // ---- 读：PICK_API_CONFIG ----

    /**
     * 选择已保存方案：四档 scope 由用户在 Apilot 授权页逐项确认；
     * Key 不勾不回传（`secret.api_key` 独立 scope）。
     */
    fun buildPickIntent(
        requestIdValue: String = newRequestId(),
        scopes: List<String> = ApilotProtocol.DEFAULT_SCOPES,
    ): Intent = Intent(ApilotProtocol.ACTION_PICK).apply {
        setPackage(apilotPackage)
        putExtra(ApilotProtocol.EXTRA_SOURCE_NAME, sourceName)
        putExtra(ApilotProtocol.EXTRA_REQUEST_ID, requestIdValue)
        putExtra(ApilotProtocol.EXTRA_SCHEMA_VERSION, ApilotProtocol.SCHEMA_V2)
        putStringArrayListExtra(ApilotProtocol.EXTRA_REQUESTED_SCOPES, ArrayList(scopes))
        putExtra(ApilotProtocol.EXTRA_RETURN_TRANSPORT, ApilotProtocol.TRANSPORT_AUTO)
    }

    /**
     * 一键授予网关能力（官方文档 v2.5.0+「本地网关互操作」）：用户在 Apilot 确认后，
     * Apilot 启动本地 OpenAI 兼容网关并回传描述符（baseUrl/model/token）。
     * `scope`=loopback（仅本机，默认最安全）或 lan（局域网设备可用）。
     */
    fun buildGrantGatewayIntent(
        requestIdValue: String = newRequestId(),
        scope: String = ApilotProtocol.GATEWAY_SCOPE_LOOPBACK,
    ): Intent = Intent(ApilotProtocol.ACTION_GRANT_GATEWAY).apply {
        setPackage(apilotPackage)
        putExtra(ApilotProtocol.EXTRA_SOURCE_NAME, sourceName)
        putExtra(ApilotProtocol.EXTRA_REQUEST_ID, requestIdValue)
        putExtra(ApilotProtocol.EXTRA_REQUESTED_SCOPE, scope)
    }

    /** 网关授权回传描述符（GATEWAY_GRANT_JSON 的结构化视图）。 */
    data class GatewayGrant(
        val baseUrl: String,
        val model: String,
        val scope: String,
        val token: String?,
        val headerName: String,
        val apiKey: String?,
    )

    /**
     * 解析网关授权回传。RESULT_OK 但 JSON 缺 baseUrl/model → Invalid；
     * token 只在 lan 模式存在；apiKey（本机模式占位 Key）按文档可直接使用。
     */
    fun parseGatewayGrant(resultCode: Int, intent: Intent?): GatewayGrant? {
        if (resultCode != android.app.Activity.RESULT_OK) return null
        val grantJson = intent?.getStringExtra(ApilotProtocol.EXTRA_GATEWAY_GRANT_JSON) ?: return null
        return runCatching {
            val obj = this@ApilotBridge.json.parseToJsonElement(grantJson) as? JsonObject ?: return null
            fun str(k: String) = (obj[k] as? JsonPrimitive)?.contentOrNull
            val baseUrl = str("baseUrl") ?: return null
            val model = str("model") ?: return null
            GatewayGrant(
                baseUrl = baseUrl,
                model = model,
                scope = str("scope") ?: ApilotProtocol.GATEWAY_SCOPE_LOOPBACK,
                token = str("token"),
                headerName = str("headerName") ?: "X-Gateway-Token",
                apiKey = str("apiKey"),
            )
        }.getOrNull()
    }

    /**
     * 解析 Activity Result。[resultCode]/[intent] 原样来自 ActivityResult；
     * content URI 经 [resolver] 立即读取（调用即用完，不保存，1MiB 上限）。
     * [uriReader] 为 URI 读取通道的注入点（测试假实现；生产留空走 [defaultReadUri]）。
     *
     * 防串话：[expectedRequestId] 传 launch 时发出的 REQUEST_ID——回传 requestId
     * 不匹配按 [PickOutcome.Invalid] 处理；V2 回传缺 `connection` scope 时整条拒收
     * （[PickOutcome.Invalid]）。
     *
     * 调用纪律：读 URI/解析可能涉及磁盘 IO，请勿在主线程调用（控制器侧已移 IO 调度器）。
     */
    fun parsePickResult(
        resultCode: Int,
        intent: Intent?,
        resolver: android.content.ContentResolver,
        expectedRequestId: String? = null,
        uriReader: ((android.net.Uri, android.content.ContentResolver) -> String?)? = null,
    ): PickOutcome {
        if (resultCode != android.app.Activity.RESULT_OK) return PickOutcome.Canceled
        val text = readResultText(intent, resolver, uriReader) ?: return PickOutcome.Invalid
        // 回传大小上限：超限判无效（防 OOM；默认读取器在流式读取时已拦一层，此处兜住注入通道）
        if (text.toByteArray(Charsets.UTF_8).size > ApilotProtocol.RESULT_MAX_BYTES) return PickOutcome.Invalid
        val outcome = parseResultJson(text)
        if (outcome is PickOutcome.V2 && ApilotProtocol.SCOPE_CONNECTION !in outcome.result.grantedScopes) {
            return PickOutcome.Invalid // 无 connection scope → 无法建立连接语义，整条拒收
        }
        if (expectedRequestId != null && requestIdOf(outcome) != expectedRequestId) {
            return PickOutcome.Invalid // 防串话：不是本次请求的回传
        }
        return outcome
    }

    private fun requestIdOf(outcome: PickOutcome): String? = when (outcome) {
        is PickOutcome.V2 -> outcome.result.requestId
        is PickOutcome.V1 -> outcome.result.requestId
        else -> null
    }

    /** extra 优先、content URI 兜底（文档示例同序）；都没有 → null（无效结果）。 */
    private fun readResultText(
        intent: Intent?,
        resolver: android.content.ContentResolver,
        uriReader: ((android.net.Uri, android.content.ContentResolver) -> String?)?,
    ): String? {
        intent?.getStringExtra(ApilotProtocol.EXTRA_CONFIG_JSON)?.let { return it }
        val uri = intent?.data ?: return null
        val read = uriReader ?: ApilotBridge::defaultReadUri
        return runCatching { read(uri, resolver) }.getOrNull()
    }

    private fun parseResultJson(text: String): PickOutcome = runCatching {
        val bare = json.parseToJsonElement(text)
        val obj = bare as? kotlinx.serialization.json.JsonObject
            ?: return PickOutcome.Malformed(MALFORMED_TOP_LEVEL)
        val schema = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
        when (schema) {
            ApilotProtocol.SCHEMA_V2 -> PickOutcome.V2(json.decodeFromString(PickResult.serializer(), text))
            ApilotProtocol.SCHEMA_V1 -> PickOutcome.V1(json.decodeFromString(V1PickResult.serializer(), text))
            else -> PickOutcome.Malformed(MALFORMED_SCHEMA)
        }
    }.getOrElse {
        // 固定文案：序列化异常原文可能引用输入片段（含 Key 时有进 UI/日志的风险），绝不外带
        PickOutcome.Malformed(MALFORMED_PARSE)
    }

    // ---- 写：IMPORT_API_CONFIGS ----

    /** 构造 V2 导入 payload JSON（apiProfiles；schemaVersion=2 + source 声明）。 */
    fun buildImportPayload(
        profiles: List<ApiProfile>,
        selfPackageName: String,
        signatureSha256: String? = null,
        appName: String = sourceName,
    ): String = json.encodeToString(
        ApiProfilesPayload.serializer(),
        ApiProfilesPayload(
            source = ProfileSource(
                appName = appName,
                packageName = selfPackageName,
                signatureSha256 = signatureSha256,
            ),
            apiProfiles = profiles,
        ),
    )

    /**
     * 导入 Intent：负载 ≤ [ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES] 走 JSON extra；
     * 超限必须提供 [uriProvider]（把 JSON 落成一次性 content URI + 读授权 flag）。
     * 超限且无 URI 通道时显式抛错——Binder 事务缓冲约 1MB，把大负载塞 extra
     * 会撞 TransactionTooLargeException，绝不静默回落。
     *
     * @throws IllegalArgumentException 负载超限且未配置 URI 通道
     */
    fun buildImportIntent(
        payloadJson: String,
        signatureSha256: String? = null,
        uriProvider: ((String) -> Uri)? = null,
    ): ImportPlan {
        val bytes = payloadJson.toByteArray(Charsets.UTF_8).size
        val provider = uriProvider
        if (bytes > ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES && provider == null) {
            throw IllegalArgumentException(
                "负载过大且未配置 URI 通道：$bytes 字节 > ${ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES}，" +
                    "请提供 uriProvider（FileProvider 一次性 content URI），不回落 extra（TransactionTooLarge 风险）",
            )
        }
        val useUri = bytes > ApilotProtocol.PAYLOAD_URI_THRESHOLD_BYTES
        val intent = Intent(ApilotProtocol.ACTION_IMPORT).apply {
            setPackage(apilotPackage)
            putExtra(ApilotProtocol.EXTRA_SOURCE_NAME, sourceName)
            putExtra(ApilotProtocol.EXTRA_REQUEST_ID, newRequestId())
            signatureSha256?.let { putExtra(ApilotProtocol.EXTRA_SOURCE_SIGNATURE_SHA256, it) }
            if (useUri) {
                setDataAndType(provider!!.invoke(payloadJson), ApilotProtocol.MIME_IMPORT)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                putExtra(ApilotProtocol.EXTRA_CONFIGS_JSON, payloadJson)
            }
        }
        return ImportPlan(intent = intent, viaUri = useUri, payloadBytes = bytes)
    }

    /** 导入计划：intent + 传输方式（测试与 UI 提示用，不含负载内容）。 */
    data class ImportPlan(val intent: Intent, val viaUri: Boolean, val payloadBytes: Int)

    companion object {

        /** Malformed 固定文案（不带异常原文，防 payload/Key 片段进 UI/日志）。 */
        const val MALFORMED_TOP_LEVEL = "回传顶层不是 JSON 对象"
        const val MALFORMED_SCHEMA = "回传 schemaVersion 非 1/2"
        const val MALFORMED_PARSE = "回传解析失败（内容不支持）"

        /** 默认 URI 读取：收到结果立即读入内存，不持久保存 URI 或内容副本；1MiB 封顶。 */
        internal fun defaultReadUri(uri: android.net.Uri, resolver: android.content.ContentResolver): String? {
            val cap = ApilotProtocol.RESULT_MAX_BYTES
            resolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > cap) return null // 超限：判无效结果
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            }
            return null
        }

        /**
         * 声明签名 SHA-256（文档「声明签名 SHA-256」extra 值格式 `AA:BB:CC`）：
         * 取目标包首张签名证书的 SHA-256 摘要，冒号分隔大写十六进制。
         * 仅普通导入时这是「调用方声明」；只有 Activity Result 场景 Apilot 才校验。
         */
        fun packageSignatureSha256(pm: PackageManager, packageName: String): String? = runCatching {
            val signs = if (android.os.Build.VERSION.SDK_INT >= 28) {
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
            signs?.firstOrNull()?.toByteArray()?.let { bytes ->
                MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString(":") { b -> "%02X".format(b) }
            }
        }.getOrNull()

        /** Apilot 是否已安装（包名探测；NameNotFoundException → false）。 */
        fun isInstalled(context: Context, apilotPackage: String = BuildConfig.APILOT_PACKAGE): Boolean =
            runCatching {
                context.packageManager.getPackageInfo(apilotPackage, 0)
                true
            }.getOrDefault(false)
    }
}

/** 选择请求结果（协议纪律的显式建模——取消不是失败）。 */
sealed interface PickOutcome {

    /** 用户取消授权：不重试、不静默回退、不提示错误。 */
    data object Canceled : PickOutcome

    /** RESULT_OK 但 extra 与 data URI 都缺：无效结果，提示用户重新操作。 */
    data object Invalid : PickOutcome

    /** schemaVersion 非 1/2、JSON 损坏或 URI 不可读。 */
    data class Malformed(val reason: String) : PickOutcome

    data class V2(val result: PickResult) : PickOutcome

    data class V1(val result: V1PickResult) : PickOutcome
}
