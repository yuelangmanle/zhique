package com.zhique.core.publish

/**
 * 凭据脱敏（规格 §6：PAT 永不入日志）：
 * 任何进入日志/异常文本的字符串先过 [redact]——Bearer/token 头与 GitHub 令牌字面
 * （ghp_/gho_/ghu_/ghs_/ghr_/github_pat_ 前缀）一律替换为 `***`。
 */
object SecretRedactor {

    private val PREFIXED = Regex("(ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]+")

    fun redact(message: String): String {
        var out = PREFIXED.replace(message, "***")
        out = Regex("(?i)(bearer|token)\\s+[^\\s,;\"]+").replace(out) { m ->
            m.value.substringBefore(' ') + " ***"
        }
        return out
    }
}
