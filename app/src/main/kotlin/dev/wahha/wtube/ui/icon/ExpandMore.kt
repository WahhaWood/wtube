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
val WIcons.ExpandMore: ImageVector
  get() {
    if (_ExpandMore != null) {
      return _ExpandMore!!
    }
    _ExpandMore =
      ImageVector.Builder(
          name = "ExpandMore",
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
            moveTo(11.63f, 14.89f)
            quadTo(11.45f, 14.83f, 11.3f, 14.68f)
            lineTo(6.7f, 10.07f)
            quadTo(6.43f, 9.8f, 6.43f, 9.38f)
            reflectiveQuadTo(6.7f, 8.67f)
            reflectiveQuadTo(7.4f, 8.4f)
            reflectiveQuadTo(8.1f, 8.67f)
            lineToRelative(3.9f, 3.9f)
            lineToRelative(3.9f, -3.9f)
            quadTo(16.18f, 8.4f, 16.6f, 8.4f)
            reflectiveQuadToRelative(0.7f, 0.28f)
            reflectiveQuadToRelative(0.27f, 0.7f)
            reflectiveQuadToRelative(-0.27f, 0.7f)
            lineToRelative(-4.6f, 4.6f)
            quadToRelative(-0.15f, 0.15f, -0.33f, 0.21f)
            reflectiveQuadTo(12f, 14.95f)
            reflectiveQuadTo(11.63f, 14.89f)
            close()
          }
        }
        .build()
    return _ExpandMore!!
  }

private var _ExpandMore: ImageVector? = null
