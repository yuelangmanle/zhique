package com.zhique.core.permission.zq

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.zhique.core.permission.Capability
import com.zhique.core.permission.PState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * zq.sensor：加速度/陀螺仪/磁力 订阅模型（计划 Task 5.2）。
 * `zq.sensor.watch({type, rate?})` → `{sub}`；样本经 `__zqEvent(sub, json)` 推送；
 * 每帧复查矩阵状态——吊销即停流。类型/频率映射（[Spec]）纯逻辑可 JVM 测试。
 */
class ZqSensor : ZqCapability {

    override val ns = "sensor"
    override val required = Capability.SENSOR
    override val methods = listOf("watch", "unwatch")

    override fun why(fn: String): String = when (fn) {
        "watch" -> "订阅设备传感器数据流（加速度/陀螺仪/磁力）"
        else -> "停止传感器数据流"
    }

    private val listeners = ConcurrentHashMap<String, SensorEventListener>()

    override suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement {
        val context = env.appContext ?: throw IllegalStateException("无宿主环境")
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: throw IllegalStateException("传感器服务不可用")
        return when (fn) {
            "watch" -> {
                val spec = Spec.parse(args.zqOptText("type") ?: "", args.zqOptText("rate"))
                val sensor = sm.getDefaultSensor(spec.sensorType)
                    ?: throw IllegalStateException("设备没有${spec.title}传感器")
                val sub = env.subs.new()
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (!env.subs.isActive(sub) || env.registry.state(env.projectId, ns) != PState.GRANTED) {
                            sm.unregisterListener(this)
                            listeners.remove(sub)
                            env.subs.cancel(sub)
                            env.evaluateJs(ZqEvents.pushJs(sub, "{\"code\":\"denied\"}"))
                            return
                        }
                        val sample = buildJsonObject {
                            put("type", spec.name)
                            put("x", event.values.firstOrNull() ?: 0f)
                            put("y", event.values.elementAtOrNull(1) ?: 0f)
                            put("z", event.values.elementAtOrNull(2) ?: 0f)
                            put("accuracy", event.accuracy)
                        }
                        env.evaluateJs(ZqEvents.pushJs(sub, sample.toString()))
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                listeners[sub] = listener
                runCatching { sm.registerListener(listener, sensor, spec.delay) }
                    .onFailure {
                        listeners.remove(sub)
                        env.subs.cancel(sub)
                        throw IllegalStateException("传感器流启动失败: ${it.message}")
                    }
                buildJsonObject { put("sub", sub) }
            }
            "unwatch" -> {
                val sub = args.zqText("sub")
                listeners.remove(sub)?.let { sm.unregisterListener(it) }
                buildJsonObject { put("ok", env.subs.cancel(sub)) }
            }
            else -> throw IllegalArgumentException("zq.sensor 未知方法: $fn")
        }
    }

    /** 类型/频率映射（纯逻辑；常量为 API 1 起稳定的 SensorManager 常量）。 */
    data class Spec(val sensorType: Int, val delay: Int, val name: String, val title: String) {
        companion object {
            fun parse(type: String, rate: String?): Spec {
                val sensorType = when (type) {
                    "accel" -> Sensor.TYPE_ACCELEROMETER
                    "gyro" -> Sensor.TYPE_GYROSCOPE
                    "magnet" -> Sensor.TYPE_MAGNETIC_FIELD
                    else -> throw IllegalArgumentException("未知传感器类型: $type（可选 accel/gyro/magnet）")
                }
                val delay = when (rate) {
                    "game" -> SensorManager.SENSOR_DELAY_GAME
                    "normal" -> SensorManager.SENSOR_DELAY_NORMAL
                    else -> SensorManager.SENSOR_DELAY_UI
                }
                val title = when (type) {
                    "accel" -> "加速度"
                    "gyro" -> "陀螺仪"
                    else -> "磁力"
                }
                return Spec(sensorType, delay, type, title)
            }
        }
    }
}
