package com.zhique.core.permission.zq

import android.app.Activity
import android.content.Context
import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 一个 zq 能力（如 zq.camera）。
 *
 * 硬件相关的实现放在 :app/:core 运行时路径，调度与参数校验逻辑可被
 * 纯 JVM 测试覆盖（计划 Task 5.2）。
 */
interface ZqCapability {

    /** 命名空间（zq.[ns].fn 中的 ns）。 */
    val ns: String

    /** 该能力对应的权限矩阵条目。 */
    val required: Capability

    /** 暴露的方法名（zq.[ns].[fn]）。 */
    val methods: List<String>

    /** 授权卡「干什么」的说明（按方法可细分）。 */
    fun why(fn: String): String

    /**
     * 执行方法。[args] 是首个选项对象；返回值序列化为 JSON 回给页面
     * （`__zqResolve(id, true, json)`）。抛错 → 页面拿到 rejected。
     */
    suspend fun call(fn: String, args: JsonObject, env: ZqEnv): JsonElement

    /**
     * 运行器销毁时的资源关停（审查修复 I2）：注销系统监听/相机/取景浮层，
     * 停掉仍在向已销毁 WebView 推送的流。默认无资源。
     */
    fun shutdown() {}
}

/** SAF pick/save 网关（Activity Result 在 :app 侧注册）。 */
interface SafGateway {
    suspend fun pick(mime: String?): JsonElement
    suspend fun save(name: String, mime: String?, content: String): JsonElement
}

/**
 * 投影会话最小面（审查修复 I1）：MediaProjection 适配在 :app，[stop] 必须
 * 反注册回调并停投影（幂等）——调用方在 finally 里调用，杜绝投影指示灯/
 * 常驻通知滞留。测试用假会话记录 stop 语义。
 */
interface ProjectionSession {
    /** 抓一帧屏幕；返回 ARGB 位图（超时/无帧返回 null）。 */
    suspend fun grabFrame(timeoutMs: Long): android.graphics.Bitmap?

    /** 结束会话：反注册回调 + 停投影（幂等，重复调用无害）。 */
    fun stop()
}

/** MediaProjection 网关（系统投影授权流在 :app 侧注册；截屏独立授权）。 */
fun interface ProjectionGateway {
    /** 每次截屏取一个新会话（含投影令牌换取 + 前台服务保障）。 */
    suspend fun session(): ProjectionSession?
}

/**
 * 能力运行环境（调度器注入）：项目归属、推送通道、注册表与可选的
 * 宿主网关。测试可只填前四项构造（Android 侧引用保持 null，不触碰）。
 */
class ZqEnv(
    val projectId: String,
    val projectDir: File,
    val scope: CoroutineScope,
    val registry: PermissionRegistry,
    /** 向页面推送 JS（结果 settle / `__zqEvent` 订阅事件）。 */
    val evaluateJs: (String) -> Unit,
    val appContext: Context? = null,
    val activity: Activity? = null,
    val saf: SafGateway? = null,
    val projection: ProjectionGateway? = null,
    /** OS 运行时权限网关（授权卡授予后立即发起系统申请；null=能力自查兜底）。 */
    val osPermissions: com.zhique.core.permission.OsPermissionGateway? = null,
) {
    /** 流式订阅句柄登记表（location/sensor 共用）。 */
    val subs = Subscriptions()
}

/** 系统运行时权限检查（App 集中持权；未授予时给出可操作的错误信息）。 */
object SystemPerms {
    fun granted(context: Context?, vararg perms: String): Boolean =
        context != null && perms.all {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
}
