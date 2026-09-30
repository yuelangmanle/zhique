package com.zhique.core.web.debug

/**
 * 桥事件环形缓冲（容量内丢最旧）。[version] 单调递增，作为 UI 侧重算
 * 时间线的 remember key——容量截断后 size 恒定，size 不能当版本用。
 */
class EventBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val list = ArrayList<DebugEvent>(capacity)

    /** 每次 append +1，与列表内容解耦。 */
    var version: Long = 0L
        private set

    /** 当前快照（拷贝，供纯投影消费）。 */
    val events: List<DebugEvent>
        get() = synchronized(this) { list.toList() }

    fun append(event: DebugEvent) {
        synchronized(this) {
            if (list.size >= capacity) list.removeAt(0)
            list.add(event)
            version++
        }
    }

    fun clear() {
        synchronized(this) { list.clear() }
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
