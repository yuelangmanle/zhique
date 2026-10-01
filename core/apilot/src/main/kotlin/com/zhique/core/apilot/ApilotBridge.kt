package com.zhique.core.apilot

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.zhique.core.apilot.BuildConfig
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
     * 解析 Activity Result。[resultCode]/[intent] 原样来自 ActivityResult；
     * content URI 经 [resolver] 立即读取（调用即用完，不保存）。
     * [uriReader] 为 URI 读取通道的注入点（测试假实现；生产留空走 [defaultReadUri]）。
     */
    fun parsePickResult(
        resultCode: Int,
        intent: Intent?,
        resolver: android.content.ContentResolver,
        uriReader: ((android.net.Uri, android.content.ContentResolver) -> String?)? = null,
    ): PickOutcome {
        if (resultCode != android.app.Activity.RESULT_OK) return PickOutcome.Canceled
        val text = readResultText(intent, resolver, uriReader) ?: return PickOutcome.Invalid
        return parseResultJson(text)
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
            ?: return PickOutcome.Malformed("顶层不是 JSON 对象")
        val schema = (obj["schemaVersion"] as? JsonPrimitive)?.intOrNull
        when (schema) {
            ApilotProtocol.SCHEMA_V2 -> PickOutcome.V2(json.decodeFromString(PickResult.serializer(), text))
            ApilotProtocol.SCHEMA_V1 -> PickOutcome.V1(json.decodeFromString(V1PickResult.serializer(), text))
            else -> PickOutcome.Malformed("schemaVersion 非 1/2")
        }
    }.getOrElse { PickOutcome.Malformed("回传解析失败：${it.message}") }

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

        /** 默认 URI 读取：收到结果立即读入内存，不持久保存 URI 或内容副本。 */
        internal fun defaultReadUri(uri: android.net.Uri, resolver: android.content.ContentResolver): String? =
            resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }

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
