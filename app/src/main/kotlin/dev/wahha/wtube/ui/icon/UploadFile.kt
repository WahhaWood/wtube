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
val WIcons.UploadFile: ImageVector
  get() {
    if (_UploadFile != null) {
      return _UploadFile!!
    }
    _UploadFile =
      ImageVector.Builder(
          name = "UploadFile",
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
            moveTo(11f, 14.83f)
            verticalLineTo(18f)
            quadToRelative(0f, 0.43f, 0.29f, 0.71f)
            reflectiveQuadTo(12f, 19f)
            reflectiveQuadToRelative(0.71f, -0.29f)
            quadTo(13f, 18.43f, 13f, 18f)
            verticalLineTo(14.83f)
            lineToRelative(0.9f, 0.9f)
            quadToRelative(0.15f, 0.15f, 0.34f, 0.22f)
            reflectiveQuadToRelative(0.38f, 0.06f)
            reflectiveQuadToRelative(0.36f, -0.09f)
            reflectiveQuadTo(15.3f, 15.7f)
            quadTo(15.58f, 15.4f, 15.59f, 15f)
            reflectiveQuadTo(15.3f, 14.3f)
            lineTo(12.7f, 11.7f)
            quadTo(12.55f, 11.55f, 12.38f, 11.49f)
            reflectiveQuadTo(12f, 11.43f)
            reflectiveQuadToRelative(-0.38f, 0.06f)
            reflectiveQuadTo(11.3f, 11.7f)
            lineTo(8.7f, 14.3f)
            quadTo(8.4f, 14.6f, 8.41f, 15f)
            quadToRelative(0.01f, 0.4f, 0.31f, 0.7f)
            quadToRelative(0.3f, 0.28f, 0.7f, 0.29f)
            reflectiveQuadToRelative(0.7f, -0.29f)
            lineTo(11f, 14.83f)
            close()
            moveTo(6f, 22f)
            quadTo(5.18f, 22f, 4.59f, 21.41f)
            reflectiveQuadTo(4f, 20f)
            verticalLineTo(4f)
            quadTo(4f, 3.17f, 4.59f, 2.59f)
            reflectiveQuadTo(6f, 2f)
            horizontalLineToRelative(7.18f)
            quadToRelative(0.4f, 0f, 0.76f, 0.15f)
            reflectiveQuadToRelative(0.64f, 0.43f)
            lineToRelative(4.85f, 4.85f)
            quadTo(19.7f, 7.7f, 19.85f, 8.06f)
            quadTo(20f, 8.42f, 20f, 8.82f)
            verticalLineTo(20f)
            quadToRelative(0f, 0.82f, -0.59f, 1.41f)
            reflectiveQuadTo(18f, 22f)
            horizontalLineTo(6f)
            close()
            moveTo(13f, 8f)
            quadToRelative(0f, 0.42f, 0.29f, 0.71f)
            reflectiveQuadTo(14f, 9f)
            horizontalLineToRelative(4f)
            lineTo(13f, 4f)
            verticalLineTo(8f)
            close()
          }
        }
        .build()
    return _UploadFile!!
  }

private var _UploadFile: ImageVector? = null
