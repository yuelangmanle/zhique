package com.zhique.core.publish

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Task 7.3：UpdateChecker——通道语义/semver 比较/资产挑选/APK 下载与进度。 */
class UpdateCheckerTest {

    private fun releasesJson(vararg items: String): String = "[${items.joinToString(",")}]"

    private fun release(
        tag: String,
        pre: Boolean = false,
        draft: Boolean = false,
        apk: String? = "zhique-$tag.apk",
        digest: String? = null,
    ): String {
        val d = digest?.let { ""","digest":"$it"""" } ?: ""
        val assets = apk?.let {
            ""","assets":[{"id":9,"name":"$it","size":6,"browser_download_url":"https://d/$it"$d}]"""
        } ?: ""","assets":[]"""
        return """{"id":${tag.hashCode()},"tag_name":"$tag","name":"$tag","body":"说明 $tag","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/o/r/tag/$tag"$assets}"""
    }

    private fun checker(server: MockWebServer): UpdateChecker =
        UpdateChecker(GitHubApi(baseUrl = server.url("/").toString().trimEnd('/')))

    @Test
    fun `stable通道取非prerelease最新且比较semver`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    releasesJson(release("v0.1.0"), release("v0.2.0-beta1", pre = true), release("v0.3.0")),
                ),
            )
            val info = checker(server).check(UpdateChecker.CHANNEL_STABLE, currentVersion = "0.1.0")
            assertNotNull(info)
            assertEquals("v0.3.0", info.tagName)
            assertFalse(info.prerelease)
        }
    }

    @Test
    fun `beta通道含prerelease且draft恒不可见`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    releasesJson(release("v0.3.0"), release("v0.4.0-beta2", pre = true), release("v0.9.0", draft = true)),
                ),
            )
            val beta = checker(server).check(UpdateChecker.CHANNEL_BETA, currentVersion = "0.3.0")
            assertEquals("v0.4.0-beta2", beta!!.tagName)
            assertTrue(beta.prerelease)

            MockWebServer().use { s2 ->
                s2.enqueue(MockResponse().setBody(releasesJson(release("v0.9.0", draft = true))))
                assertNull(checker(s2).check(UpdateChecker.CHANNEL_BETA, currentVersion = "0.1.0"), "draft 不参与更新")
            }
        }
    }

    @Test
    fun `当前已是最新或远端更旧返回null`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(releasesJson(release("v0.1.0"))))
            assertNull(checker(server).check(UpdateChecker.CHANNEL_STABLE, currentVersion = "0.1.0"))
        }
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(releasesJson(release("v0.2.0"))))
            assertNull(checker(server).check(UpdateChecker.CHANNEL_BETA, currentVersion = "0.3.0"))
        }
    }

    @Test
    fun `无APK资产的版本仍给出Info但assetUrl为空`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(releasesJson(release("v0.5.0", apk = null))))
            val info = checker(server).check(UpdateChecker.CHANNEL_STABLE, "0.1.0")
            assertEquals("v0.5.0", info!!.tagName)
            assertNull(info.assetUrl)
        }
    }

    @Test
    fun `semver解析与比较边界`() {
        assertTrue(SemVer.isNewer("v0.2.0", "0.1.9"))
        assertTrue(SemVer.isNewer("1.0", "0.9.9"))
        assertTrue(SemVer.isNewer("0.2.0-beta1", "0.1.9"), "预发布标签剥离后比较数值段")
        assertFalse(SemVer.isNewer("0.1.0", "0.1.0"))
        assertFalse(SemVer.isNewer("0.1.0", "0.2.0"))
        assertFalse(SemVer.isNewer("not-a-version", "0.1.0"))
        // 复审补测：超大数字段不抛 NumberFormatException，解析为 null 视为不可比较
        assertNull(SemVer.parse("v99999999999.0"))
        assertFalse(SemVer.isNewer("v99999999999.0", "0.1.0"))
        assertNull(SemVer.parse("v1.2.99999999999"))
        assertFalse(SemVer.isNewer("v1.0", "garbage"))
        assertEquals(listOf(1, 2, 3), SemVer.parse("v1.2.3"))
    }

    @Test
    fun `downloadApk流式落盘并回调进度`() {
        MockWebServer().use { server ->
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val dir = File(System.getProperty("java.io.tmpdir"), "updl-${System.nanoTime()}")
            val info = UpdateInfo(
                tagName = "v0.2.0", version = "0.2.0", notes = "", prerelease = false,
                htmlUrl = null, assetName = "zhique-0.2.0.apk",
                assetUrl = server.url("/zhique-0.2.0.apk").toString(),
            )
            val progresses = mutableListOf<Float>()
            val file = checker(server).downloadApk(info, dir) { progresses += it }
            assertEquals(4096, file.length())
            assertTrue(file.name.endsWith("0.2.0.apk"))
            assertTrue(progresses.isNotEmpty() && progresses.last() in 0.99f..1.01f)
            assertTrue(!File(dir, "zhique-0.2.0.apk.part").exists(), ".part 残留须清理")
            dir.deleteRecursively()
        }
    }

    // ---- 下载 sha256 校验（M7 遗留注释项收口：API 给 digest 则必校） ----

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    @Test
    fun `check透传资产digest到UpdateInfo`() {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    releasesJson(release("v0.2.0", digest = "sha256:abc123")),
                ),
            )
            val info = checker(server).check(UpdateChecker.CHANNEL_STABLE, "0.1.0")
            assertEquals("sha256:abc123", info!!.assetDigest)
        }
    }

    @Test
    fun `digest匹配下载成功`() {
        MockWebServer().use { server ->
            val bytes = "apk-bytes-1".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val dir = File(System.getProperty("java.io.tmpdir"), "updl-${System.nanoTime()}")
            val info = UpdateInfo(
                tagName = "v0.2.0", version = "0.2.0", notes = "", prerelease = false,
                htmlUrl = null, assetName = "a.apk",
                assetUrl = server.url("/a.apk").toString(),
                assetDigest = "sha256:${sha256(bytes)}",
            )
            val file = checker(server).downloadApk(info, dir)
            assertEquals(bytes.size.toLong(), file.length())
            dir.deleteRecursively()
        }
    }

    @Test
    fun `digest不匹配抛错且残留丢弃`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("tampered-or-truncated"))
            val dir = File(System.getProperty("java.io.tmpdir"), "updl-${System.nanoTime()}")
            val info = UpdateInfo(
                tagName = "v0.2.0", version = "0.2.0", notes = "", prerelease = false,
                htmlUrl = null, assetName = "a.apk",
                assetUrl = server.url("/a.apk").toString(),
                assetDigest = "sha256:${"0".repeat(64)}",
            )
            val e = runCatching { checker(server).downloadApk(info, dir) }
            assertTrue(e.isFailure, "sha256 不匹配必须拒绝")
            assertTrue(e.exceptionOrNull()!!.message!!.contains("sha256"))
            assertTrue(!File(dir, "zhique-0.2.0.apk").exists(), "校验失败的包须丢弃")
            assertTrue(!File(dir, "zhique-0.2.0.apk.part").exists(), ".part 同样不得残留")
            dir.deleteRecursively()
        }
    }

    @Test
    fun `无digest跳过校验照常落盘`() {
        MockWebServer().use { server ->
            val bytes = "apk-bytes-no-digest".toByteArray()
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            val dir = File(System.getProperty("java.io.tmpdir"), "updl-${System.nanoTime()}")
            // digest=null（老 API 响应）与非 sha256 形态都跳过校验
            for (digest in listOf<String?>(null, "md5:deadbeef")) {
                val info = UpdateInfo(
                    tagName = "v0.2.0", version = "0.2.0", notes = "", prerelease = false,
                    htmlUrl = null, assetName = "a.apk",
                    assetUrl = server.url("/a.apk").toString(),
                    assetDigest = digest,
                )
                server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
                val file = checker(server).downloadApk(info, dir)
                assertEquals(bytes.size.toLong(), file.length())
            }
            dir.deleteRecursively()
        }
    }
}
