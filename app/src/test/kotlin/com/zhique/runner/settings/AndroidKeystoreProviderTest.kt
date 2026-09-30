package com.zhique.runner.settings

import com.zhique.core.common.crypto.KeyProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** AndroidKeystoreProvider 契约冒烟（真实 Keystore 操作留给集成验证，Robolectric 无系统安全区）。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidKeystoreProviderTest {

    @Test
    fun `实现KeyProvider契约`() {
        val p: KeyProvider = AndroidKeystoreProvider()
        assertTrue(p is AndroidKeystoreProvider)
    }

    @Test
    fun `别名常量稳定且非凭据`() {
        assertEquals("zhique-master-aes", AndroidKeystoreProvider.DEFAULT_ALIAS)
        assertEquals("AndroidKeyStore", AndroidKeystoreProvider.ANDROID_KEYSTORE)
    }
}
