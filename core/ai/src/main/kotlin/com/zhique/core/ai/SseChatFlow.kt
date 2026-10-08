package com.zhique.core.ai

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 三协议共用的 SSE 会话流管线：发请求 → HTTP 错误分类 → 逐行喂 [SseParser] →
 * 每帧交给协议解析器产出流事件 → EOF 兜底补 Done(STOP)。
 * `newParser` 每次收集时调用一次（解析器可持有单次流内状态，如 Anthropic 工具块元数据）。
 */
internal fun sseChatFlow(
    client: OkHttpClient,
    request: Request,
    newParser: () -> (String) -> List<StreamEvent>,
): Flow<StreamEvent> = flow {
    val call = client.newCall(request)
    // 注册到当前 Job：collector 取消时立即 call.cancel()，中断阻塞 readLine（协作取消）
    currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
    val response = try {
        call.execute()
    } catch (e: IOException) {
        throw AiError.Network("连接失败：${e.message}", e)
    }
    try {
        if (!response.isSuccessful) {
            throw HttpErrors.fromCode(response.code, response.body?.string())
        }
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
        val parse = newParser()
        val parser = SseParser()
        var stopReading = false
        var sawIoError = false
        suspend fun handle(frames: List<SseParser.Frame>) {
            if (stopReading) return // Done 是最后一事件：终态后的残余帧/finish 不再发出
            for (frame in frames) {
                when (frame) {
                    SseParser.Frame.DoneSentinel -> stopReading = true
                    is SseParser.Frame.Data -> for (e in parse(frame.payload)) {
                        if (e is StreamEvent.Done) stopReading = true
                        emit(e)
                        if (stopReading) return
                    }
                }
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = try {
                readCappedLine(reader)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive() // 取消竞态：以 CancellationException 为准
                sawIoError = true
                // 与连接阶段同口径：中段断流也分类为网络错误（退避/UI 文案可识别）
                throw AiError.Network("流中断：${e.message}", e)
            } ?: break
            handle(parser.feed(line + "\n"))
            if (stopReading) break
        }
        handle(parser.finish())
        if (!stopReading && !sawIoError) emit(StreamEvent.Done(StopReason.STOP))
    } finally {
        runCatching { response.close() }
        call.cancel()
    }
}.flowOn(Dispatchers.IO)

/** 单行读取上限：超过即断流（防失控响应撑爆内存）。 */
internal const val SSE_MAX_LINE_CHARS = 1 shl 20

/** 带行长上限的 readLine（返回 null = EOF；\n 终止、剥尾部 \r）。 */
private fun readCappedLine(reader: BufferedReader): String? {
    val sb = StringBuilder(256)
    while (true) {
        val i = reader.read()
        if (i < 0) return if (sb.isEmpty()) null else sb.toString()
        val c = i.toChar()
        if (c == '\n') return sb.toString().removeSuffix("\r")
        sb.append(c)
        if (sb.length > SSE_MAX_LINE_CHARS) throw AiError.Protocol("SSE 行超长（>${SSE_MAX_LINE_CHARS}），已断流")
    }
}

/** 三协议共用默认客户端（SSE 长读 300s）。 */
internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
