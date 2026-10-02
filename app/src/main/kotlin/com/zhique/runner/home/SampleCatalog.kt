package com.zhique.runner.home

/**
 * 预设示例目录（示例库）：assets/samples/ 下每个精美示例的展示元数据。
 * 首页空态与「示例库」入口据此渲染卡片；点卡片即创建并运行（同名自动加序号）。
 */
data class SampleEntry(
    val asset: String,
    val name: String,
    val emoji: String,
    val desc: String,
    /** 卡片渐变主色（#RRGGBB）。 */
    val accent: String,
)

object SampleCatalog {

    val ALL: List<SampleEntry> = listOf(
        SampleEntry("permission-check.html", "权限检测中心", "🛡️", "逐个检测手机全部能力", "#5B8DEF"),
        SampleEntry("stars.html", "星空", "🌌", "WebGL 星空粒子", "#46509F"),
        SampleEntry("particles.html", "粒子星云", "✨", "触摸吸引的粒子漩涡", "#9A6BFF"),
        SampleEntry("pomodoro.html", "极简番茄钟", "🍅", "专注计时 · 数据本机持久化", "#FF6B5A"),
        SampleEntry("glow-paint.html", "流光画板", "🎨", "霓虹笔触 · 一键存图", "#3ECFA8"),
        SampleEntry("camera-test.html", "相机测试", "📷", "拍照 / 取景 / 拒绝降级", "#E8A33D"),
    )

    fun byAsset(asset: String): SampleEntry? = ALL.firstOrNull { it.asset == asset }
}
