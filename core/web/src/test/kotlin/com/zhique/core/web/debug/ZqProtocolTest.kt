package com.zhique.core.web.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** zq 调用协议的 native 回写 JS 构造（未命中能力立即拒绝 / 超时兜底）。 */
class ZqProtocolTest {

    @Test
    fun `rejectJs格式正确`() {
        assertEquals(
            "window.__zqResolve && __zqResolve(7, false, \"timeout\")",
            ZqProtocol.rejectJs(7, "timeout"),
        )
    }

    @Test
    fun `resolveJs转义引号与反斜杠`() {
        assertEquals(
            "window.__zqResolve && __zqResolve(1, true, \"{\\\"a\\\":1}\")",
            ZqProtocol.resolveJs(1, true, "{\"a\":1}"),
        )
        assertEquals(
            "window.__zqResolve && __zqResolve(2, false, \"a\\\\b\")",
            ZqProtocol.rejectJs(2, "a\\b"),
        )
    }

    @Test
    fun `id缺失时用-1不产生非法JS`() {
        assertEquals(
            "window.__zqResolve && __zqResolve(-1, false, \"no id\")",
            ZqProtocol.rejectJs(null, "no id"),
        )
        assertEquals(30_000L, ZqProtocol.TIMEOUT_MS)
    }
}
