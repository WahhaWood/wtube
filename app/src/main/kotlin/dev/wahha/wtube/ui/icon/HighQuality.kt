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
val WIcons.HighQuality: ImageVector
  get() {
    if (_HighQuality != null) {
      return _HighQuality!!
    }
    _HighQuality =
      ImageVector.Builder(
          name = "HighQuality",
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
            moveTo(14.75f, 15f)
            verticalLineToRelative(0.75f)
            quadToRelative(0f, 0.32f, 0.21f, 0.54f)
            reflectiveQuadTo(15.5f, 16.5f)
            reflectiveQuadToRelative(0.54f, -0.21f)
            reflectiveQuadToRelative(0.21f, -0.54f)
            verticalLineTo(15f)
            horizontalLineTo(17f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            reflectiveQuadTo(18f, 14f)
            verticalLineTo(10f)
            quadTo(18f, 9.57f, 17.71f, 9.29f)
            reflectiveQuadTo(17f, 9f)
            horizontalLineTo(14f)
            quadTo(13.58f, 9f, 13.29f, 9.29f)
            reflectiveQuadTo(13f, 10f)
            verticalLineToRelative(4f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(14f, 15f)
            horizontalLineToRelative(0.75f)
            close()
            moveTo(7.5f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(1.25f)
            quadToRelative(0f, 0.32f, 0.21f, 0.54f)
            reflectiveQuadTo(10.25f, 15f)
            reflectiveQuadToRelative(0.54f, -0.21f)
            reflectiveQuadTo(11f, 14.25f)
            verticalLineTo(9.75f)
            quadTo(11f, 9.42f, 10.79f, 9.21f)
            reflectiveQuadTo(10.25f, 9f)
            quadTo(9.93f, 9f, 9.71f, 9.21f)
            reflectiveQuadTo(9.5f, 9.75f)
            verticalLineTo(11.5f)
            horizontalLineToRelative(-2f)
            verticalLineTo(9.75f)
            quadTo(7.5f, 9.42f, 7.29f, 9.21f)
            reflectiveQuadTo(6.75f, 9f)
            reflectiveQuadTo(6.21f, 9.21f)
            reflectiveQuadTo(6f, 9.75f)
            verticalLineToRelative(4.5f)
            quadToRelative(0f, 0.32f, 0.21f, 0.54f)
            reflectiveQuadTo(6.75f, 15f)
            quadToRelative(0.33f, 0f, 0.54f, -0.21f)
            reflectiveQuadTo(7.5f, 14.25f)
            verticalLineTo(13f)
            close()
            moveToRelative(7f, 0.5f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(4f, 20f)
            quadTo(3.18f, 20f, 2.59f, 19.41f)
            reflectiveQuadTo(2f, 18f)
            verticalLineTo(6f)
            quadTo(2f, 5.18f, 2.59f, 4.59f)
            reflectiveQuadTo(4f, 4f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(22f, 5.18f, 22f, 6f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 20f)
            horizontalLineTo(4f)
            close()
          }
        }
        .build()
    return _HighQuality!!
  }

private var _HighQuality: ImageVector? = null
