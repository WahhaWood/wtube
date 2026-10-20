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
val WIcons.Forward10: ImageVector
  get() {
    if (_Forward10 != null) {
      return _Forward10!!
    }
    _Forward10 =
      ImageVector.Builder(
          name = "Forward10",
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
            moveTo(8.49f, 21.29f)
            quadTo(6.85f, 20.58f, 5.64f, 19.36f)
            reflectiveQuadTo(3.71f, 16.51f)
            reflectiveQuadTo(3f, 13f)
            reflectiveQuadTo(3.71f, 9.49f)
            reflectiveQuadTo(5.64f, 6.64f)
            reflectiveQuadTo(8.49f, 4.71f)
            reflectiveQuadTo(12f, 4f)
            horizontalLineToRelative(0.15f)
            lineTo(11.3f, 3.15f)
            quadTo(11f, 2.85f, 11.01f, 2.45f)
            reflectiveQuadTo(11.3f, 1.75f)
            quadToRelative(0.3f, -0.3f, 0.71f, -0.31f)
            quadToRelative(0.41f, -0.01f, 0.71f, 0.29f)
            lineTo(15.3f, 4.3f)
            quadTo(15.6f, 4.6f, 15.6f, 5f)
            reflectiveQuadTo(15.3f, 5.7f)
            lineTo(12.73f, 8.27f)
            quadToRelative(-0.3f, 0.3f, -0.71f, 0.29f)
            reflectiveQuadTo(11.3f, 8.25f)
            quadTo(11.03f, 7.95f, 11.01f, 7.55f)
            reflectiveQuadTo(11.3f, 6.85f)
            lineTo(12.15f, 6f)
            horizontalLineTo(12f)
            quadTo(9.08f, 6f, 7.04f, 8.04f)
            reflectiveQuadTo(5f, 13f)
            reflectiveQuadToRelative(2.04f, 4.96f)
            reflectiveQuadTo(12f, 20f)
            reflectiveQuadToRelative(4.96f, -2.04f)
            quadTo(19f, 15.93f, 19f, 13f)
            quadToRelative(0f, -0.43f, 0.29f, -0.71f)
            reflectiveQuadTo(20f, 12f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(21f, 13f)
            quadToRelative(0f, 1.88f, -0.71f, 3.51f)
            reflectiveQuadToRelative(-1.93f, 2.85f)
            reflectiveQuadToRelative(-2.85f, 1.93f)
            reflectiveQuadTo(12f, 22f)
            reflectiveQuadTo(8.49f, 21.29f)
            close()
            moveTo(9f, 11.5f)
            horizontalLineTo(8.25f)
            quadToRelative(-0.32f, 0f, -0.54f, -0.21f)
            reflectiveQuadTo(7.5f, 10.75f)
            reflectiveQuadTo(7.71f, 10.21f)
            reflectiveQuadTo(8.25f, 10f)
            horizontalLineToRelative(1.5f)
            quadToRelative(0.33f, 0f, 0.54f, 0.21f)
            quadToRelative(0.21f, 0.21f, 0.21f, 0.54f)
            verticalLineToRelative(4.5f)
            quadToRelative(0f, 0.32f, -0.21f, 0.54f)
            reflectiveQuadTo(9.75f, 16f)
            quadTo(9.43f, 16f, 9.21f, 15.79f)
            reflectiveQuadTo(9f, 15.25f)
            verticalLineTo(11.5f)
            close()
            moveTo(12.5f, 16f)
            quadToRelative(-0.42f, 0f, -0.71f, -0.29f)
            reflectiveQuadTo(11.5f, 15f)
            verticalLineTo(11f)
            quadToRelative(0f, -0.43f, 0.29f, -0.71f)
            reflectiveQuadTo(12.5f, 10f)
            horizontalLineToRelative(2f)
            quadToRelative(0.43f, 0f, 0.71f, 0.29f)
            reflectiveQuadTo(15.5f, 11f)
            verticalLineToRelative(4f)
            quadToRelative(0f, 0.42f, -0.29f, 0.71f)
            reflectiveQuadTo(14.5f, 16f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(13f, 14.5f)
            horizontalLineToRelative(1f)
            verticalLineToRelative(-3f)
            horizontalLineTo(13f)
            verticalLineToRelative(3f)
            close()
          }
        }
        .build()
    return _Forward10!!
  }

private var _Forward10: ImageVector? = null
