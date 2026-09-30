package com.zhique.core.paste

/** 兼容性提示类别。 */
enum class CompatKind {
    /** W3C 标准权限 API——运行期经 WebChromeClient 权限桥路由（规格 §2.1 F6）。 */
    STANDARD_PERMISSION_API,

    /** `zq.*` 调用——M4/M8 与 native 注册表核对（本任务只产出数据，不做注册表校验）。 */
    ZQ_CALL,
}

/** 一条兼容性提示：[api] 为命中的 API 名（zq 调用已去空格规范化）。 */
data class CompatHint(val api: String, val kind: CompatKind)

/**
 * 反向兜底钩子（计划 Task 2.2 Step 6）：组装后扫描
 * `navigator.mediaDevices | geolocation | getUserMedia | Notification.` 与 `zq.ns.fn` 调用。
 * 从粘贴管道的静态视角，所有 zq 调用一律上报为「待核对」，由 M4 权限矩阵 / M8 提示词桥消费。
 */
object CompatScanner {

    private val STANDARD_APIS = listOf(
        "navigator.mediaDevices",
        "navigator.geolocation",
        "getUserMedia",
        "Notification.",
    )
    private val ZQ_CALL = Regex("""\bzq\s*\.\s*[A-Za-z_]\w*\s*\.\s*[A-Za-z_]\w*""")
    private val WS = Regex("\\s+")

    fun scan(html: String): List<CompatHint> {
        val out = mutableListOf<CompatHint>()
        for (api in STANDARD_APIS) {
            if (api in html) out += CompatHint(api, CompatKind.STANDARD_PERMISSION_API)
        }
        ZQ_CALL.findAll(html)
            .map { WS.replace(it.value, "") }
            .distinct()
            .forEach { out += CompatHint(it, CompatKind.ZQ_CALL) }
        return out
    }
}
