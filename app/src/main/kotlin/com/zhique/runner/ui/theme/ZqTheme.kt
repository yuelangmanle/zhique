package com.zhique.runner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ZqTheme（iOS 设计语言）：分栏底（systemGroupedBackground）+ 卡片面
 * （secondarySystemGroupedBackground）+ iOS 系统语义色 + HIG 字阶；
 * 强调色保留品牌「雀青」。液态玻璃材质见 ui/kit/LiquidGlass。
 */

/** 语义色（iOS 系统色语义：绿=健康、橙=等待、红=错误，全 App 不换义）。 */
@Immutable
data class ZqSemantic(
    val interactive: Color, // 品牌雀青（可交互 tint）
    val health: Color, // iOS 绿
    val waiting: Color, // iOS 橙
    val danger: Color, // iOS 红
    val glassBorder: Color, // 液态玻璃高光描边（白系）
    val glassHighlight: Color, // 玻璃内顶部高光
    val glow: Color, // 低透明 tint 轻染
)

/** 语义色 CompositionLocal（默认取浅色域，防测试环境未包主题）。 */
val LocalZqSemantic = staticCompositionLocalOf {
    ZqSemantic(
        interactive = Color(0xFF2F6D5F),
        health = Color(0xFF34C759),
        waiting = Color(0xFF9A6A1F),
        danger = Color(0xFFFF3B30),
        glassBorder = Color.White.copy(alpha = 0.55f),
        glassHighlight = Color.White.copy(alpha = 0.70f),
        glow = Color(0x1A2F6D5F),
    )
}

private val Tint = Color(0xFF2F6D5F) // 雀青（浅域 tint）

// ---- 浅色（iOS 分栏：灰底白卡） ----

private val LightColors: ColorScheme = lightColorScheme(
    primary = Tint,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EAE4),
    onPrimaryContainer = Color(0xFF0E2B24),
    secondary = Color(0xFF3C3C43),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE9E9EB), // iOS gray fill
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF5C5A48),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFFF3B30), // iOS red
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE2DE),
    onErrorContainer = Color(0xFF5C0E06),
    background = Color(0xFFF2F2F7), // systemGroupedBackground
    onBackground = Color(0xFF1C1C1E),
    surface = Color(0xFFFFFFFF), // secondarySystemGroupedBackground
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFE9E9EB),
    onSurfaceVariant = Color(0xFF6C6C70), // secondaryLabel
    outline = Color(0xFFC6C6C8), // separator
    outlineVariant = Color(0xFFE5E5EA),
    surfaceContainer = Color(0xFFF7F7FA),
    surfaceContainerHigh = Color(0xFFF2F2F5),
    surfaceContainerHighest = Color(0xFFEDEDF0),
    surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF2F2F7),
)

private val LightSemantic = ZqSemantic(
    interactive = Tint,
    health = Color(0xFF34C759),
    waiting = Color(0xFFB58526),
    danger = Color(0xFFFF3B30),
    glassBorder = Color.White.copy(alpha = 0.60f),
    glassHighlight = Color.White.copy(alpha = 0.75f),
    glow = Color(0x1A2F6D5F),
)

// ---- 深色（iOS 分栏：黑底深灰卡） ----

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7CC6B2),
    onPrimary = Color(0xFF0A2A22),
    primaryContainer = Color(0xFF1F443B),
    onPrimaryContainer = Color(0xFFC8E9DD),
    secondary = Color(0xFFC7C7CC),
    onSecondary = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE5E5EA),
    tertiary = Color(0xFFC6C2A6),
    onTertiary = Color(0xFF2E2C1E),
    error = Color(0xFFFF453A), // iOS red (dark)
    onError = Color(0xFF4C0B02),
    errorContainer = Color(0xFF7A2416),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF000000), // iOS 黑
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1C1C1E), // elevated
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9AA2), // secondaryLabel dark
    outline = Color(0xFF38383A), // separator dark
    outlineVariant = Color(0xFF2C2C2E),
    surfaceContainer = Color(0xFF232325),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF363638),
    surfaceContainerLow = Color(0xFF1A1A1C),
    surfaceContainerLowest = Color(0xFF0F0F10),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1C1C1E),
)

private val DarkSemantic = ZqSemantic(
    interactive = Color(0xFF7CC6B2),
    health = Color(0xFF30D158),
    waiting = Color(0xFFFF9F0A),
    danger = Color(0xFFFF453A),
    glassBorder = Color.White.copy(alpha = 0.22f),
    glassHighlight = Color.White.copy(alpha = 0.14f),
    glow = Color(0x337CC6B2),
)

/** HIG 字阶映射（系统字体；字重/字号承担层级）。 */
private val ZqTypography = Typography(
    // largeTitle 34/41
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 41.sp, letterSpacing = 0.3.sp),
    // title1 28/34
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = 0.3.sp),
    // title2 22/28
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp),
    // title3 20/25
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 25.sp, letterSpacing = 0.2.sp),
    // headline 17/22
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    // subheadline(semibold) 15/20
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    // body 17/22
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    // subheadline 15/20
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    // footnote 13/18
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    // callout(semibold) 16/21
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    // caption1 12/16
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    // caption2 11/13
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 13.sp, letterSpacing = 0.2.sp),
)

/** iOS 形状：卡片 12、控件 8、胶囊/玻璃面板 20+。 */
private val ZqShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/** 主题入口：[darkTheme] 跟随系统。 */
@Composable
fun ZqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    androidx.compose.runtime.CompositionLocalProvider(LocalZqSemantic provides semantic) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = ZqTypography,
            shapes = ZqShapes,
            content = content,
        )
    }
}

/** 当前域语义色（状态标注消费）。 */
@Composable
fun zqSemantic(): ZqSemantic = LocalZqSemantic.current
