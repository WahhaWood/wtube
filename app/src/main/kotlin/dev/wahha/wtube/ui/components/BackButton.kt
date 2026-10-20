package dev.wahha.wtube.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.wahha.wtube.R
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.WIcons

/** Round back button floating over a header image, readable on any picture. */
@Composable
fun BackButton(modifier: Modifier = Modifier) {
    val nav = LocalNavigator.current
    IconButton(
        onClick = nav.back,
        modifier = modifier.background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurface)
    }
}
