package com.zhique.core.ai

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        suspend fun handle(frames: List<SseParser.Frame>) {
            for (frame in frames) {
                when (frame) {
                    SseParser.Frame.DoneSentinel -> stopReading = true
                    is SseParser.Frame.Data -> for (e in parse(frame.payload)) {
                        if (e is StreamEvent.Done) stopReading = true
                        emit(e)
                    }
                }
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            val line = reader.readLine() ?: break
            handle(parser.feed(line + "\n"))
            if (stopReading) break
        }
        handle(parser.finish())
        if (!stopReading) emit(StreamEvent.Done(StopReason.STOP))
    } finally {
        runCatching { response.close() }
        call.cancel()
    }
}.flowOn(Dispatchers.IO)

/** 三协议共用默认客户端（SSE 长读 300s）。 */
internal fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(300, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()
