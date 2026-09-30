package com.zhique.core.web

import android.net.Uri
import android.webkit.WebResourceResponse

/**
 * 项目内容源抽象（M6）：织雀本体从磁盘项目目录服务（[AssetServer]），
 * 导出模板壳从 APK `assets/project/` 服务（模板壳侧实现）。
 * [WebViewHost] 运行时两者共用，行为一致。
 */
interface ProjectSource {

    /** 返回 null = 404（路径逃逸/缺失文件一律拒绝）。 */
    fun intercept(url: Uri): WebResourceResponse?

    /** 项目入口页 URL（https 域名，file:// 一律禁止）。 */
    fun indexUrl(): String
}
