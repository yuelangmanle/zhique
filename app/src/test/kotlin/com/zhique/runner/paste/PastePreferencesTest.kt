package com.zhique.runner.paste

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 「粘贴后自动运行/停在预览」通用偏好键（DataStore 落盘，M5 设置屏复用）。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PastePreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `默认停在预览_读写往返`() = runTest {
        val store = PreferenceDataStoreFactory.create(
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()),
            produceFile = { File(tmp.root, "paste.preferences_pb") },
        )
        val prefs = PastePreferences(store)
        assertEquals(false, prefs.autoRun.first(), "默认停在预览")
        prefs.setAutoRun(true)
        assertEquals(true, prefs.autoRun.first())
        prefs.setAutoRun(false)
        assertEquals(false, prefs.autoRun.first())
    }
}
