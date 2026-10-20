package dev.wahha.wtube.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.ui.account.AccountButton
import dev.wahha.wtube.ui.icon.Settings
import dev.wahha.wtube.ui.icon.WIcons

/** Title row of a tab screen, with the account and the way into Settings on the right. */
@Composable
fun ScreenHeader(title: String, leading: @Composable () -> Unit = {}) {
    val nav = LocalNavigator.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        AccountButton()
        IconButton(onClick = nav.openSettings, modifier = Modifier.testTag("open_settings")) {
            Icon(WIcons.Settings, contentDescription = stringResource(R.string.settings), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * The app icon in miniature, from the adaptive icon's own layers: drawn at 1.5x and clipped, because an
 * adaptive icon shows only the middle 72 of its 108 dp.
 */
@Composable
fun LogoMark(size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(size * 1.5f))
    }
}
