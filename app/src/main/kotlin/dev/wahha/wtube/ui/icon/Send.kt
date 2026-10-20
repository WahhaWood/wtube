package dev.wahha.wtube.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

@Suppress("CheckReturnValue")
val WIcons.Send: ImageVector
  get() {
    if (_Send != null) {
      return _Send!!
    }
    _Send =
      ImageVector.Builder(
          name = "Send",
          defaultWidth = 24.dp,
          defaultHeight = 24.dp,
          viewportWidth = 24f,
          viewportHeight = 24f,
        )
        .apply {
          path(
            fill = SolidColor(Color.Black),
            fillAlpha = 1f,
            stroke = null,
            strokeAlpha = 1f,
            strokeLineWidth = 1f,
            strokeLineCap = StrokeCap.Butt,
            strokeLineJoin = StrokeJoin.Bevel,
            strokeLineMiter = 1f,
            pathFillType = PathFillType.Companion.NonZero,
          ) {
            moveTo(19.8f, 12.93f)
            lineTo(4.4f, 19.43f)
            quadTo(3.9f, 19.63f, 3.45f, 19.34f)
            reflectiveQuadTo(3f, 18.5f)
            verticalLineTo(5.5f)
            quadTo(3f, 4.95f, 3.45f, 4.66f)
            quadTo(3.9f, 4.38f, 4.4f, 4.57f)
            lineToRelative(15.4f, 6.5f)
            quadToRelative(0.63f, 0.28f, 0.63f, 0.93f)
            reflectiveQuadTo(19.8f, 12.93f)
            close()
            moveTo(5f, 17f)
            lineTo(16.85f, 12f)
            lineTo(5f, 7f)
            verticalLineToRelative(3.5f)
            lineTo(11f, 12f)
            lineTo(5f, 13.5f)
            verticalLineTo(17f)
            close()
            moveToRelative(0f, 0f)
            verticalLineTo(12f)
            verticalLineTo(7f)
            verticalLineToRelative(3.5f)
            verticalLineToRelative(3f)
            verticalLineTo(17f)
            close()
          }
        }
        .build()
    return _Send!!
  }

private var _Send: ImageVector? = null
