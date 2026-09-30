package com.zhique.core.permission

/**
 * OS 运行时权限网关（规格审查修复 #1）：授权卡 GRANTED 后立即发起系统
 * `requestPermissions`。实现卡在 :app（ActivityResultContracts.RequestMultiplePermissions，
 * 申请在授权流程内完成，不再引导「去系统设置」为唯一路径）。
 */
interface OsPermissionGateway {

    /** 系统级权限是否已全部授予（不弹任何 UI）。 */
    fun granted(permissions: List<String>): Boolean

    /** 发起系统申请；返回每项权限的授予结果。 */
    suspend fun request(permissions: List<String>): Map<String, Boolean>
}

/** [OsGate.ensure] 的结果。 */
enum class OsGateResult {
    /** 系统权限就绪，能力可执行。 */
    PASS,

    /** 系统权限被拒：矩阵态已回 DENIED，页面拿 {"code":"denied","reason":"system"}。 */
    SYSTEM_DENIED,
}

/**
 * 矩阵授予后的系统权限门（审查修复 #1 核心）：
 *
 * - 能力无 manifest 权限（剪贴板/文件/分享/截屏）→ 直接 PASS；
 * - 网关未接入（如独立 :web 壳）→ PASS，由能力实现自查兜底，不阻断；
 * - OS 已授予 → PASS（不重复申请）；
 * - 否则立即发起系统申请；任一被拒 → **矩阵态回 DENIED**（下次调用不再重弹，
 *   权限中心直改可复活），页面按「系统权限被拒」语义优雅降级。
 */
object OsGate {

    suspend fun ensure(
        registry: PermissionRegistry,
        projectId: String,
        capability: Capability,
        gateway: OsPermissionGateway?,
    ): OsGateResult {
        val needed = capability.manifestPermissions
        if (needed.isEmpty() || gateway == null) return OsGateResult.PASS
        if (gateway.granted(needed)) return OsGateResult.PASS
        val results = gateway.request(needed)
        if (results.isNotEmpty() && results.values.all { it }) return OsGateResult.PASS
        registry.set(projectId, capability.id, PState.DENIED)
        return OsGateResult.SYSTEM_DENIED
    }
}
