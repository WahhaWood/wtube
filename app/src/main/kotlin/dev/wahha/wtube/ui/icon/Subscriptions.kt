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
val WIcons.Subscriptions: ImageVector
  get() {
    if (_Subscriptions != null) {
      return _Subscriptions!!
    }
    _Subscriptions =
      ImageVector.Builder(
          name = "Subscriptions",
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
            moveTo(4f, 22f)
            quadTo(3.18f, 22f, 2.59f, 21.41f)
            reflectiveQuadTo(2f, 20f)
            verticalLineTo(10f)
            quadTo(2f, 9.17f, 2.59f, 8.59f)
            reflectiveQuadTo(4f, 8f)
            horizontalLineTo(20f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            reflectiveQuadTo(22f, 10f)
            verticalLineTo(20f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(20f, 22f)
            horizontalLineTo(4f)
            close()
            moveToRelative(6.78f, -3.52f)
            lineToRelative(4.6f, -3.05f)
            quadTo(15.6f, 15.28f, 15.6f, 15f)
            reflectiveQuadTo(15.38f, 14.58f)
            lineToRelative(-4.6f, -3.05f)
            quadTo(10.53f, 11.35f, 10.26f, 11.49f)
            quadTo(10f, 11.63f, 10f, 11.93f)
            verticalLineToRelative(6.15f)
            quadToRelative(0f, 0.3f, 0.26f, 0.44f)
            reflectiveQuadToRelative(0.51f, -0.04f)
            close()
            moveTo(5f, 7f)
            quadTo(4.58f, 7f, 4.29f, 6.71f)
            quadTo(4f, 6.43f, 4f, 6f)
            reflectiveQuadTo(4.29f, 5.29f)
            reflectiveQuadTo(5f, 5f)
            horizontalLineTo(19f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(20f, 6f)
            reflectiveQuadTo(19.71f, 6.71f)
            reflectiveQuadTo(19f, 7f)
            horizontalLineTo(5f)
            close()
            moveTo(8f, 4f)
            quadTo(7.58f, 4f, 7.29f, 3.71f)
            reflectiveQuadTo(7f, 3f)
            quadTo(7f, 2.57f, 7.29f, 2.29f)
            reflectiveQuadTo(8f, 2f)
            horizontalLineToRelative(8f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(17f, 3f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(16f, 4f)
            horizontalLineTo(8f)
            close()
          }
        }
        .build()
    return _Subscriptions!!
  }

private var _Subscriptions: ImageVector? = null
