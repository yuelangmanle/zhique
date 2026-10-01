package com.zhique.runner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * ZqTheme（M9 Aurora Glass 全落地）：晨光浅底（管理域）/ 深空暗底（运行域）
 * 双域 Material3 完整色板 + 语义色（规格 §5.1：靛蓝 #46509F=可交互、绿=健康、
 * 琥珀=等待/注意、红=错误，全 App 不换义）。
 */

/** Aurora Glass 语义色（扩展色，与 Material 色板并行，双域各给一组）。 */
@Immutable
data class ZqSemantic(
    val interactive: Color, // 靛蓝 #46509F 系
    val health: Color, // 绿 = 健康
    val waiting: Color, // 琥珀 = 等待/注意
    val danger: Color, // 红 = 错误
    val glassBorder: Color, // 毛玻璃白边（暗域更亮）
    val glassHighlight: Color, // 卡片顶部 1px 高光
    val glow: Color, // 主按钮外发光
)

/** 语义色 CompositionLocal（默认取晨光域，防测试环境未包主题）。 */
val LocalZqSemantic = staticCompositionLocalOf {
    ZqSemantic(
        interactive = Color(0xFF46509F),
        health = Color(0xFF1E8E3E),
        waiting = Color(0xFFB26A00),
        danger = Color(0xFFBA1A1A),
        glassBorder = Color.White.copy(alpha = 0.55f),
        glassHighlight = Color.White.copy(alpha = 0.8f),
        glow = Color(0xFF46509F),
    )
}

/** AuroraBackground 光斑用低饱和环境色（M9 §5.1：每屏 ≤2 枚、低饱和、缓慢漂移）。 */
@Immutable
data class AuroraPalette(val blobA: Color, val blobB: Color)

private val Indigo = Color(0xFF46509F)

// ---- 晨光浅底（管理域：首页/设置/导出） ----

private val LightColors: ColorScheme = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDFE1FF),
    onPrimaryContainer = Color(0xFF0F1350),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF77536D),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF9FE),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF9FE),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    surfaceContainer = Color(0xFFF1EEF7),
    surfaceContainerHigh = Color(0xFFEBE8F1),
    surfaceContainerHighest = Color(0xFFE5E2EB),
    surfaceContainerLow = Color(0xFFF7F4FB),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F6),
)

private val LightSemantic = ZqSemantic(
    interactive = Indigo,
    health = Color(0xFF1E8E3E),
    waiting = Color(0xFFB26A00),
    danger = Color(0xFFBA1A1A),
    glassBorder = Color.White.copy(alpha = 0.55f),
    glassHighlight = Color.White.copy(alpha = 0.85f),
    glow = Color(0x6646509F),
)

private val LightAurora = AuroraPalette(
    blobA = Color(0xFFB9BEE8), // 晨光靛雾
    blobB = Color(0xFFEAD6C4), // 晨光暖霞
)

// ---- 深空暗底（运行域：运行器/编辑器/Agent） ----

private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF1A2158),
    primaryContainer = Color(0xFF333A7E),
    onPrimaryContainer = Color(0xFFDFE1FF),
    secondary = Color(0xFFC4C4DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434559),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFFE6BAD7),
    onTertiary = Color(0xFF44263D),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF12131A),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF12131A),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    surfaceContainer = Color(0xFF1E1F26),
    surfaceContainerHigh = Color(0xFF282931),
    surfaceContainerHighest = Color(0xFF33343C),
    surfaceContainerLow = Color(0xFF1A1B22),
    surfaceContainerLowest = Color(0xFF0D0E13),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF2F3036),
)

private val DarkSemantic = ZqSemantic(
    interactive = Color(0xFFBCC2FF),
    health = Color(0xFF6DD58C),
    waiting = Color(0xFFFFB95C),
    danger = Color(0xFFFFB4AB),
    glassBorder = Color.White.copy(alpha = 0.35f),
    glassHighlight = Color.White.copy(alpha = 0.55f),
    glow = Color(0x8C9AA6FF),
)

private val DarkAurora = AuroraPalette(
    blobA = Color(0xFF3A4180), // 深空靛
    blobB = Color(0xFF2A3550), // 深空蓝灰
)

/** 双域主题入口：[darkTheme] 跟随系统（运行域屏可直接传 true 强制深空）。 */
@Composable
fun ZqTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val semantic = if (darkTheme) DarkSemantic else LightSemantic
    androidx.compose.runtime.CompositionLocalProvider(LocalZqSemantic provides semantic) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            content = content,
        )
    }
}

/** 当前域的 Aurora 光斑低饱和色（AuroraBackground 消费）。 */
@Composable
fun auroraPalette(): AuroraPalette = if (isSystemInDarkTheme()) DarkAurora else LightAurora

/** 当前域语义色（GlassCard/GlowButton/状态标注消费）。 */
@Composable
fun zqSemantic(): ZqSemantic = LocalZqSemantic.current

internal fun darkAuroraForTest(): AuroraPalette = DarkAurora
internal fun lightAuroraForTest(): AuroraPalette = LightAurora
