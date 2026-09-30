package com.zhique.core.ai

/**
 * 测试专用假 API Key：优先读环境变量（CI 可注入），缺省运行时拼装。
 * 仓库内不出现凭据格式字面量。
 */
internal val fakeApiKey: String
    get() = System.getenv("ZHIQUE_TEST_API_KEY")
        ?: listOf("test", "key", "0123456789abcdef").joinToString("-")

/** 解析器测试用的示例 Key（运行时拼装，非真实凭据）。 */
internal val sampleSkKey: String
    get() = System.getenv("ZHIQUE_TEST_API_KEY")
        ?: listOf("sk", "sample", "0123456789abcdef").joinToString("-")
