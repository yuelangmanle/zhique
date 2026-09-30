package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import com.zhique.core.permission.OsGate
import com.zhique.core.permission.OsGateResult
import com.zhique.core.permission.OsPermissionGateway
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * W3C 标准路 → 同一注册表（规格 §4.6「两条接入路，同一个注册表」）。
 *
 * - getUserMedia：`onPermissionRequest`（RESOURCE_VIDEO/AUDIO_CAPTURE）由
 *   :core:web 网关转进来，映射能力矩阵逐个过授权卡，全部 GRANTED 才 grant；
 * - geolocation：`onGeolocationPermissionsShowPrompt` 经 [onGeolocation] 走
 *   location 条目；
 * - **两条路都在真实授予时 recordUse()**——运行期真实使用计数驱动导出最小
 *   权限建议，漏计会让 getUserMedia/geolocation 用量不进 manifest 变体；
 * - 矩阵授予后同样过 [OsGate]（系统申请在授权流程内完成；OS 拒 → 矩阵回
 *   DENIED，页面 deny 降级）。
 */
class ZqW3CRouter(
    private val registry: PermissionRegistry,
    private val projectId: String,
    private val scope: CoroutineScope,
    private val osPermissions: OsPermissionGateway? = null,
) {

    /** W3C 资源名 → zq 能力（未知资源 → null；纯映射可 JVM 测试）。 */
    fun capabilitiesFor(resources: List<String>): List<Capability> = resources.mapNotNull {
        when (it) {
            android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Capability.CAMERA
            android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Capability.MIC
            else -> null
        }
    }

    /**
     * 处理一次 W3C 权限请求。[grant]/[deny] 封装 `PermissionRequest.grant/deny`
     * （须在 UI 线程调用——:app 侧网关保证）。
     */
    fun onRequest(resources: List<String>, grant: () -> Unit, deny: () -> Unit) {
        val caps = capabilitiesFor(resources)
        if (caps.isEmpty()) {
            deny() // 无可映射资源：不弹卡直接拒（未注册路语义一致）
            return
        }
        scope.launch {
            var allGranted = true
            for (cap in caps) {
                val state = registry.request(projectId, cap.id, "网页请求${cap.title}（W3C 标准接口 getUserMedia）")
                val ok = state == PState.GRANTED &&
                    OsGate.ensure(registry, projectId, cap, osPermissions) == OsGateResult.PASS
                if (!ok) allGranted = false
            }
            if (allGranted) {
                // W3C 路即真实使用：授予即计数（导出建议统计 getUserMedia 用量）
                caps.forEach { registry.recordUse(projectId, it.id) }
                grant()
            } else {
                deny()
            }
        }
    }

    /**
     * W3C geolocation（onGeolocationPermissionsShowPrompt）：location 条目过
     * 同一授权卡 + 系统门；[allow] 封装 `GeolocationPermissions.Callback.invoke`。
     */
    fun onGeolocation(allow: (Boolean) -> Unit) {
        scope.launch {
            val state = registry.request(projectId, Capability.LOCATION.id, "网页请求定位（W3C geolocation）")
            val granted = state == PState.GRANTED &&
                OsGate.ensure(registry, projectId, Capability.LOCATION, osPermissions) == OsGateResult.PASS
            if (granted) registry.recordUse(projectId, Capability.LOCATION.id)
            allow(granted)
        }
    }
}
