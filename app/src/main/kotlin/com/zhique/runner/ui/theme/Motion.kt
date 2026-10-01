package com.zhique.runner.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 织雀全线非线性 spring（规格 §5.2：damping 0.75–0.85，禁线性 easing）。
 * M9 Aurora Glass 分化动效五件套（[ZqMotion]）；本常量保留为抽屉/跟手弹簧别名，
 * DebugDrawer / RunnerScreen 分屏 / FloatingBubble 既有调用沿用。
 */
val ZqSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/** Aurora Glass 动效五件套常量（规格 §5.2 规定动作）。 */
object ZqMotion {

    /** ① 抽屉/分屏弹簧过冲回弹（跟手释放吸附三档）。 */
    val Drawer = spring<Float>(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow)

    /** ② 卡片按压回弹（scale 0.94→1.035→1），GlassCard 内建。 */
    val Press = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)

    /** ③ FAB 加号→叉号形变（GlowButton fab 变体内建）。 */
    val Fab = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMedium)

    /** ④ 进度环/进度条弹性填充（预算环、压缩卡进度）。 */
    val Progress = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow)

    /** ⑤ 列表项/卡片滑入微过冲（首现上滑 24dp + 淡入）。 */
    val SlideIn = spring<Float>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)

    /** 思考流逐字回放缓动（非线性 ease-out 节奏，替代线性 tween）。 */
    val Reveal = spring<Float>(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)
}
