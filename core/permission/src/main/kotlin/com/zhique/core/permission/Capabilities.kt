package com.zhique.core.permission

/**
 * 权限桥第一批能力全覆盖（规格 §2.1 F6）。
 *
 * [manifestPermissions] 是该能力对应的 Android 集中权限名（App 侧 manifest
 * 一次性全量申报 = 集中持权，网页按需经授权卡申请，规格 §4.6）；剪贴板/
 * 分享/文件沙盒/截屏不需要 dangerous 权限，故为空表。
 */
enum class Capability(
    val id: String,
    val title: String,
    val summary: String,
    val manifestPermissions: List<String> = emptyList(),
) {
    CAMERA("camera", "相机", "拍照并把照片保存到项目目录",
        listOf("android.permission.CAMERA")),
    MIC("mic", "麦克风", "录音并把音频保存到项目目录",
        listOf("android.permission.RECORD_AUDIO")),
    FILE("file", "文件", "在项目沙盒内读写文件；SAF 选择/保存到任意位置"),
    LOCATION("location", "定位", "读取位置（单次或周期流）",
        listOf(
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
        )),
    SENSOR("sensor", "传感器", "订阅加速度/陀螺仪/磁力计数据流",
        listOf("android.permission.HIGH_SAMPLING_RATE_SENSORS")),
    BLUETOOTH("bluetooth", "蓝牙", "扫描附近的 BLE 设备",
        listOf(
            "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT",
        )),
    NOTIFICATION("notification", "通知", "以系统通知展示网页发来的消息",
        listOf("android.permission.POST_NOTIFICATIONS")),
    CLIPBOARD("clipboard", "剪贴板", "读取或写入系统剪贴板"),
    SHARE("share", "分享", "把文本或项目内文件交给系统分享"),
    SCREEN("screen", "截屏", "抓取屏幕画面（系统投影弹窗单独授权）"),
    ;

    companion object {
        val ALL: List<Capability> = entries.toList()

        fun fromId(id: String): Capability? = entries.firstOrNull { it.id == id }

        /**
         * manifest 变体映射：能力 id 清单 → 集中权限名并集（去重、保序）。
         * 导出壳按「运行期真实使用记录」勾选的能力清单生成最小权限 manifest。
         */
        fun manifestFor(ids: Collection<String>): List<String> =
            ids.mapNotNull { fromId(it) }
                .flatMapTo(LinkedHashSet()) { it.manifestPermissions }
                .toList()
    }
}
