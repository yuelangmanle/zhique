package com.zhique.runner.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.zhique.core.ai.ModelRef
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 质量审查修复回归（fix 11）：ProviderStore / RoleBindingStore 读改写原子化（edit 单事务内完成）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewFixStoresTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `ProviderStore并发upsert不丢更新`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "atomic-settings.preferences_pb") },
        )
        val store = ProviderStore(
            dataStore,
            com.zhique.core.common.crypto.CryptoStore(object : com.zhique.core.common.crypto.KeyProvider {
                private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
                override fun masterKey() = key
            }),
        )
        coroutineScope {
            (1..20).map { i ->
                launch(Dispatchers.IO) {
                    store.upsert(ProviderConfig(id = "p$i", name = "n$i", protocol = "openai_compatible", baseUrl = "https://x.invalid", keyCipher = "c", model = "m"))
                }
            }.forEach { it.join() }
        }
        assertEquals(20, store.list().size, "edit 单事务内读改写：并发 upsert 不得互相覆盖")
    }

    @Test
    fun `RoleBindingStore并发bind不丢更新`() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "atomic-roles.preferences_pb") },
        )
        val store = RoleBindingStore(dataStore)
        coroutineScope {
            com.zhique.core.ai.AgentRole.entries.map { role ->
                launch(Dispatchers.IO) { store.bind(role.name, ModelRef("p-default", "m-${role.name}")) }
            }.forEach { it.join() }
        }
        assertEquals(com.zhique.core.ai.AgentRole.entries.size, store.current().roles.size, "并发 bind 五槽全落库")
        assertTrue(store.current().roles.values.all { it.model.startsWith("m-") })
    }
}
