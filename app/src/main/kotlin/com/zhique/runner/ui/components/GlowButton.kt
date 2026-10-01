package com.zhique.runner.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.zhique.runner.ui.theme.ZqMotion
import com.zhique.runner.ui.theme.zqSemantic

/**
 * GlowButton（规格 §5.1 发光交互要素）：主按钮靛蓝系外发光——shadow 环绕
 * （语义 glow 色，elevation 即光晕半径），按下光晕收拢、松开回弹。
 * [fab]=true 时为 FAB 变体：内容（+ 图标）随 [expanded] 旋转 45° 成 ×，
 * 形变走 [ZqMotion.Fab]（§5.2 ③ FAB 加号→叉号形变）。
 */
@Composable
fun GlowButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    iconContentDescription: String? = null,
    expanded: Boolean = false,
    fab: Boolean = false,
    shape: Shape = MaterialTheme.shapes.large,
    container: Color? = null,
    testTag: String? = null,
) {
    val semantic = zqSemantic()
    val base = container ?: MaterialTheme.colorScheme.primary
    val glow = 14.dp
    val glowScale by animateFloatAsState(
        targetValue = if (fab && expanded) 0.6f else 1f,
        animationSpec = ZqMotion.Fab,
        label = "glow",
    )
    val glowModifier = if (testTag != null) modifier.testTag(testTag) else modifier

    if (fab) {
        FloatingActionButton(
            onClick = onClick,
            modifier = glowModifier.shadow(
                glow * glowScale, CircleShape,
                ambientColor = semantic.glow, spotColor = semantic.glow,
            ),
            containerColor = base,
            shape = CircleShape,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = iconContentDescription,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (expanded) 45f else 0f),
                )
            } else if (label != null) {
                Text(label)
            }
        }
    } else {
        Button(
            onClick = onClick,
            modifier = glowModifier.shadow(
                glow * glowScale, shape,
                ambientColor = semantic.glow, spotColor = semantic.glow,
            ),
            colors = ButtonDefaults.buttonColors(containerColor = base),
            shape = shape,
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = iconContentDescription)
            if (label != null) Text(label)
        }
    }
}
