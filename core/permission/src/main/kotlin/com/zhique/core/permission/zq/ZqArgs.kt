package com.zhique.core.permission.zq

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * zq_call 参数解析与校验助手。
 *
 * 织雀桥（zhique-bridge.js）把页面调用打包成 `args: JSON.stringify([...])`
 * —— 一个 JSON 数组，约定第一个元素是选项对象（如 `zq.camera.capture({...})`）。
 */
object ZqArgs {

    private val json = Json { ignoreUnknownKeys = true }

    /** 解析参数数组；null/空 → 空数组；损坏 JSON 抛错（调度层回 rejected，不崩）。 */
    fun parse(raw: String?): JsonArray =
        raw?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())

    /** 首个对象参数（选项对象）；缺省/类型不符 → 空对象。 */
    fun firstObject(raw: String?): JsonObject =
        parse(raw).firstOrNull() as? JsonObject ?: JsonObject(emptyMap())

    fun optText(obj: JsonObject, key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** 必填字符串参数；缺失即抛（调度层回 rejected 并带原因）。 */
    fun text(obj: JsonObject, key: String): String =
        optText(obj, key) ?: throw IllegalArgumentException("缺少参数: $key")

    fun optBool(obj: JsonObject, key: String, default: Boolean = false): Boolean =
        (obj[key] as? JsonPrimitive)?.booleanOrNull ?: default

    /** 可选整数参数，夹到 [min, max]（流类参数的边界由调用方声明）。 */
    fun optInt(obj: JsonObject, key: String, default: Int, min: Int, max: Int): Int =
        ((obj[key] as? JsonPrimitive)?.intOrNull ?: default).coerceIn(min, max)

    fun optLong(obj: JsonObject, key: String, default: Long): Long =
        (obj[key] as? JsonPrimitive)?.longOrNull ?: default

    /** 便捷重载：从元素数组取首对象。 */
    fun firstObject(args: JsonArray): JsonObject =
        args.firstOrNull() as? JsonObject ?: JsonObject(emptyMap())
}

/** 参数便捷扩展（内部用，避免到处传 ZqArgs）。 */
internal fun JsonObject.zqText(key: String): String = ZqArgs.text(this, key)
internal fun JsonObject.zqOptText(key: String): String? = ZqArgs.optText(this, key)
internal fun JsonObject.zqOptBool(key: String, default: Boolean = false): Boolean =
    ZqArgs.optBool(this, key, default)
internal fun JsonObject.zqOptInt(key: String, default: Int, min: Int, max: Int): Int =
    ZqArgs.optInt(this, key, default, min, max)

/** 结果元素（call 的返回），便于类型书写。 */
typealias ZqResult = JsonElement
