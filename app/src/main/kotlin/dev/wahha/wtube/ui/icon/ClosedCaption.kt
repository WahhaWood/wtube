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
val WIcons.ClosedCaption: ImageVector
  get() {
    if (_ClosedCaption != null) {
      return _ClosedCaption!!
    }
    _ClosedCaption =
      ImageVector.Builder(
          name = "ClosedCaption",
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
            moveTo(5f, 20f)
            quadTo(4.18f, 20f, 3.59f, 19.41f)
            reflectiveQuadTo(3f, 18f)
            verticalLineTo(6f)
            quadTo(3f, 5.18f, 3.59f, 4.59f)
            reflectiveQuadTo(5f, 4f)
            horizontalLineTo(19f)
            quadToRelative(0.83f, 0f, 1.41f, 0.59f)
            quadTo(21f, 5.18f, 21f, 6f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(19f, 20f)
            horizontalLineTo(5f)
            close()
            moveTo(5f, 18f)
            horizontalLineTo(19f)
            verticalLineTo(6f)
            horizontalLineTo(5f)
            verticalLineTo(18f)
            close()
            moveTo(7f, 15f)
            horizontalLineToRelative(3f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            reflectiveQuadTo(11f, 14f)
            verticalLineTo(13.5f)
            quadToRelative(0f, -0.23f, -0.15f, -0.38f)
            reflectiveQuadTo(10.48f, 12.98f)
            horizontalLineTo(10.03f)
            quadToRelative(-0.22f, 0f, -0.38f, 0.15f)
            quadTo(9.5f, 13.27f, 9.5f, 13.5f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(2f)
            quadToRelative(0f, 0.22f, 0.15f, 0.38f)
            quadToRelative(0.15f, 0.15f, 0.38f, 0.15f)
            horizontalLineToRelative(0.45f)
            quadToRelative(0.22f, 0f, 0.38f, -0.15f)
            reflectiveQuadTo(11f, 10.5f)
            verticalLineTo(10f)
            quadTo(11f, 9.57f, 10.71f, 9.29f)
            reflectiveQuadTo(10f, 9f)
            horizontalLineTo(7f)
            quadTo(6.58f, 9f, 6.29f, 9.29f)
            reflectiveQuadTo(6f, 10f)
            verticalLineToRelative(4f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(7f, 15f)
            close()
            moveTo(17f, 9f)
            horizontalLineTo(14f)
            quadTo(13.58f, 9f, 13.29f, 9.29f)
            reflectiveQuadTo(13f, 10f)
            verticalLineToRelative(4f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(14f, 15f)
            horizontalLineToRelative(3f)
            quadToRelative(0.43f, 0f, 0.71f, -0.29f)
            reflectiveQuadTo(18f, 14f)
            verticalLineTo(13.5f)
            quadToRelative(0f, -0.23f, -0.15f, -0.38f)
            reflectiveQuadTo(17.48f, 12.98f)
            horizontalLineTo(17.03f)
            quadToRelative(-0.23f, 0f, -0.38f, 0.15f)
            quadTo(16.5f, 13.27f, 16.5f, 13.5f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(2f)
            quadToRelative(0f, 0.22f, 0.15f, 0.38f)
            quadToRelative(0.15f, 0.15f, 0.38f, 0.15f)
            horizontalLineToRelative(0.45f)
            quadToRelative(0.22f, 0f, 0.38f, -0.15f)
            reflectiveQuadTo(18f, 10.5f)
            verticalLineTo(10f)
            quadTo(18f, 9.57f, 17.71f, 9.29f)
            reflectiveQuadTo(17f, 9f)
            close()
            moveTo(5f, 18f)
            verticalLineTo(6f)
            verticalLineTo(18f)
            close()
          }
        }
        .build()
    return _ClosedCaption!!
  }

private var _ClosedCaption: ImageVector? = null
