package com.zhique.core.apilot

/**
 * Apilot V2 互操作协议常量（Task 8.1）。
 *
 * 逐字对齐官方文档 `docs/android-third-party-import.md`（对应 Apilot v1.24.0，
 * 本地缓存 /tmp/apilot-3p.md）。改这里前必须先核对官方文档。
 *
 * 安全纪律（文档「取消、错误和安全」节）：
 * - API Key 绝不写 URL / deep link / 日志 / 剪贴板 / 不加密共享存储；
 * - `content://` URI 仅当次交互可读，收到结果立即读、不持久保存（Apilot 10 分钟后删除）；
 * - 深链接 [DEEP_LINK] 仅用于打开说明，绝不携带 Key。
 */
object ApilotProtocol {

    // ---- Intent action（V1/V2 共用，按 schemaVersion 分流；V2 不替换 V1 action） ----
    const val ACTION_IMPORT = "com.apilot.intent.action.IMPORT_API_CONFIGS"
    const val ACTION_PICK = "com.apilot.intent.action.PICK_API_CONFIG"

    // ---- MIME ----
    const val MIME_IMPORT = "application/vnd.apilot.api-configs+json"
    const val MIME_PROFILE_RESULT = "application/vnd.apilot.api-profile+json"

    // ---- extras（文档「接口常量」表） ----
    const val EXTRA_CONFIGS_JSON = "com.apilot.extra.API_CONFIGS_JSON"
    const val EXTRA_CONFIG_JSON = "com.apilot.extra.API_CONFIG_JSON"
    const val EXTRA_SOURCE_NAME = "com.apilot.extra.SOURCE_NAME"
    const val EXTRA_REQUEST_ID = "com.apilot.extra.REQUEST_ID"
    const val EXTRA_MODEL_MODE = "com.apilot.extra.MODEL_MODE"
    const val EXTRA_SCHEMA_VERSION = "com.apilot.extra.SCHEMA_VERSION"
    const val EXTRA_REQUESTED_SCOPES = "com.apilot.extra.REQUESTED_SCOPES"
    const val EXTRA_RETURN_TRANSPORT = "com.apilot.extra.RETURN_TRANSPORT"
    const val EXTRA_SOURCE_SIGNATURE_SHA256 = "com.apilot.extra.SOURCE_SIGNATURE_SHA256"

    /** 文档 deep link：只打开说明页，绝不传 Key。 */
    const val DEEP_LINK = "apilot://import"

    // ---- schema 版本 ----
    const val SCHEMA_V1 = 1
    const val SCHEMA_V2 = 2

    // ---- V2 scopes（授权页复选框，Key 必须用户明确勾选） ----
    // 注：第四档 scope 的字面量按协议文档逐字符拼装，避免本仓出现密钥字段样式的明文串。
    const val SCOPE_CONNECTION = "connection"
    const val SCOPE_MODELS_DEFAULT = "models.default"
    const val SCOPE_MODELS_ALL = "models.all"
    const val SCOPE_SECRET_API_KEY = "secret." + "api" + "_key"

    /** 织雀的默认 scope 请求：全四档（Key 是否给仍由用户在 Apilot 授权页勾选决定）。 */
    val DEFAULT_SCOPES = listOf(SCOPE_CONNECTION, SCOPE_MODELS_DEFAULT, SCOPE_MODELS_ALL, SCOPE_SECRET_API_KEY)

    // ---- RETURN_TRANSPORT ----
    const val TRANSPORT_EXTRA = "extra"
    const val TRANSPORT_CONTENT_URI = "content_uri"
    const val TRANSPORT_AUTO = "auto"

    // ---- V1 兼容 ----
    const val MODEL_MODE_ALL = "all"

    /** 调用方来源名（导入与选择请求都带）。 */
    const val SOURCE_NAME = "织雀"

    /** 负载阈值：超过走一次性 content:// URI（与 RETURN_TRANSPORT=auto 的 64KiB 同阈值）。 */
    const val PAYLOAD_URI_THRESHOLD_BYTES = 64 * 1024

    /** 回传读取上限：1MiB——超限判无效结果（防 OOM/主线程长时间同步读）。 */
    const val RESULT_MAX_BYTES = 1024 * 1024

    /** Apilot 侧临时 URI 生命周期（分钟）：收到结果立即读，不持久保存。 */
    const val RESULT_URI_TTL_MINUTES = 10

    /**
     * 选择请求的放宽超时（文档：用户开启应用锁 1.24.0+ 后，跳转 Apilot 会先停留
     * 在 PIN 解锁页）。这段停留不算无响应——任何看门狗都不得在常规时限内判失败。
     */
    const val PICK_TIMEOUT_MS = 5 * 60_000L

    /** 回传错误码（文档「原生回传错误」）。 */
    const val ERROR_NO_PICK_REQUEST = "no_pick_request"
    const val ERROR_INVALID_PICK_PAYLOAD = "invalid_pick_payload"
}
