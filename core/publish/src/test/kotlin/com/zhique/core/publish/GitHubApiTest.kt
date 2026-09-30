package com.zhique.core.publish

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Task 7.1：GitHubApi（mockwebserver：请求体/header/鉴权/401/403/422 错误分类）。 */
class GitHubApiTest {

    private val pat = "github_pat_" + "T".repeat(22) // 运行时拼装的假 PAT，零凭据字面量

    private fun api(server: MockWebServer, log: (String) -> Unit = {}): GitHubApi =
        GitHubApi(baseUrl = server.url("/").toString().trimEnd('/'), log = log)

    @Test
    fun `createRepo发送名称公私与autoInit并携带Bearer鉴权`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"id":1,"full_name":"alice/demo","owner":{"login":"alice"},"html_url":"https://github.com/alice/demo"}""",
                ),
            )
            val created = api(server).createRepo(pat, name = "demo", isPrivate = true, autoInit = false)
            val recorded = server.takeRequest()
            assertEquals("POST", recorded.method)
            assertEquals("/user/repos", recorded.path)
            assertEquals("Bearer $pat", recorded.getHeader("Authorization"))
            assertEquals("application/vnd.github+json", recorded.getHeader("Accept"))
            val body = recorded.body.readUtf8()
            assertTrue("\"name\":\"demo\"" in body, body)
            assertTrue("\"private\":true" in body, body)
            assertTrue("\"auto_init\":false" in body, body)
            assertEquals("alice/demo", created.fullName)
            assertEquals("alice", created.owner.login)
        }
    }

    @Test
    fun `listReleases解析tagName与prerelease字段`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """[{"id":11,"tag_name":"v0.2.0","name":"beta","body":"更新说明","prerelease":true,
                       "html_url":"u","upload_url":"https://uploads.example.com/releases/11/assets{?name}",
                       "assets":[{"id":5,"name":"app.apk","size":9,"browser_download_url":"d"}]}]""",
                ),
            )
            val releases = api(server).listReleases(pat, owner = "alice", repo = "demo")
            val recorded = server.takeRequest()
            assertEquals("/repos/alice/demo/releases", recorded.path)
            assertEquals("Bearer $pat", recorded.getHeader("Authorization"))
            assertEquals(1, releases.size)
            assertEquals("v0.2.0", releases[0].tagName)
            assertTrue(releases[0].preRelease)
            assertEquals("app.apk", releases[0].assets.first().name)
        }
    }

    @Test
    fun `createRelease发送tag与notes且prerelease可真可假`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"id":12,"tag_name":"v0.1.0","name":"v0.1.0","body":"changelog","prerelease":false}""",
                ),
            )
            val release = api(server).createRelease(pat, "alice", "demo", "v0.1.0", "changelog", prerelease = false)
            val recorded = server.takeRequest()
            assertEquals("/repos/alice/demo/releases", recorded.path)
            val body = recorded.body.readUtf8()
            assertTrue("\"tag_name\":\"v0.1.0\"" in body, body)
            assertTrue("\"prerelease\":false" in body, body)
            assertTrue("\"body\":\"changelog\"" in body, body)
            assertEquals("v0.1.0", release.tagName)
        }
    }

    @Test
    fun `uploadAsset按模板拼name并发送APK字节`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody("""{"id":9,"name":"app-1.apk","size":3}"""),
            )
            val tmp = File(
                java.nio.file.Files.createTempDirectory("assets").toFile(),
                "app-1.apk",
            ).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val asset = api(server).uploadAsset(
                pat,
                server.url("/repos/a/b/releases/1/assets{?name,label}").toString(),
                tmp,
            )
            val recorded = server.takeRequest()
            assertTrue(recorded.path!!.startsWith("/repos/a/b/releases/1/assets?name=app-1.apk"), recorded.path)
            assertEquals("application/vnd.android.package-archive", recorded.getHeader("Content-Type"))
            assertEquals(3, recorded.body.size.toLong())
            assertEquals("app-1.apk", asset.name)
            tmp.delete()
        }
    }

    @Test
    fun `401分类为Unauthorized且消息不含PAT`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Bad credentials"}"""))
            val e = assertFailsWith<GitHubException.Unauthorized> {
                api(server).listReleases(pat, "alice", "demo")
            }
            assertEquals(401, e.code)
            assertFalse(pat in (e.message ?: ""))
        }
    }

    @Test
    fun `403分类为Forbidden`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
            assertFailsWith<GitHubException.Forbidden> { api(server).listReleases(pat, "alice", "demo") }
        }
    }

    @Test
    fun `422分类为Validation并携带远端说明`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(422)
                    .setBody("""{"message":"Validation Failed","errors":[{"message":"name already exists"}]}"""),
            )
            val e = assertFailsWith<GitHubException.Validation> {
                api(server).createRepo(pat, "demo")
            }
            assertEquals(422, e.code)
            assertTrue("Validation" in (e.message ?: ""))
        }
    }

    @Test
    fun `429分类为RateLimited并解析RetryAfter秒数`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(429)
                    .setHeader("Retry-After", "42")
                    .setBody("""{"message":"API rate limit exceeded"}"""),
            )
            val e = assertFailsWith<GitHubException.RateLimited> { api(server).listReleases(pat, "a", "b") }
            assertEquals(429, e.code)
            assertEquals(42, e.retryAfterSeconds)
            assertTrue("限流" in (e.message ?: ""))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(429).setBody("throttled"))
            val e = assertFailsWith<GitHubException.RateLimited> { api(server).listReleases(pat, "a", "b") }
            assertEquals(60, e.retryAfterSeconds, "缺 Retry-After 头给保守缺省 60s")
        }
    }

    @Test
    fun `其他HTTP状态分类为Http`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
            val e = assertFailsWith<GitHubException.Http> { api(server).listReleases(pat, "a", "b") }
            assertEquals(500, e.code)
        }
    }

    @Test
    fun `logFilter只输出脱敏后方法URL且永不包含PAT`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            val lines = mutableListOf<String>()
            api(server) { lines += it }.listReleases(pat, "alice", "demo")
            assertTrue(lines.isNotEmpty(), "应有审计行")
            lines.forEach { line ->
                assertFalse(pat in line, "日志行泄漏 PAT：$line")
                assertFalse("Authorization" in line, "日志行不得含鉴权头：$line")
            }
            assertTrue(lines[0].startsWith("GET http"), lines[0])
        }
    }

    @Test
    fun `ownerRepo解析三种形态`() {
        val api = GitHubApi()
        assertEquals("alice" to "demo", api.ownerRepo("https://github.com/alice/demo"))
        assertEquals("alice" to "demo", api.ownerRepo("https://github.com/alice/demo.git"))
        assertEquals("alice" to "demo", api.ownerRepo("alice/demo"))
    }
}
