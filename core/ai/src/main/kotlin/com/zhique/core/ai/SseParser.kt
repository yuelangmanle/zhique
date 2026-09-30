package com.zhique.core.ai

/**
 * SSE 帧解析器（增量式，可喂任意切块：半包/粘包均安全）。
 *
 * 语义（RFC 9512 简化子集，三协议够用）：
 * - 事件由空行闭合；同事件内多行 `data:` 以 `\n` 连接；
 * - `data: [DONE]` 哨兵单独成 [SseParser.Frame.DoneSentinel]；
 * - `event:`/`id:`/`retry:` 字段与 `:` 注释行忽略（三协议事件类型都在 data JSON 的 type 字段里）；
 * - CRLF 行尾兼容；流结束经 [finish] 冲刷残行与未闭合事件。
 */
class SseParser {

    sealed interface Frame {
        data class Data(val payload: String) : Frame
        data object DoneSentinel : Frame
    }

    private val pending = StringBuilder()
    private val dataBuf = StringBuilder()
    private var dataBufDirty = false

    /** 喂一个任意切块（可为半行、整行、多行粘包），返回由此闭合出的帧。 */
    fun feed(chunk: String): List<Frame> {
        pending.append(chunk)
        val frames = mutableListOf<Frame>()
        var idx = pending.indexOf('\n')
        while (idx >= 0) {
            val line = pending.substring(0, idx).removeSuffix("\r")
            dispatch(line)?.let(frames::add)
            pending.delete(0, idx + 1)
            idx = pending.indexOf('\n')
        }
        return frames
    }

    /** 流结束：冲刷无换行残行与未闭合事件（OpenAI 部分实现不发 [DONE] 直接 EOF）。 */
    fun finish(): List<Frame> {
        val frames = mutableListOf<Frame>()
        if (pending.isNotEmpty()) {
            val line = pending.toString().removeSuffix("\r")
            pending.setLength(0)
            dispatch(line)?.let(frames::add)
        }
        flushEvent()?.let(frames::add)
        return frames
    }

    private fun dispatch(line: String): Frame? {
        if (line.isEmpty()) return flushEvent()
        if (line.startsWith(":")) return null // SSE 注释（keep-alive）
        val colon = line.indexOf(':')
        val field = if (colon < 0) line else line.substring(0, colon)
        if (field != "data") return null
        val value = if (colon < 0) "" else line.substring(colon + 1).removePrefix(" ")
        if (dataBufDirty) dataBuf.append('\n')
        dataBuf.append(value)
        dataBufDirty = true
        return null
    }

    private fun flushEvent(): Frame? {
        if (!dataBufDirty) return null
        val payload = dataBuf.toString()
        dataBuf.setLength(0)
        dataBufDirty = false
        return if (payload.trim() == DONE) Frame.DoneSentinel else Frame.Data(payload)
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}
