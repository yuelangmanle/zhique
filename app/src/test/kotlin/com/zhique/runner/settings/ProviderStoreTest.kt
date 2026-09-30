package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.common.crypto.CryptoStore
import com.zhique.core.common.crypto.KeyProvider
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Provider 配置仓：Key 密文落库、增删改查往返（Task 3.6）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeKeyProvider : KeyProvider {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun masterKey() = key
    }

    private fun newStore(): ProviderStore {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "settings.preferences_pb") },
        )
        return ProviderStore(dataStore, CryptoStore(FakeKeyProvider()))
    }

    private fun config(keyCipher: String = "") = ProviderConfig(
        id = "p1",
        name = "测试服务商",
        protocol = "openai_compatible",
        baseUrl = "https://example.invalid",
        keyCipher = keyCipher,
        model = "test-model",
        maxOutputManual = 0,
    )

    @Test
    fun `保存与读取往返`() = runTest {
        val store = newStore()
        store.upsert(config(keyCipher = store.encryptKey("plain-key-123")))
        val loaded = store.list().single()
        assertEquals("测试服务商", loaded.name)
        assertEquals("test-model", loaded.model)
        assertEquals(0, loaded.maxOutputManual, "0 = 按模型默认")
        assertEquals("plain-key-123", store.decryptKey(loaded), "密文可解回明文")
    }

    @Test
    fun `Key密文落库不存明文`() = runTest {
        val store = newStore()
        val cipher = store.encryptKey("plain-key-123")
        assertNotEquals("plain-key-123", cipher)
        assertTrue(cipher.isNotEmpty())
        store.upsert(config(keyCipher = cipher))
        val raw = java.io.File(tmp.root, "settings.preferences_pb").readBytes().decodeToString()
        assertTrue("plain-key-123" !in raw, "明文 Key 不得出现在落盘文件")
    }

    @Test
    fun `删除与更新`() = runTest {
        val store = newStore()
        store.upsert(config())
        store.upsert(config().copy(name = "改名"))
        assertEquals(listOf("改名"), store.list().map { it.name }, "同 id 覆盖更新")
        store.remove("p1")
        assertNull(store.get("p1"))
        assertTrue(store.list().isEmpty())
        assertEquals(emptyList(), store.providers.first())
    }
}
