package com.zhique.template.shell

import android.content.Context
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader

/**
 * 模板壳内容源（M6 Task 6.1）：从 APK `assets/project/` 服务项目文件，
 * 域名与织雀本体 [com.zhique.core.web.AssetServer] 一致
 * （`https://appassets.androidplatform.net/assets/`），页面代码零改动可运行。
 */
class TemplateProjectSource(assets: android.content.res.AssetManager) :
    com.zhique.core.web.ProjectSource {

    private val loader = WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler("/assets/", Handler(assets))
        .build()

    override fun intercept(url: android.net.Uri): WebResourceResponse? =
        loader.shouldInterceptRequest(url)

    override fun indexUrl(): String = "https://$DOMAIN/assets/index.html"

    private class Handler(private val assets: android.content.res.AssetManager) :
        WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path.isEmpty() || path.contains("..")) return null
            val stream = runCatching { assets.open("$ASSET_ROOT/$path") }.getOrNull() ?: return null
            val mime = guessMime(path)
            val charset = if (mime.startsWith("text/")) "utf-8" else null
            return WebResourceResponse(mime, charset, stream)
        }

        private fun guessMime(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: FALLBACK[ext] ?: "application/octet-stream"
        }
    }

    companion object {
        const val ASSET_ROOT = "project"
        const val DOMAIN = "appassets.androidplatform.net"

        private val FALLBACK = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "js" to "application/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "wasm" to "application/wasm",
        )
    }
}

/** 上下文便捷构造。 */
fun templateSource(context: Context) = TemplateProjectSource(context.assets)
