package dev.wahha.wtube.ui.video

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.player.PlayerState
import dev.wahha.wtube.player.SUBTITLE_AUTO
import dev.wahha.wtube.player.SUBTITLE_TRANSLATED
import dev.wahha.wtube.player.Sleep
import dev.wahha.wtube.player.SleepTimer
import dev.wahha.wtube.ui.icon.Add
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.Audiotrack
import dev.wahha.wtube.ui.icon.Bedtime
import dev.wahha.wtube.ui.icon.Check
import dev.wahha.wtube.ui.icon.ChevronRight
import dev.wahha.wtube.ui.icon.ClosedCaption
import dev.wahha.wtube.ui.icon.Headphones
import dev.wahha.wtube.ui.icon.HighQuality
import dev.wahha.wtube.ui.icon.Info
import dev.wahha.wtube.ui.icon.Remove
import dev.wahha.wtube.ui.icon.Repeat
import dev.wahha.wtube.ui.icon.SkipNext
import dev.wahha.wtube.ui.icon.Speed
import dev.wahha.wtube.ui.icon.WIcons
import java.util.Locale
import kotlin.math.roundToInt

private enum class SettingsPage { Main, Quality, Speed, Subtitles, Audio, Sleep }

private val QuickSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private const val MIN_SPEED = 0.25f
private const val MAX_SPEED = 3f
private const val SPEED_STEP = 0.05f
private val SleepMinutes = listOf(15, 30, 45, 60, 90)

