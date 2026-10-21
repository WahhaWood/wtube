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
val WIcons.Forum: ImageVector
  get() {
    if (_Forum != null) {
      return _Forum!!
    }
    _Forum =
      ImageVector.Builder(
          name = "Forum",
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
            moveTo(21f, 20.6f)
            quadToRelative(-0.2f, 0f, -0.38f, -0.08f)
            reflectiveQuadTo(20.3f, 20.3f)
            lineTo(18f, 18f)
            horizontalLineTo(8f)
            quadTo(7.18f, 18f, 6.59f, 17.41f)
            reflectiveQuadTo(6f, 16f)
            verticalLineTo(15f)
            horizontalLineTo(17f)
            quadToRelative(0.82f, 0f, 1.41f, -0.59f)
            reflectiveQuadTo(19f, 13f)
            verticalLineTo(6f)
            horizontalLineToRelative(1f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(22f, 7.18f, 22f, 8f)
            verticalLineTo(19.58f)
            quadToRelative(0f, 0.45f, -0.3f, 0.74f)
            reflectiveQuadTo(21f, 20.6f)
            close()
            moveTo(4f, 12.18f)
            lineTo(5.18f, 11f)
            horizontalLineTo(15f)
            verticalLineTo(4f)
            horizontalLineTo(4f)
            verticalLineToRelative(8.17f)
            close()
            moveTo(3f, 15.6f)
            quadToRelative(-0.4f, 0f, -0.7f, -0.29f)
            reflectiveQuadTo(2f, 14.58f)
            verticalLineTo(4f)
            quadTo(2f, 3.17f, 2.59f, 2.59f)
            reflectiveQuadTo(4f, 2f)
            horizontalLineTo(15f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(17f, 4f)
            verticalLineToRelative(7f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(15f, 13f)
            horizontalLineTo(6f)
            lineTo(3.7f, 15.3f)
            quadTo(3.55f, 15.45f, 3.38f, 15.53f)
            reflectiveQuadTo(3f, 15.6f)
            close()
            moveTo(4f, 11f)
            verticalLineTo(4f)
            verticalLineToRelative(7f)
            close()
          }
        }
        .build()
    return _Forum!!
  }

private var _Forum: ImageVector? = null
