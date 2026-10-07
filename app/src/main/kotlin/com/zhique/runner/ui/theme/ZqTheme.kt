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
 * ZqTheme（可商用级克制视觉）：暖白/石墨中性面 + 单一「雀青」强调色。
 *
 * 准则：无渐变、无毛玻璃、无发光——层级由灰阶、字重、1dp 细线承担；
 * 强调色只在可交互语义上出现；语义色全 App 不换义（§5.1）。
 */

/** 语义色（扩展位，与 Material 色板并行；glassBorder/Highlight 保留兼容旧调用，现为细线/中性值）。 */
@Immutable
data class ZqSemantic(
    val interactive: Color, // 雀青（可交互）
    val health: Color, // 绿 = 健康
    val waiting: Color, // 琥珀 = 等待/注意
    val danger: Color, // 红 = 错误
    val glassBorder: Color, // 细线（原毛玻璃白边）
    val glassHighlight: Color, // 中性（原卡片高光）
    val glow: Color, // 低透明强调（原外发光，现仅轻染）
)

/** 语义色 CompositionLocal（默认取浅色域，防测试环境未包主题）。 */
val LocalZqSemantic = staticCompositionLocalOf {
    ZqSemantic(
        interactive = Color(0xFF2F6D5F),
        health = Color(0xFF2E7D46),
        waiting = Color(0xFF9A6A1F),
        danger = Color(0xFFB3402E),
        glassBorder = Color(0xFFE3E3DC),
        glassHighlight = Color(0xFFFFFFFF),
        glow = Color(0x1A2F6D5F),
    )
}

private val Ink = Color(0xFF2F6D5F) // 雀青·浅域强调

// ---- 浅色（暖纸中性面） ----

private val LightColors: ColorScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9EAE4),
    onPrimaryContainer = Color(0xFF0E2B24),
    secondary = Color(0xFF4E5854),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDEEAE5),
    onSecondaryContainer = Color(0xFF17211D),
    tertiary = Color(0xFF5C5A48),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFFB3402E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFADBD5),
    onErrorContainer = Color(0xFF3E0A03),
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF1B1C1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1C1A),
    surfaceVariant = Color(0xFFEFEFEA),
    onSurfaceVariant = Color(0xFF63655E),
    outline = Color(0xFFD9D9D1),
    outlineVariant = Color(0xFFE7E7E0),
    surfaceContainer = Color(0xFFF2F2EE),
    surfaceContainerHigh = Color(0xFFECECE7),
    surfaceContainerHighest = Color(0xFFE7E7E1),
    surfaceContainerLow = Color(0xFFF7F7F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2C2E2B),
    inverseOnSurface = Color(0xFFEFF1ED),
)

private val LightSemantic = ZqSemantic(
    interactive = Ink,
    health = Color(0xFF2E7D46),
    waiting = Color(0xFF9A6A1F),
    danger = Color(0xFFB3402E),
    glassBorder = Color(0xFFE3E3DC),
    glassHighlight = Color(0xFFFFFFFF),
    glow = Color(0x1A2F6D5F),
)

// ---- 深色（石墨中性面） ----

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF7CC6B2),
    onPrimary = Color(0xFF0A2A22),
    primaryContainer = Color(0xFF1F443B),
    onPrimaryContainer = Color(0xFFC8E9DD),
    secondary = Color(0xFFB7C2BC),
    onSecondary = Color(0xFF232B27),
    secondaryContainer = Color(0xFF39443F),
    onSecondaryContainer = Color(0xFFD3DED8),
    tertiary = Color(0xFFC6C2A6),
    onTertiary = Color(0xFF2E2C1E),
    error = Color(0xFFF2B8AE),
    onError = Color(0xFF4C0B02),
    errorContainer = Color(0xFF7A2416),
    onErrorContainer = Color(0xFFFFDAD3),
    background = Color(0xFF101312),
    onBackground = Color(0xFFE3E6E3),
    surface = Color(0xFF161918),
    onSurface = Color(0xFFE3E6E3),
    surfaceVariant = Color(0xFF202422),
    onSurfaceVariant = Color(0xFF9BA19D),
    outline = Color(0xFF2C302D),
    outlineVariant = Color(0xFF242826),
    surfaceContainer = Color(0xFF1C201E),
    surfaceContainerHigh = Color(0xFF222624),
    surfaceContainerHighest = Color(0xFF282C2A),
    surfaceContainerLow = Color(0xFF141716),
    surfaceContainerLowest = Color(0xFF0C0E0D),
    inverseSurface = Color(0xFFE3E6E3),
    inverseOnSurface = Color(0xFF2C2E2B),
)

private val DarkSemantic = ZqSemantic(
    interactive = Color(0xFF7CC6B2),
    health = Color(0xFF7ED09A),
    waiting = Color(0xFFE5B56E),
    danger = Color(0xFFF2B8AE),
    glassBorder = Color(0xFF2C302D),
    glassHighlight = Color(0xFF232725),
    glow = Color(0x337CC6B2),
)

/** 完整字阶：系统字体，靠字重/字号/灰阶建层级（无装饰字体）。 */
private val ZqTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
)

/** 形状：克制的中小圆角（面 12、控件 8、胶囊 full 由组件自定）。 */
private val ZqShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
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