/**
 * Everything about the current video, YouTube style: a summary list, each row opening its own options.
 * Speed and caption choices are saved to Settings (the service applies them, also to the next videos);
 * quality and audio track are overrides for this video only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerSettingsSheet(player: Player, state: PlayerState, onStats: () -> Unit, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(SettingsPage.Main) }
    val settings by Settings.current.collectAsStateWithLifecycle()
    val sleep by SleepTimer.sleep.collectAsStateWithLifecycle()
    val groups = state.tracks.groups
    val video = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }
    val audioTracks = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
    val subtitles = groups.filter { it.type == C.TRACK_TYPE_TEXT }
    val parameters = state.parameters
    val qualityOverridden = parameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
    val playing = video?.let { group ->
        (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { qualityLabel(group.getTrackFormat(it)) }
    }
    val autoLabel = if (playing != null) stringResource(R.string.player_quality_auto_current, playing) else stringResource(R.string.player_quality_auto)
    val subtitlesOff = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes
    val audioOnly = C.TRACK_TYPE_VIDEO in parameters.disabledTrackTypes
    fun update(change: TrackSelectionParameters.Builder.() -> Unit) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply(change).build()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        ImmersiveDialog()
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (page) {
                SettingsPage.Main -> {
                    if (video != null) {
                        SettingRow(WIcons.HighQuality, stringResource(R.string.player_quality), if (qualityOverridden) playing.orEmpty() else autoLabel) {
                            page = SettingsPage.Quality
                        }
                    }
                    SettingRow(WIcons.Speed, stringResource(R.string.player_speed), speedLabel(state.speed)) { page = SettingsPage.Speed }
                    if (subtitles.isNotEmpty()) {
                        val selected = subtitles.firstOrNull { it.isSelected }?.getTrackFormat(0)
                        val value = if (subtitlesOff || selected == null) stringResource(R.string.player_off) else subtitleLabel(selected)
                        SettingRow(WIcons.ClosedCaption, stringResource(R.string.player_subtitles), value) { page = SettingsPage.Subtitles }
                    }
                    if (audioTracks.size > 1) {
                        val index = audioTracks.indexOfFirst { it.isSelected }
                        val value = audioTracks.getOrNull(index)?.let { audioLabel(it.getTrackFormat(0), index) }.orEmpty()
                        SettingRow(WIcons.Audiotrack, stringResource(R.string.player_audio_track), value) { page = SettingsPage.Audio }
                    }
                    SettingRow(WIcons.Bedtime, stringResource(R.string.player_sleep_timer), sleepLabel(sleep)) { page = SettingsPage.Sleep }
                    ToggleRow(WIcons.Repeat, stringResource(R.string.player_loop), state.repeatMode == Player.REPEAT_MODE_ONE) {
                        player.repeatMode = if (it) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    }
                    ToggleRow(WIcons.SkipNext, stringResource(R.string.player_autoplay), settings.autoplay) { Settings.set(Settings.Autoplay, it) }
                    ToggleRow(WIcons.Headphones, stringResource(R.string.player_audio_only), audioOnly) { update { setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, it) } }
                    SettingRow(WIcons.Info, stringResource(R.string.player_stats), null) {
                        onStats()
                        onDismiss()
                    }
                }
                SettingsPage.Quality -> {
                    SheetHeader(stringResource(R.string.player_quality)) { page = SettingsPage.Main }
                    OptionRow(if (qualityOverridden) stringResource(R.string.player_quality_auto) else autoLabel, selected = !qualityOverridden) {
                        update { clearOverridesOfType(C.TRACK_TYPE_VIDEO) }
                        onDismiss()
                    }
                    if (video != null) {
                        (0 until video.length)
                            .filter { video.isTrackSupported(it) }
                            .sortedByDescending { video.getTrackFormat(it).height }
                            .forEach { index ->
                                OptionRow(qualityLabel(video.getTrackFormat(index)), selected = qualityOverridden && video.isTrackSelected(index)) {
                                    update { setOverrideForType(TrackSelectionOverride(video.mediaTrackGroup, index)) }
                                    onDismiss()
                                }
                            }
                    }
                }
                SettingsPage.Speed -> {
                    SheetHeader(stringResource(R.string.player_speed)) { page = SettingsPage.Main }
                    SpeedPicker(state.speed)
                }
                SettingsPage.Subtitles -> {
                    SheetHeader(stringResource(R.string.player_subtitles)) { page = SettingsPage.Main }
                    OptionRow(stringResource(R.string.player_off), selected = subtitlesOff) {
                        update { setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).clearOverridesOfType(C.TRACK_TYPE_TEXT) }
                        Settings.set(Settings.Subtitles, false)
                        onDismiss()
                    }
                    subtitles.sortedBy { subtitleOrder(it.getTrackFormat(0)) }.forEach { group ->
                        val format = group.getTrackFormat(0)
                        OptionRow(subtitleLabel(format), selected = !subtitlesOff && group.isSelected) {
                            update {
                                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                            }
                            Settings.set(Settings.Subtitles, true)
                            format.language?.let { Settings.set(Settings.SubtitleLanguage, it) }
                            onDismiss()
                        }
                    }
                }
                SettingsPage.Audio -> {
                    SheetHeader(stringResource(R.string.player_audio_track)) { page = SettingsPage.Main }
                    audioTracks.forEachIndexed { index, group ->
                        OptionRow(audioLabel(group.getTrackFormat(0), index), selected = group.isSelected) {
                            // Every bitrate of the track, so the player still adapts within it.
                            update { setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, (0 until group.length).toList())) }
                            onDismiss()
                        }
                    }
                }
                SettingsPage.Sleep -> {
                    SheetHeader(stringResource(R.string.player_sleep_timer)) { page = SettingsPage.Main }
                    OptionRow(stringResource(R.string.player_off), selected = sleep == null) {
                        SleepTimer.cancel()
                        onDismiss()
                    }
                    SleepMinutes.forEach { minutes ->
                        OptionRow(stringResource(R.string.player_sleep_minutes, minutes), selected = false) {
                            SleepTimer.inMinutes(minutes)
                            onDismiss()
                        }
                    }
                    OptionRow(stringResource(R.string.player_sleep_end_of_video), selected = sleep?.endOfVideo == true) {
                        SleepTimer.atEndOfVideo()
                        onDismiss()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A slider in 0.05 steps from 0.25x to 3x with −/+ buttons, and the usual speeds as chips below. */
