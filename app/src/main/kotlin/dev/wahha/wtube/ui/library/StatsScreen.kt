package dev.wahha.wtube.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import dev.wahha.wtube.data.StatsRange
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.formatWatchTime
import dev.wahha.wtube.data.summarize
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.BarChart
import dev.wahha.wtube.ui.icon.WIcons

/** Time actually spent watching: totals, a chart by day or week, and the top channels and videos. */
@Composable
fun StatsScreen() {
    val nav = LocalNavigator.current
    val entries by WatchStats.entries.collectAsStateWithLifecycle()
    var range by rememberSaveable { mutableStateOf(StatsRange.WEEK) }
    var confirmReset by remember { mutableStateOf(false) }
    val summary by remember(entries, range) {
        derivedStateOf { summarize(entries, WatchStats.dayOf(System.currentTimeMillis()), range) }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = systemTop(), bottom = systemBottom() + LocalBottomInset.current),
    ) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.stats_title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (entries.isNotEmpty()) TextButton(onClick = { confirmReset = true }) { Text(stringResource(R.string.stats_reset)) }
            }
        }
        if (entries.isEmpty()) {
            item { MessageState(WIcons.BarChart, stringResource(R.string.stats_empty)) }
            return@LazyColumn
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                StatsRange.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = range == option,
                        onClick = { range = option },
                        shape = SegmentedButtonDefaults.itemShape(index, StatsRange.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (option) {
                                    StatsRange.WEEK -> R.string.stats_week
                                    StatsRange.MONTH -> R.string.stats_month
                                    StatsRange.ALL -> R.string.stats_all
                                },
                            ),
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.stats_total), style = MaterialTheme.typography.bodySmall)
                Text(formatWatchTime(summary.totalSeconds), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure(stringResource(R.string.stats_videos), summary.videos.toString(), Modifier.weight(1f))
                Figure(stringResource(R.string.stats_days), summary.activeDays.toString(), Modifier.weight(1f))
                Figure(stringResource(R.string.stats_streak), summary.streak.toString(), Modifier.weight(1f))
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    stringResource(if (range == StatsRange.ALL) R.string.stats_chart_weeks else R.string.stats_chart_day),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                BarChart(summary.chart)
            }
        }
        if (summary.channels.isNotEmpty()) {
            item { Text(stringResource(R.string.stats_channels), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) }
            val top = summary.channels.first().seconds.coerceAtLeast(1)
            summary.channels.forEach { channel ->
                item(key = "c:${channel.url ?: channel.name}") {
                    Ranked(channel.name, null, channel.seconds, channel.seconds.toFloat() / top, channel.url?.let { url -> { nav.openChannel(url) } })
                }
            }
        }
        if (summary.topVideos.isNotEmpty()) {
            item { Text(stringResource(R.string.stats_top_videos), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp)) }
            val top = summary.topVideos.first().seconds.coerceAtLeast(1)
            summary.topVideos.forEach { video ->
                item(key = "v:${video.id}") {
                    Ranked(video.title, video.channel, video.seconds, video.seconds.toFloat() / top) { nav.openVideo(video.id) }
                }
            }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            text = { Text(stringResource(R.string.stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    WatchStats.clear()
                    confirmReset = false
                }) { Text(stringResource(R.string.stats_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BarChart(values: List<Long>) {
    val bar = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(120.dp)) {
        val gap = 4.dp.toPx()
        val width = ((size.width - gap * (values.size - 1)) / values.size).coerceAtLeast(1f)
        val radius = CornerRadius(minOf(width / 2, 4.dp.toPx()))
        values.forEachIndexed { index, value ->
            val height = if (value > 0) (size.height * value / peak).coerceAtLeast(4.dp.toPx()) else 4.dp.toPx()
            drawRoundRect(
                color = if (value > 0) bar else empty,
                topLeft = Offset(index * (width + gap), size.height - height),
                size = Size(width, height),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun Ranked(title: String, subtitle: String?, seconds: Long, share: Float, onClick: (() -> Unit)?) {
    val fill = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(formatWatchTime(seconds), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
        }
        Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(track, size = size, cornerRadius = radius)
            drawRoundRect(fill, size = Size(size.width * share.coerceIn(0.02f, 1f), size.height), cornerRadius = radius)
        }
    }
}
