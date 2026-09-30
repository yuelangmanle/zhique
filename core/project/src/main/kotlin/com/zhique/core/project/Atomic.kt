package com.zhique.core.project

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 关键 JSON 落盘的原子写：同目录临时文件 + ATOMIC_MOVE 替换，失败清理临时文件。 */
internal fun Path.writeStringAtomic(text: String) {
    val tmp = Files.createTempFile(parent, fileName.toString(), ".tmp")
    try {
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, this, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, this, StandardCopyOption.REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        Files.deleteIfExists(tmp)
        throw e
    }
}