@Composable
private fun SpeedPicker(current: Float) {
    // The slider moves freely under the finger; the speed is saved (and applied) on release.
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = dragging ?: current
    fun pick(speed: Float) = Settings.set(Settings.Speed, roundSpeed(speed.coerceIn(MIN_SPEED, MAX_SPEED)))
    Text(
        speedLabel(roundSpeed(shown)),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { pick(current - SPEED_STEP) }) { Icon(WIcons.Remove, contentDescription = stringResource(R.string.player_speed_slower)) }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let(::pick)
                dragging = null
            },
            valueRange = MIN_SPEED..MAX_SPEED,
            steps = ((MAX_SPEED - MIN_SPEED) / SPEED_STEP).roundToInt() - 1,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { pick(current + SPEED_STEP) }) { Icon(WIcons.Add, contentDescription = stringResource(R.string.player_speed_faster)) }
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickSpeeds.forEach { speed ->
            FilterChip(selected = current == speed, onClick = { pick(speed) }, label = { Text(speedLabel(speed)) })
        }
    }
}

// Dividing (not multiplying by 0.05) keeps labels like "1,15x" free of float noise.
private fun roundSpeed(speed: Float): Float = (speed / SPEED_STEP).roundToInt() / (1 / SPEED_STEP).roundToInt().toFloat()

@Composable
internal fun speedLabel(speed: Float): String =
    if (speed == 1f) stringResource(R.string.player_speed_normal) else speed.toString().removeSuffix(".0").replace('.', ',') + "x"

private fun qualityLabel(format: Format): String {
    val fps = if (format.frameRate > 48f) format.frameRate.toInt().toString() else ""
    return "${format.height}p$fps"
}

/** "Английский", from a language tag, in Russian whatever the phone's language. */
internal fun languageName(tag: String?): String? = tag?.takeIf { it.isNotEmpty() }?.let {
    Locale.forLanguageTag(it).getDisplayName(Locale.forLanguageTag("ru")).replaceFirstChar { c -> c.titlecase(Locale.forLanguageTag("ru")) }
}

@Composable
private fun subtitleLabel(format: Format): String {
    val name = languageName(format.language) ?: format.language.orEmpty()
    return when (format.subtitleKind) {
        SUBTITLE_TRANSLATED -> stringResource(R.string.player_subtitles_translated, name)
        SUBTITLE_AUTO -> stringResource(R.string.player_subtitles_auto, name)
        else -> name
    }
}

/** Human tracks by language first, then auto-generated, then the machine translation. */
private fun subtitleOrder(format: Format): String {
    val kind = when (format.subtitleKind) {
        SUBTITLE_TRANSLATED -> 2
        SUBTITLE_AUTO -> 1
        else -> 0
    }
    return "$kind ${languageName(format.language)}"
}

@Composable
private fun audioLabel(format: Format, index: Int): String {
    val name = languageName(format.language) ?: return stringResource(R.string.player_audio_unknown, index + 1)
    return when {
        format.roleFlags and C.ROLE_FLAG_MAIN != 0 -> stringResource(R.string.player_audio_original, name)
        format.roleFlags and C.ROLE_FLAG_DUB != 0 -> stringResource(R.string.player_audio_dubbed, name)
        format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 -> stringResource(R.string.player_audio_descriptive, name)
        else -> name
    }
}

@Composable
private fun sleepLabel(sleep: Sleep?): String = when {
    sleep == null -> stringResource(R.string.player_off)
    sleep.endOfVideo -> stringResource(R.string.player_sleep_end_of_video)
    else -> {
        val left = ((sleep.atMs ?: 0) - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        stringResource(R.string.player_sleep_minutes, ((left + 59_999) / 60_000).toInt())
    }
}

// MergingMediaSource prefixes merged formats' ids with the source index ("3:auto").
private val Format.subtitleKind: String? get() = id?.substringAfterLast(':')

private val Tracks.Group.isSelected: Boolean get() = (0 until length).any { isTrackSelected(it) }

@Composable
private fun SettingRow(icon: ImageVector, label: String, value: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurface)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium)
        Icon(WIcons.ChevronRight, contentDescription = null, tint = colors.outline)
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(start = 24.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurface)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) colors.primary else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(WIcons.Check, contentDescription = null, tint = colors.primary)
    }
}
