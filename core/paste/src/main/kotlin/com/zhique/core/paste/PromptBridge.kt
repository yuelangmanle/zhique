package com.zhique.core.paste

/**
 * 提示词桥（Task 8.2，规格 §4.10 / F10）：
 *
 * 在 App 内生成「织雀提示词」，用户复制或系统分享到任意外部 AI：
 *
 * ```
 * 用户第一句（想法）
 * + 固定运行环境段（安卓 Chromium WebView / WebGL·WebGPU / 单文件完整 HTML / 权限被拒不崩溃）
 * + 按项目勾选的 API 段落（只带用得上的 zq.* 文档，模板资产 zq-docs 目录下的 md 文件）
 * + 固定输出要求（单文件、无 markdown 围栏、权限拒绝优雅降级）
 * ```
 *
 * 文档资产与 :core:permission 十能力（[Capability] 语义，此处按 id 字符串解耦）
 * 同步维护；一致性防漂移测试在 :core:paste（资产完备性）与
 * :core:permission（枚举×资产交叉比对）各自把守。
 *
 * [docLoader] 注入文档读取（生产走 classpath 资源；测试可注入假文档）。
 */
class PromptBridge(
    private val docLoader: (String) -> String? = DEFAULT_DOC_LOADER,
) {

    /** 生成织雀提示词。[selectedApis] 为能力 id（zq 命名空间名）；未知 id 忽略不进正文。 */
    fun generate(firstSentence: String, selectedApis: Collection<String>): String {
        val idea = firstSentence.trim().ifBlank { "（在这里描述你想做的小工具）" }
        return buildString {
            appendLine(HEADER)
            appendLine()
            appendLine("## 我要做的事")
            appendLine(idea)
            appendLine()
            appendLine("## 运行环境")
            ENVIRONMENT_LINES.forEach { appendLine(it) }
            appendLine()
            val apis = selectedApis.map { it.trim() }.filter { it in KNOWN_APIS }.distinct()
            if (apis.isNotEmpty()) {
                appendLine("## 可用的织雀 API（zq.*）")
                appendLine()
                apis.forEach { id ->
                    appendLine("### zq.$id")
                    appendLine(docLoader(id)?.trim() ?: "（文档缺失：以运行环境描述为准，勿臆造 API。）")
                    appendLine()
                }
            }
            appendLine("## 输出要求")
            OUTPUT_LINES.forEach { appendLine(it) }
        }.trimEnd() + "\n"
    }

    companion object {

        const val HEADER = "# 织雀提示词（Zhique Prompt Bridge）"

        /** 运行环境段（规格 F10：Chromium WebView / WebGL·WebGPU 可用 / 单文件完整 HTML / 权限被拒不崩溃）。 */
        val ENVIRONMENT_LINES = listOf(
            "- 目标平台：安卓 App 内置 WebView（Chromium 内核，随系统 WebView 更新）。",
            "- WebGL 可用；WebGPU 视设备可用（不可用时请自动降级 WebGL，并在界面标注，不要硬依赖 WebGPU）。",
            "- 页面直接作为本地文件加载：不要依赖本地服务器、构建步骤或 Node.js。",
            "- 标准 Web API（DOM/Blob/Canvas/fetch 等）直接可用；手机原生能力（相机/定位等）经下文 zq.* 桥接 API 调用。",
            "- 权限被用户拒绝时必须优雅降级：给出可读提示并提供替代路径，不得崩溃、白屏或死循环重试。",
        )

        /** 输出要求段（规格 F10：单文件完整 HTML、无 markdown 围栏、拒绝不崩溃）。 */
        val OUTPUT_LINES = listOf(
            "- 只输出一个完整 HTML 文件的源码：从 <!DOCTYPE html> 开始，到 </html> 结束。",
            "- 不要输出 markdown 代码围栏（```），不要附带任何解释性文字。",
            "- CSS 与 JavaScript 全部内联在同一文件中；不引用本地文件路径，外链 CDN 需有离线兜底。",
            "- 严格按上文 zq.* 文档调用；文档之外的桥接 API 一律不要臆造。",
            "- 界面文案使用中文，布局适配手机竖屏。",
        )

        /**
         * 支持的 zq 能力 id（与 :core:permission 十能力同步）。
         * 防漂移：资产文件清单 = 本清单；:core:permission 侧测试另与 Capability 枚举交叉比对。
         */
        val KNOWN_APIS = listOf(
            "camera", "mic", "file", "location", "sensor",
            "bluetooth", "notification", "clipboard", "share", "screen",
        )

        /** 文档资源路径（:core:paste 的 java resources，打包后随 APK 合并）。 */
        fun docPath(id: String): String = "zq-docs/$id.md"

        /** 默认文档加载：classpath 资源。 */
        val DEFAULT_DOC_LOADER: (String) -> String? = { id ->
            runCatching {
                PromptBridge::class.java.classLoader?.getResourceAsStream(docPath(id))
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            }.getOrNull()
        }
    }
}
