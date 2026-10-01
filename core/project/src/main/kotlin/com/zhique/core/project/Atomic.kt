package com.zhique.core.project

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 关键 JSON 落盘的原子写：同目录临时文件 + fsync + ATOMIC_MOVE 替换，失败清理临时文件。 */
internal fun Path.writeStringAtomic(text: String) {
    val tmp = Files.createTempFile(parent, fileName.toString(), ".tmp")
    try {
        // 不用 Files.writeString（API 33+，minSdk 31 直接 NoSuchMethodError）——
        // java.io 流全版本可用，fd.sync() 掉电保护是原子写语义的一部分
        java.io.FileOutputStream(tmp.toFile()).use { fos ->
            fos.write(text.toByteArray(Charsets.UTF_8))
            fos.fd.sync()
        }
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
