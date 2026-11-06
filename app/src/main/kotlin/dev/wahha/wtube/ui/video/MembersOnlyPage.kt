package dev.wahha.wtube.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.wahha.wtube.R
import dev.wahha.wtube.data.MembersOnly
import dev.wahha.wtube.data.innertube.WatchVideo
import dev.wahha.wtube.data.watchUrl
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.openInYouTube
import dev.wahha.wtube.ui.icon.Star
import dev.wahha.wtube.ui.icon.WIcons

/** In place of the video's page when YouTube refuses it to non-members: what it is and where it can be watched. */
@Composable
internal fun MembersOnlyPage(id: String, video: WatchVideo?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val nav = LocalNavigator.current
    MessageState(
        WIcons.Star,
        stringResource(R.string.members_only_body),
        modifier,
        title = video?.title ?: stringResource(R.string.members_only_title),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { openInYouTube(context, watchUrl(id)) }) {
                Text(stringResource(R.string.members_only_open), color = LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
            }
            video?.channelUrl?.let { channel ->
                OutlinedButton(onClick = { openInYouTube(context, MembersOnly.joinUrl(channel)) }) {
                    Text(stringResource(R.string.members_only_join), color = LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
                }
                TextButton(onClick = { nav.openChannel(channel) }) {
                    Text(video.channelName ?: stringResource(R.string.members_only_channel), color = LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
