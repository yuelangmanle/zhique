package com.zhique.runner.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * 织雀全线非线性 spring（规格 §5.2：damping 0.75–0.85，禁线性 easing）。
 * M9 Aurora Glass 打磨时再分化常量族；M1 起步 0.8。
 */
val ZqSpring = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)
