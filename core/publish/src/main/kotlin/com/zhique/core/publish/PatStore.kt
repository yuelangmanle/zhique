package com.zhique.core.publish

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * fine-grained PAT 存储（Task 7.1）：AES-256-GCM 加密落盘（CryptoStore），
 * 明文只在调用返回瞬间存在，绝不写日志。
 *
 * 首次引导文案常量随仓库走：仅 repo 权限（Contents: Read and write）+ 官方创建链接。
 */
class PatStore(private val file: File, private val crypto: com.zhique.core.common.crypto.CryptoStore) {

    /** 保存 PAT（覆盖写，密文原子落盘）。 */
    fun save(pat: String) {
        require(pat.isNotBlank()) { "PAT 不能为空" }
        file.parentFile?.mkdirs()
        val tmp = Files.createTempFile(file.parentFile.toPath(), file.name, ".tmp")
        try {
            Files.write(tmp, crypto.encrypt(pat).toByteArray(Charsets.UTF_8))
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }

    /** 读取 PAT（明文，仅调用瞬间存在）；未配置或密文损坏返回 null。 */
    fun pat(): String? {
        if (!file.isFile) return null
        return runCatching { crypto.decrypt(Files.readAllBytes(file.toPath()).decodeToString()) }.getOrNull()
    }

    fun hasPat(): Boolean = pat() != null

    /** 清除（登出/换号）。 */
    fun clear() {
        file.delete()
    }

    companion object {
        /** fine-grained PAT 创建入口（仅 repo 权限）。 */
        const val GUIDE_URL = "https://github.com/settings/personal-access-tokens/new"

        /** 引导说明：权限最小化口径（规格 §4.8）。 */
        const val GUIDE_SCOPES =
            "使用 fine-grained PAT，仅需「仓库 → Contents: Read and write」权限；" +
                "PAT 经 AES-256-GCM 加密后仅存于本机，不上传、不写日志。"
    }
}
