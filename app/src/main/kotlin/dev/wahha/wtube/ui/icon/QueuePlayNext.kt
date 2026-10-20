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
val WIcons.QueuePlayNext: ImageVector
  get() {
    if (_QueuePlayNext != null) {
      return _QueuePlayNext!!
    }
    _QueuePlayNext =
      ImageVector.Builder(
          name = "QueuePlayNext",
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
            moveTo(21f, 18f)
            lineTo(18.75f, 15.75f)
            quadTo(18.43f, 15.43f, 18.43f, 15f)
            reflectiveQuadToRelative(0.32f, -0.75f)
            reflectiveQuadTo(19.5f, 13.93f)
            reflectiveQuadToRelative(0.75f, 0.33f)
            lineTo(23.3f, 17.3f)
            quadToRelative(0.3f, 0.3f, 0.3f, 0.7f)
            reflectiveQuadToRelative(-0.3f, 0.7f)
            lineToRelative(-3.05f, 3.05f)
            quadToRelative(-0.32f, 0.32f, -0.75f, 0.32f)
            reflectiveQuadTo(18.75f, 21.75f)
            reflectiveQuadTo(18.43f, 21f)
            reflectiveQuadToRelative(0.32f, -0.75f)
            lineTo(21f, 18f)
            close()
            moveTo(9f, 21f)
            quadTo(8.58f, 21f, 8.29f, 20.71f)
            quadTo(8f, 20.43f, 8f, 20f)
            verticalLineTo(19f)
            horizontalLineTo(4f)
            quadTo(3.18f, 19f, 2.59f, 18.41f)
            reflectiveQuadTo(2f, 17f)
            verticalLineTo(5f)
            quadTo(2f, 4.17f, 2.59f, 3.59f)
            reflectiveQuadTo(4f, 3f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 5f)
            verticalLineToRelative(6f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(21f, 12f)
            quadToRelative(-0.42f, 0f, -0.71f, -0.29f)
            quadTo(20f, 11.43f, 20f, 11f)
            verticalLineTo(5f)
            horizontalLineTo(4f)
            verticalLineTo(17f)
            horizontalLineTo(16f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(17f, 18f)
            quadToRelative(0f, 0.43f, -0.29f, 0.71f)
            reflectiveQuadTo(16f, 19f)
            horizontalLineTo(15f)
            verticalLineToRelative(1f)
            quadToRelative(0f, 0.43f, -0.29f, 0.71f)
            reflectiveQuadTo(14f, 21f)
            horizontalLineTo(9f)
            close()
            moveToRelative(2f, -9f)
            verticalLineToRelative(2f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(12f, 15f)
            reflectiveQuadToRelative(0.71f, -0.29f)
            reflectiveQuadTo(13f, 14f)
            verticalLineTo(12f)
            horizontalLineToRelative(2f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            quadTo(16f, 11.43f, 16f, 11f)
            reflectiveQuadTo(15.71f, 10.29f)
            reflectiveQuadTo(15f, 10f)
            horizontalLineTo(13f)
            verticalLineTo(8f)
            quadTo(13f, 7.57f, 12.71f, 7.29f)
            reflectiveQuadTo(12f, 7f)
            reflectiveQuadTo(11.29f, 7.29f)
            reflectiveQuadTo(11f, 8f)
            verticalLineToRelative(2f)
            horizontalLineTo(9f)
            quadTo(8.58f, 10f, 8.29f, 10.29f)
            reflectiveQuadTo(8f, 11f)
            reflectiveQuadToRelative(0.29f, 0.71f)
            quadTo(8.58f, 12f, 9f, 12f)
            horizontalLineToRelative(2f)
            close()
            moveTo(4f, 17f)
            verticalLineTo(5f)
            verticalLineTo(17f)
            close()
          }
        }
        .build()
    return _QueuePlayNext!!
  }

private var _QueuePlayNext: ImageVector? = null
