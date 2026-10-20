package dev.wahha.wtube.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import dev.wahha.wtube.data.History
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.VideoRow
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.History
import dev.wahha.wtube.ui.icon.WIcons

/** The whole local watch history, with how far each video got. */
@Composable
fun HistoryScreen() {
    val nav = LocalNavigator.current
    val entries by History.entries.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalBottomInset.current,
        ),
    ) {
        item {
            Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.library_history), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = History::clear) { Text(stringResource(R.string.library_history_clear)) }
            }
        }
        if (entries.isEmpty()) item { MessageState(WIcons.History, stringResource(R.string.library_history_empty)) }
        items(entries, key = { it.video.id }) { entry ->
            val duration = entry.video.duration * 1000
            val progress = if (duration > 0) (entry.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(Modifier.animateItem()) { VideoRow(entry.video, progress) { nav.openVideo(entry.video.id, it) } }
        }
    }
}
