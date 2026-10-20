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
val WIcons.NorthWest: ImageVector
  get() {
    if (_NorthWest != null) {
      return _NorthWest!!
    }
    _NorthWest =
      ImageVector.Builder(
          name = "NorthWest",
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
            moveTo(7f, 8.4f)
            verticalLineTo(14f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(6f, 15f)
            quadTo(5.58f, 15f, 5.29f, 14.71f)
            reflectiveQuadTo(5f, 14f)
            verticalLineTo(6f)
            quadTo(5f, 5.57f, 5.29f, 5.29f)
            reflectiveQuadTo(6f, 5f)
            horizontalLineToRelative(8f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(15f, 6f)
            reflectiveQuadTo(14.71f, 6.71f)
            reflectiveQuadTo(14f, 7f)
            horizontalLineTo(8.4f)
            lineTo(19.3f, 17.9f)
            quadToRelative(0.28f, 0.28f, 0.28f, 0.7f)
            quadToRelative(0f, 0.42f, -0.28f, 0.7f)
            quadToRelative(-0.27f, 0.27f, -0.7f, 0.27f)
            reflectiveQuadTo(17.9f, 19.3f)
            lineTo(7f, 8.4f)
            close()
          }
        }
        .build()
    return _NorthWest!!
  }

private var _NorthWest: ImageVector? = null
