package com.zhique.core.publish

import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** 发布底座通用错误（非 HTTP 分类错误）。 */
class PublishException(message: String) : Exception(SecretRedactor.redact(message))

/** GitHub REST 错误分类（规格 §6：401 凭据无效 / 403 权限不足 / 422 参数被拒）。 */
sealed class GitHubException(val code: Int, message: String) : Exception(message) {
    /** 401：PAT 无效或过期——引导重新配置。 */
    class Unauthorized(detail: String = "") : GitHubException(401, "GitHub 凭据无效或已过期：$detail 请重新配置 PAT")

    /** 403：fine-grained PAT 权限不足 / 触发限流。 */
    class Forbidden(detail: String) : GitHubException(403, "GitHub 拒绝访问（权限不足或限流）：$detail")

    /** 422：参数被拒（重名/非法 tag 等）。 */
    class Validation(detail: String) : GitHubException(422, "GitHub 校验失败：$detail")

    /** 其他 HTTP 状态。 */
    class Http(code: Int, detail: String) : GitHubException(code, "GitHub 请求失败（$code）：$detail")
}

@Serializable
data class AssetInfo(
    val id: Long,
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val downloadUrl: String? = null,
)

@Serializable
data class ReleaseInfo(
    val id: Long,
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    @SerialName("prerelease") val preRelease: Boolean = false,
    @SerialName("html_url") val htmlUrl: String? = null,
    @SerialName("upload_url") val uploadUrl: String? = null,
    val assets: List<AssetInfo> = emptyList(),
)

@Serializable
data class RepoCreated(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    val owner: Owner,
    @SerialName("html_url") val htmlUrl: String? = null,
) {
    @Serializable
    data class Owner(@SerialName("login") val login: String)
}

/**
 * GitHub REST 底座（OkHttp，Task 7.1）：建仓 / 列 Release / 建 Release / 传资产。
 *
 * - 鉴权 `Authorization: Bearer <pat>`（fine-grained PAT）；
 * - **PAT 永不入日志**：logFilter 只放行「方法 + 脱敏后的 URL」，头部与请求体永不进日志，
 *   异常文本统一过 [SecretRedactor]；
 * - baseUrl 可注入（mockwebserver 测试口径）。
 */
open class GitHubApi(
    private val baseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val log: (String) -> Unit = {},
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** logFilter：只有脱敏后的「方法 URL」一行可进日志，PAT 无出路径。 */
    private fun audit(message: String) {
        log(SecretRedactor.redact(message))
    }

    private fun request(pat: String, builder: Request.Builder.() -> Unit): okhttp3.Response {
        val request = Request.Builder()
            .apply { if (pat.isNotBlank()) header("Authorization", "Bearer $pat") }
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .apply(builder)
            .build()
        audit("${request.method} ${redactUrl(request.url.toString())}")
        return client.newCall(request).execute()
    }

    private fun redactUrl(url: String): String = url

    /** 按 GitHub HTTP 语义分类（401/403/422/其他），正文读取后关闭响应。 */
    private fun classify(response: okhttp3.Response, what: String): Nothing {
        val body = response.use { it.body?.string() }.orEmpty().take(500)
        throw when (response.code) {
            401 -> GitHubException.Unauthorized(body)
            403 -> GitHubException.Forbidden(body)
            422 -> GitHubException.Validation(body)
            else -> GitHubException.Http(response.code, body)
        }.also { audit("$what 失败：${it.message}") }
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    /** 创建远端仓库（autoInit=false：本地项目历史为准，避免首推冲突）。 */
    open fun createRepo(pat: String, name: String, isPrivate: Boolean = true, autoInit: Boolean = false): RepoCreated {
        val payload = json.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            kotlinx.serialization.json.buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(name))
                put("private", kotlinx.serialization.json.JsonPrimitive(isPrivate))
                put("auto_init", kotlinx.serialization.json.JsonPrimitive(autoInit))
            },
        )
        val response = request(pat) { url("$baseUrl/user/repos").post(payload.toRequestBody(JSON)) }
        if (!response.isSuccessful) classify(response, "createRepo")
        return response.use { decode(it.body!!.string()) }
    }

    /** 列远端 Release（含 prerelease/draft 全量，按创建时间倒序）。 */
    open fun listReleases(pat: String, owner: String, repo: String): List<ReleaseInfo> {
        val response = request(pat) { url("$baseUrl/repos/$owner/$repo/releases").get() }
        if (!response.isSuccessful) classify(response, "listReleases")
        return response.use { decode(it.body!!.string()) }
    }

    /** 创建 Release（tag 需已随 push 存在于远端；notes=changelog）。 */
    open fun createRelease(
        pat: String,
        owner: String,
        repo: String,
        tag: String,
        notes: String,
        prerelease: Boolean = false,
    ): ReleaseInfo {
        val payload = json.encodeToString(
            ReleasePayload.serializer(),
            ReleasePayload(tag, tag, notes, prerelease),
        )
        val response = request(pat) {
            url("$baseUrl/repos/$owner/$repo/releases").post(payload.toRequestBody(JSON))
        }
        if (!response.isSuccessful) classify(response, "createRelease")
        return response.use { decode(it.body!!.string()) }
    }

    /** 上传资产到 Release（uploadUrl 来自 ReleaseInfo.upload_url 模板，需剥离 {@?...} 占位）。 */
    open fun uploadAsset(pat: String, uploadUrl: String, file: File, contentType: String = APK_MIME): AssetInfo {
        val base = uploadUrl.substringBefore("{").substringBefore("%7B")
        val url = "$base?name=${java.net.URLEncoder.encode(file.name, Charsets.UTF_8)}"
        val response = request(pat) {
            url(url).put(file.asRequestBody(contentType.toMediaType()))
        }
        if (!response.isSuccessful) classify(response, "uploadAsset")
        return response.use { decode(it.body!!.string()) }
    }

    /** 解析远端仓库 owner/repo（Release 调用入参；RepoBinding 缺失时兜底）。 */
    fun ownerRepo(fullName: String): Pair<String, String> {
        val cleaned = fullName.removePrefix("https://github.com/").removeSuffix(".git")
        val parts = cleaned.split('/')
        require(parts.size >= 2) { "无法解析 owner/repo：${SecretRedactor.redact(fullName)}" }
        return parts[0] to parts[1]
    }

    @Serializable
    private data class ReleasePayload(
        @SerialName("tag_name") val tagName: String,
        val name: String,
        val body: String,
        val prerelease: Boolean,
    )

    companion object {
        const val API_VERSION = "2022-11-28"
        const val APK_MIME = "application/vnd.android.package-archive"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
