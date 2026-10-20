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
val WIcons.VideoLibrary: ImageVector
  get() {
    if (_VideoLibrary != null) {
      return _VideoLibrary!!
    }
    _VideoLibrary =
      ImageVector.Builder(
          name = "VideoLibrary",
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
            moveTo(17.53f, 10.63f)
            quadTo(17.88f, 10.4f, 17.88f, 10f)
            reflectiveQuadTo(17.53f, 9.38f)
            lineTo(12.65f, 6.25f)
            quadTo(12.28f, 6f, 11.89f, 6.21f)
            quadTo(11.5f, 6.43f, 11.5f, 6.88f)
            verticalLineToRelative(6.25f)
            quadToRelative(0f, 0.45f, 0.39f, 0.66f)
            reflectiveQuadToRelative(0.76f, -0.04f)
            lineToRelative(4.88f, -3.13f)
            close()
            moveTo(8f, 18f)
            quadTo(7.18f, 18f, 6.59f, 17.41f)
            reflectiveQuadTo(6f, 16f)
            verticalLineTo(4f)
            quadTo(6f, 3.17f, 6.59f, 2.59f)
            reflectiveQuadTo(8f, 2f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 4f)
            verticalLineTo(16f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 18f)
            horizontalLineTo(8f)
            close()
            moveTo(4f, 22f)
            quadTo(3.18f, 22f, 2.59f, 21.41f)
            reflectiveQuadTo(2f, 20f)
            verticalLineTo(7f)
            quadTo(2f, 6.57f, 2.29f, 6.29f)
            reflectiveQuadTo(3f, 6f)
            reflectiveQuadTo(3.71f, 6.29f)
            reflectiveQuadTo(4f, 7f)
            verticalLineTo(20f)
            horizontalLineTo(17f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(18f, 21f)
            reflectiveQuadToRelative(-0.29f, 0.71f)
            reflectiveQuadTo(17f, 22f)
            horizontalLineTo(4f)
            close()
          }
        }
        .build()
    return _VideoLibrary!!
  }

private var _VideoLibrary: ImageVector? = null
