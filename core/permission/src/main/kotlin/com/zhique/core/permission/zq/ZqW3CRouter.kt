package com.zhique.core.permission.zq

import com.zhique.core.permission.Capability
import com.zhique.core.permission.PermissionRegistry
import com.zhique.core.permission.PState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * W3C 标准路 → 同一注册表（规格 §4.6「两条接入路，同一个注册表」）。
 *
 * `WebChromeClient.onPermissionRequest`（RESOURCE_VIDEO/AUDIO_CAPTURE 等）
 * 由 :core:web 的网关转进来：资源映射到能力矩阵条目，逐个过授权卡，
 * 全部 GRANTED 才 grant，任一 DENIED 即 deny —— 拒绝对网页是优雅信号。
 */
class ZqW3CRouter(
    private val registry: PermissionRegistry,
    private val projectId: String,
    private val scope: CoroutineScope,
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
                if (state != PState.GRANTED) allGranted = false
            }
            if (allGranted) grant() else deny()
        }
    }
}
