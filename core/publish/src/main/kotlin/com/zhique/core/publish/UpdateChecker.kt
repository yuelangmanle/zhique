package com.zhique.core.publish

import java.io.File
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/** semver 比较（发布自更新口径）：按数字段逐段比，缺段补 0；前缀 v 忽略。 */
object SemVer {
    /** 解析 "v1.2.3" → [1,2,3]；非法返回 null。 */
    fun parse(version: String): List<Int>? {
        val cleaned = version.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        if (parts.any { it.isEmpty() || it.any { c -> !c.isDigit() } }) return null
        return parts.map { it.toInt() }
    }

    /** candidate 是否比 current 新（任一更大数据段胜出；等价 false）。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parse(candidate) ?: return false
        val cur = parse(current) ?: return false
        val n = maxOf(c.size, cur.size)
        for (i in 0 until n) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}

/** 一条可升级的更新（自更新检查结果）。 */
data class UpdateInfo(
    val tagName: String,
    val version: String,
    val notes: String,
    val prerelease: Boolean,
    val htmlUrl: String?,
    val assetName: String?,
    val assetUrl: String?,
)

/**
 * 自更新检查器（Task 7.3，规格 §4.8/§7「更新通道」）：
 * stable=非 prerelease 且非 draft 的最新一条；beta=含 prerelease 的最新一条
 * → semver 比当前新才算可升级。织雀自身仓库常量随开源发布件维护（M10）。
 */
open class UpdateChecker(
    private val api: GitHubApi,
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val owner: String = ZHIQUE_OWNER,
    private val repo: String = ZHIQUE_REPO,
) {

    /** 按通道检查；无可升级版本返回 null。 */
    open fun check(channel: String, currentVersion: String): UpdateInfo? {
        val pat = "" // 织雀自身仓库是公开的：listReleases 走匿名只需 pat 参数可空
        val releases = runCatching { api.listReleases(pat, owner, repo) }.getOrDefault(emptyList())
        val visible = releases.filter { !it.draft && (channel == CHANNEL_BETA || !it.preRelease) }
        val latest = visible.firstOrNull { SemVer.isNewer(it.tagName, currentVersion) } ?: return null
        val apk = latest.assets.firstOrNull { it.name.endsWith(".apk") }
        return UpdateInfo(
            tagName = latest.tagName,
            version = latest.tagName.removePrefix("v"),
            notes = latest.body.orEmpty(),
            prerelease = latest.preRelease,
            htmlUrl = latest.htmlUrl,
            assetName = apk?.name,
            assetUrl = apk?.downloadUrl,
        )
    }

    /**
     * 下载更新 APK 到 [targetDir]（Downloads 域由调用方传入），进度回调 0..1。
     * 返回落盘文件；流式写盘，不整包驻留内存。
     *
     * 安全口径（Minor-7，明确不声明为已校验）：APK 经 api.github.com / github.com
     * HTTPS 获取，完整性目前仅依赖 TLS 信任链，**暂无内容 hash/签名校验**；
     * 安装侧由 PackageInstaller 同签名约束兜底（决策29）。
     */
    open fun downloadApk(
        info: UpdateInfo,
        targetDir: File,
        onProgress: (Float) -> Unit = {},
    ): File {
        val url = requireNotNull(info.assetUrl) { "该版本未附带 APK 资产" }
        targetDir.mkdirs()
        val target = File(targetDir, "zhique-${info.version}.apk")
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "下载失败（${response.code}）" }
            val body = response.body ?: throw PublishException("下载失败：空响应体")
            val total = body.contentLength()
            body.byteStream().use { input ->
                File(target.absolutePath + ".part").outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var done = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toDouble() / total).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
        }
        val part = File(target.absolutePath + ".part")
        check(part.renameTo(target)) { "下载落盘失败" }
        return target
    }

    companion object {
        const val CHANNEL_STABLE = "stable"
        const val CHANNEL_BETA = "beta"

        /** 织雀自身的开源仓库（X6 自更新演练口径；M10 发布件对齐）。 */
        const val ZHIQUE_OWNER = "zhique-app"
        const val ZHIQUE_REPO = "zhique"
    }
}
