package com.zhique.core.agent

/**
 * Agent 预算三闸（规格 §4.5：轮数/token/时长，默认 5 轮）——
 * [exhausted] 是预算是否触顶的单一事实源，编排器与 UI 都从它取值。
 */
class Budget(
    maxRounds: Int = DEFAULT_MAX_ROUNDS,
    val maxTokens: Long = UNLIMITED,
    val maxDurationMs: Long = UNLIMITED,
    private val now: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxRounds in 1..MAX_ROUNDS_CAP) { "maxRounds 须在 1–$MAX_ROUNDS_CAP" }
    }

    /** 「续 N 轮」放宽轮数闸：maxRounds 可变（上限钳制）。 */
    var maxRounds: Int = maxRounds
        private set

    private var t0 = 0L
    private var tokens = 0L
    private var round = 0

    fun start() {
        t0 = now()
        tokens = 0
        round = 0
    }

    /** 开启一轮（编排器在每次调模型前调用），返回轮号。 */
    fun beginRound(): Int = ++round

    fun recordTokens(n: Int) {
        if (n > 0) tokens += n
    }

    /** 任一闸触顶即停。 */
    fun exhausted(): Boolean =
        round >= maxRounds || tokens >= maxTokens || elapsedMs >= maxDurationMs

    fun extendRounds(n: Int) {
        maxRounds = (maxRounds + n).coerceAtMost(MAX_ROUNDS_CAP)
    }

    val elapsedMs: Long get() = if (t0 == 0L) 0 else now() - t0
    val usedTokens: Long get() = tokens
    val roundsUsed: Int get() = round

    companion object {
        const val DEFAULT_MAX_ROUNDS = 5
        const val MAX_ROUNDS_CAP = 99
        const val UNLIMITED = Long.MAX_VALUE
    }
}
