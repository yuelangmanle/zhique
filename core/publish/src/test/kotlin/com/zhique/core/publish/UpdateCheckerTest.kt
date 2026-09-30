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
    ): String {
        val assets = apk?.let {
            ""","assets":[{"id":9,"name":"$it","size":6,"browser_download_url":"https://d/$it"}]"""
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
}
