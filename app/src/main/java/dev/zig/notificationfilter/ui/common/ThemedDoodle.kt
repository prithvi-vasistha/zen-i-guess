package dev.zig.notificationfilter.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.vectorResource

/** Recolour the three flat empty-state vectors while keeping their fills and line art. */
@Composable
fun themedDoodlePainter(@DrawableRes resource: Int): Painter {
    val image = ImageVector.vectorResource(resource)
    val colors = MaterialTheme.colorScheme
    val themed = remember(image, colors) {
        fun recolor(brush: Brush?): Brush? {
            if (brush !is SolidColor) return brush
            val color = when (brush.value) {
                Color(0xFF4CAF50) -> colors.primary
                Color(0xFFC8E6C9) -> colors.primaryContainer
                Color(0xFFEEEEEE) -> colors.surfaceVariant
                Color.White -> colors.background
                else -> brush.value
            }
            return SolidColor(color)
        }
        val builder = ImageVector.Builder(image.name, image.defaultWidth, image.defaultHeight,
            image.viewportWidth, image.viewportHeight)
        for (node in image.root) {
            val path = node as VectorPath
            builder.addPath(path.pathData, pathFillType = path.pathFillType, name = path.name,
                fill = recolor(path.fill), fillAlpha = path.fillAlpha,
                stroke = recolor(path.stroke), strokeAlpha = path.strokeAlpha,
                strokeLineWidth = path.strokeLineWidth, strokeLineCap = path.strokeLineCap,
                strokeLineJoin = path.strokeLineJoin, strokeLineMiter = path.strokeLineMiter,
                trimPathStart = path.trimPathStart, trimPathEnd = path.trimPathEnd, trimPathOffset = path.trimPathOffset)
        }
        builder.build()
    }
    return rememberVectorPainter(themed)
}
