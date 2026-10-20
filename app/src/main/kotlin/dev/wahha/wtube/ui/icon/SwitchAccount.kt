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
val WIcons.SwitchAccount: ImageVector
  get() {
    if (_SwitchAccount != null) {
      return _SwitchAccount!!
    }
    _SwitchAccount =
      ImageVector.Builder(
          name = "SwitchAccount",
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
            moveTo(16.13f, 10.13f)
            quadTo(17f, 9.25f, 17f, 8f)
            reflectiveQuadTo(16.13f, 5.88f)
            reflectiveQuadTo(14f, 5f)
            reflectiveQuadTo(11.88f, 5.88f)
            reflectiveQuadTo(11f, 8f)
            reflectiveQuadToRelative(0.88f, 2.13f)
            reflectiveQuadTo(14f, 11f)
            reflectiveQuadToRelative(2.13f, -0.88f)
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
            moveTo(8f, 16f)
            horizontalLineTo(20f)
            quadTo(18.95f, 14.58f, 17.36f, 13.79f)
            reflectiveQuadTo(14f, 13f)
            reflectiveQuadToRelative(-3.36f, 0.79f)
            reflectiveQuadTo(8f, 16f)
            close()
          }
        }
        .build()
    return _SwitchAccount!!
  }

private var _SwitchAccount: ImageVector? = null
