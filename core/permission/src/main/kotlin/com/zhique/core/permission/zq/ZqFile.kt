package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.file：项目沙盒直读写 + SAF pick/save（规格 §4.6「完整文件系统读写」第一批）。
 *
 * - `zq.file.read({path})` / `write({path, content})` / `list({path?})`：
 *   限项目沙盒目录（[ZqPaths] 越界拒绝）；
 * - `zq.file.pick({mime?})` / `save({name, mime?, content})`：SAF，经
 *   [SafGateway]（Activity Result 在 :app 侧注册；未接入时明确报错不假装成功）。
 */
class ZqFile : ZqCapability {

    override val ns = "file"
    override val required = Capability.FILE
    override val methods = listOf("read", "write", "list", "pick", "save")

    override fun why(fn: String): String = when (fn) {
        "pick" -> "从手机选择一个文件读入网页"
        "save" -> "把网页内容保存到手机存储（系统保存对话框）"
        else -> "读写本项目目录内的文件（限项目沙盒）"
    }

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement = when (fn) {
        "read" -> {
            val f = ZqPaths.resolveInSandbox(env.projectDir, args.zqText("path"))
            // 内联回传大小上限（审查修复 Minor #7）：超出回 rejected "too large"
            require(f.length() <= ZqLimits.MAX_INLINE_BYTES) { "too large" }
            buildJsonObject { put("content", f.readText()) }
        }
        "write" -> {
            val rel = args.zqText("path")
            val content = args.zqOptText("content") ?: ""
            val f = ZqPaths.resolveInSandbox(env.projectDir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content)
            buildJsonObject { put("path", rel); put("bytes", f.length()) }
        }
        "list" -> {
            val dir = args.zqOptText("path")
                ?.let { ZqPaths.resolveInSandbox(env.projectDir, it) }
                ?: env.projectDir
            require(dir.isDirectory) { "不是目录: ${args.zqOptText("path")}" }
            val entries = (dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name },
            ) ?: emptyList()).map { child ->
                buildJsonObject {
                    put("name", child.name)
                    put("dir", child.isDirectory)
                    put("size", child.length())
                }
            }
            buildJsonObject { put("entries", JsonArray(entries)) }
        }
        "pick" -> env.saf?.pick(args.zqOptText("mime"))
            ?: throw IllegalStateException("SAF 未接入：请使用项目沙盒读写（zq.file.read/write）")
        "save" -> env.saf?.save(args.zqText("name"), args.zqOptText("mime"), args.zqOptText("content") ?: "")
            ?: throw IllegalStateException("SAF 未接入：请使用项目沙盒读写（zq.file.read/write）")
        else -> throw IllegalArgumentException("zq.file 未知方法: $fn")
    }
}
