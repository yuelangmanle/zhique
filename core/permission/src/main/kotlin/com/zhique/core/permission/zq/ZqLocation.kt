package com.zhique.core.permission.zq

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.zhique.core.permission.Capability
import com.zhique.core.permission.PState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.location：last known 单次读取 + 周期流简版（计划 Task 5.2）。
 * 流为 EventChannel 风格：watch 返回 sub 句柄，`__zqEvent(sub, json)` 推送；
 * 每次推送前复查矩阵状态——吊销即停流（运行中调用返回 denied 语义）。
 */
class ZqLocation : ZqCapability {

    override val ns = "location"
    override val required = Capability.LOCATION
    override val methods = listOf("get", "watch", "unwatch")

    override fun why(fn: String): String = when (fn) {
        "get" -> "读取一次当前位置"
        "watch" -> "按周期推送位置更新到页面"
        else -> "停止位置推送"
    }

    private val listeners = ConcurrentHashMap<String, LocationListener>()

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: throw IllegalStateException("定位服务不可用")
        return when (fn) {
            "get" -> {
                requireSystemPermission(context)
                val best = candidates(lm)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.time }
                    ?: throw IllegalStateException("尚无可用位置（请先在系统开启定位并稍候）")
                locJson(best, sub = null)
            }
            "watch" -> {
                requireSystemPermission(context)
                val intervalMs = args.zqOptInt("intervalMs", DEFAULT_INTERVAL_MS, MIN_INTERVAL_MS, MAX_INTERVAL_MS)
                val provider = candidates(lm).firstOrNull { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
                    ?: throw IllegalStateException("无可用的定位提供者（请到系统设置开启定位）")
                val sub = env.subs.new()
                val holder = arrayOfNulls<LocationListener>(1)
                val listener = LocationListener { loc ->
                    if (!env.subs.isActive(sub) || env.registry.state(env.projectId, ns) != PState.GRANTED) {
                        holder[0]?.let { l -> runCatching { lm.removeUpdates(l) } }
                        listeners.remove(sub)
                        env.subs.cancel(sub)
                        env.evaluateJs(ZqEvents.pushJs(sub, "{\"code\":\"denied\"}"))
                        return@LocationListener
                    }
                    env.evaluateJs(ZqEvents.pushJs(sub, locJson(loc, sub).toString()))
                }
                holder[0] = listener
                listeners[sub] = listener
                runCatching {
                    lm.requestLocationUpdates(provider, intervalMs.toLong(), 0f, listener, Looper.getMainLooper())
                }.onFailure {
                    listeners.remove(sub)
                    env.subs.cancel(sub)
                    throw IllegalStateException("位置流启动失败: ${it.message}")
                }
                buildJsonObject { put("sub", sub) }
            }
            "unwatch" -> {
                val sub = args.zqText("sub")
                listeners.remove(sub)?.let { lm.removeUpdates(it) }
                buildJsonObject { put("ok", env.subs.cancel(sub)) }
            }
            else -> throw IllegalArgumentException("zq.location 未知方法: $fn")
        }
    }

    private fun candidates(lm: LocationManager): List<String> =
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { lm.allProviders.contains(it) }

    private fun requireSystemPermission(context: Context) {
        if (!SystemPerms.granted(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        ) {
            throw IllegalStateException("系统定位权限未授予（可重新发起授权，或在系统设置中开启织雀的位置信息）")
        }
    }

    private fun locJson(loc: Location, sub: String?) = buildJsonObject {
        put("lat", loc.latitude)
        put("lng", loc.longitude)
        put("accuracy", loc.accuracy.toDouble())
        put("time", loc.time)
        if (sub != null) put("sub", sub)
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 5_000
        const val MIN_INTERVAL_MS = 1_000
        const val MAX_INTERVAL_MS = 60_000
    }
}
