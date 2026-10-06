package com.zhique.core.web

/**
 * eruda 高级面板注入器（M9 Task 9.2）：设置开关打开时，项目页加载完成后把
 * 内置 `assets/eruda.js`（化用魔改，MIT）注入页尾并 `eruda.init()`。
 *
 * 与自研采集共存：织雀桥在 document-start 已完成 console/error/fetch/XHR 挂钩，
 * eruda 晚于页面加载注入、只往 window 上挂自己的面板与第二层 console 包装，
 * 事件先经织雀桥记录再进 eruda 展示——采集不被吞、面板不重复建。
 *
 * [GUARD] 页面级标记防重复 init（onPageFinished 可能多次回调）。
 */
object ErudaInjector {

    const val ERUDA_ASSET = "eruda.js"

    /** 页面上下文里的「已加载」标记（随导航重置，每次导航重新注入）。 */
    internal const val GUARD = "__ZHIQUE_ERUDA_LOADED__"

    /** 关闭/无资产 → null（不注入）。 */
    fun pageEndScript(erudaSource: String?): String? {
        if (erudaSource.isNullOrBlank()) return null
        return buildString {
            append("(function(){")
            append("if(window.").append(GUARD).append(")return;")
            append("window.").append(GUARD).append("=true;")
            append(erudaSource)
            append(";if(window.eruda&&window.eruda.init){window.eruda.init();console.log('[eruda] 面板已加载')}else{console.log('[eruda] 加载异常: '+(typeof window.eruda))}")
            append("})();")
        }
    }
}
