package com.zhique.core.permission

import com.zhique.core.paste.PromptBridge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M8 提示词桥防漂移（Task 8.2）：zq-docs 文档清单必须与 Capability 枚举逐 id 一致。
 * 提示词桥的文档资产落后或超前于权限桥能力注册，都会让外部 AI 拿到错误契约。
 */
class ZqDocsConsistencyTest {

    @Test
    fun 提示词桥KNOWN_APIS与Capability枚举逐id一致() {
        val enumIds = Capability.ALL.map { it.id }.sorted()
        assertEquals(enumIds, PromptBridge.KNOWN_APIS.sorted())
    }

    @Test
    fun 每个Capability的id文档都能被提示词桥加载() {
        Capability.ALL.forEach { cap ->
            val doc = PromptBridge.DEFAULT_DOC_LOADER(cap.id)
            assertTrue(!doc.isNullOrBlank(), "Capability ${cap.id}（${cap.title}）缺 zq-docs 文档")
            assertTrue("zq.${cap.id}" in doc!!, "文档 ${cap.id} 未提及 zq.${cap.id} 命名空间")
        }
    }
}
