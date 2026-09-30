package com.zhique.core.web

import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * 项目目录 → `https://appassets.androidplatform.net/assets/` 的本地资源服务（规格 §4.2）。
 * `file://` 一律禁止；路径逃逸（`..` / canonical 越界）与缺失文件一律 404（handle 返回 null）。
 */
class AssetServer(projectDir: File) {

    private val loader = WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler("/assets/", ProjectDirHandler(projectDir))
        .build()

    /** 交给 WebViewClient.shouldInterceptRequest。 */
    fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)

    fun indexUrl(): String = "https://$DOMAIN/assets/index.html"

    private class ProjectDirHandler(private val root: File) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            if (path.isEmpty() || path.contains("..")) return null
            val f = File(root, path)
            if (!f.canonicalPath.startsWith(root.canonicalPath + File.separator) || !f.isFile) {
                return null
            }
            val mime = guessMime(path)
            val charset =
                if (mime.startsWith("text/") ||
                    mime in TEXT_LIKE_MIMES
                ) "utf-8" else null
            return WebResourceResponse(mime, charset, f.inputStream())
        }

        private fun guessMime(path: String): String {
            val ext = path.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: FALLBACK_MIMES[ext] ?: "application/octet-stream"
        }
    }

    private companion object {
        const val DOMAIN = "appassets.androidplatform.net"

        val TEXT_LIKE_MIMES = setOf(
            "application/javascript",
            "application/json",
            "image/svg+xml",
            "application/wasm",
        )

        val FALLBACK_MIMES = mapOf(
            "html" to "text/html",
            "htm" to "text/html",
            "js" to "application/javascript",
            "mjs" to "application/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "wasm" to "application/wasm",
        )
    }
}
