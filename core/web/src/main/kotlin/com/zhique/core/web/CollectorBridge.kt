package com.zhique.core.web

import android.webkit.JavascriptInterface
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * JS 桥的 native 端（`window.ZhiqueNative`）：接收织雀桥 JS 的 `onEvent(json)` 原始事件。
 * 解析与分流交给 [WebViewHost]。
 *
 * replay=64：晚订阅的 UI（如重建的抽屉）能补最近一段事件；
 * DROP_OLDEST：事件风暴时绝不阻塞 JS 线程。
 */
class CollectorBridge {

    private val _raw = MutableSharedFlow<String>(
        replay = 64,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val raw: SharedFlow<String> = _raw

    /** 任一桥事件到达时回调（宿主据此标记 JS 桥存活、关闭 WebChromeClient 兜底采集）。 */
    var onEventArrived: (() -> Unit)? = null

    @JavascriptInterface
    fun onEvent(json: String) {
        onEventArrived?.invoke()
        _raw.tryEmit(json)
    }
}
