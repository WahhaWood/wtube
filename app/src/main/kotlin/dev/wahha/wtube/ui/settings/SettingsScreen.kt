package dev.wahha.wtube.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.datastore.preferences.core.Preferences
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.BuildConfig
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Backup
import dev.wahha.wtube.data.ErrorLog
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.HiddenItems
import dev.wahha.wtube.data.History
import dev.wahha.wtube.data.SearchHistory
import dev.wahha.wtube.data.WatchStats
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.SponsorBlock
import dev.wahha.wtube.data.Updates
import dev.wahha.wtube.player.sponsorCategoryLabel
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.Thumbnail
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.glass.GlassSupported
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.Check
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.video.speedLabel
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen() {
    val nav = LocalNavigator.current
    val settings by Settings.current.collectAsStateWithLifecycle()
    val hidden by Hidden.items.collectAsStateWithLifecycle()
    val availableUpdate by Updates.available.collectAsStateWithLifecycle()
    var sheet by rememberSaveable { mutableStateOf<SettingsSheet?>(null) }
    var clearOpen by rememberSaveable { mutableStateOf(false) }
    var licensesOpen by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Settings, history and searches to a JSON file the user picks, and back.
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = Backup.current()
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } ?: error("No output") }
            }.isSuccess
            Notices.show(if (ok) R.string.settings_backup_saved else R.string.settings_backup_failed)
        }
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("No input") }
                Backup.restore(Backup.parse(text))
            }.isSuccess
            Notices.show(if (ok) R.string.settings_backup_restored else R.string.settings_backup_failed)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = systemTop(),
            bottom = systemBottom() + LocalBottomInset.current,
        ),
    ) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
            }
        }

        item { SectionHeader(stringResource(R.string.settings_playback)) }
        item { ValueRow(stringResource(R.string.settings_quality), qualityLabel(settings.maxQuality)) { sheet = SettingsSheet.Quality } }
        item { ValueRow(stringResource(R.string.settings_quality_mobile), qualityLabel(settings.maxQualityMobile)) { sheet = SettingsSheet.QualityMobile } }
        item { ValueRow(stringResource(R.string.settings_speed), speedLabel(settings.speed)) { sheet = SettingsSheet.Speed } }
        item {
            ValueRow(stringResource(R.string.settings_double_tap), stringResource(R.string.player_seek_amount, settings.doubleTapSeconds)) {
                sheet = SettingsSheet.DoubleTap
            }
        }
        item {
            SwitchRow(stringResource(R.string.player_autoplay), stringResource(R.string.settings_autoplay_summary), settings.autoplay, Settings.Autoplay)
        }
        item {
            SwitchRow(stringResource(R.string.settings_resume), stringResource(R.string.settings_resume_summary), settings.resumePlayback, Settings.ResumePlayback)
        }
        item {
            SwitchRow(stringResource(R.string.settings_background), stringResource(R.string.settings_background_summary), settings.backgroundPlayback, Settings.BackgroundPlayback)
        }
        item {
            SwitchRow(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_summary), settings.pictureInPicture, Settings.PictureInPicture)
        }

        item { SectionHeader(stringResource(R.string.settings_gestures)) }
        item {
            Text(
                stringResource(R.string.settings_gestures_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        val gestures = settings.gestureBrightness || settings.gestureVolume
        item {
            SwitchRow(
                stringResource(R.string.settings_gesture_brightness),
                stringResource(if (settings.gestureSwapSides) R.string.settings_gesture_right else R.string.settings_gesture_left),
                settings.gestureBrightness,
                Settings.GestureBrightness,
            )
        }
        item {
            SwitchRow(
                stringResource(R.string.settings_gesture_volume),
                stringResource(if (settings.gestureSwapSides) R.string.settings_gesture_left else R.string.settings_gesture_right),
                settings.gestureVolume,
                Settings.GestureVolume,
            )
        }
        item {
            ValueRow(stringResource(R.string.settings_gesture_sides), stringResource(gestureSidesName(settings.gestureSwapSides)), onClick = { sheet = SettingsSheet.GestureSides }.takeIf { gestures })
        }
        item {
            ValueRow(stringResource(R.string.settings_gesture_zone), stringResource(GestureZoneNames[settings.gestureZone]), onClick = { sheet = SettingsSheet.GestureZone }.takeIf { gestures })
        }
        item {
            ValueRow(
                stringResource(R.string.settings_gesture_sensitivity),
                stringResource(GestureSensitivityNames[settings.gestureSensitivity]),
                onClick = { sheet = SettingsSheet.GestureSensitivity }.takeIf { gestures },
            )
        }
        item {
            SwitchRow(
                stringResource(R.string.settings_gesture_remember),
                stringResource(R.string.settings_gesture_remember_summary),
                settings.gestureRememberBrightness,
                Settings.GestureRememberBrightness,
                enabled = settings.gestureBrightness,
            )
        }
        item {
            SwitchRow(
                stringResource(R.string.settings_gesture_haptics),
                stringResource(R.string.settings_gesture_haptics_summary),
                settings.gestureHaptics,
                Settings.GestureHaptics,
                enabled = gestures,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_subtitles)) }
        item {
            SwitchRow(stringResource(R.string.settings_subtitles_on), stringResource(R.string.settings_subtitles_on_summary), settings.subtitles, Settings.Subtitles)
        }
        item {
            ValueRow(stringResource(R.string.settings_subtitle_language), languageName(settings.subtitleLanguage)) { sheet = SettingsSheet.SubtitleLanguage }
        }
        item {
            ValueRow(stringResource(R.string.settings_subtitle_size), stringResource(SubtitleSizeNames[settings.subtitleSize])) { sheet = SettingsSheet.SubtitleSize }
        }
        item {
            SwitchRow(
                stringResource(R.string.settings_subtitle_background),
                stringResource(R.string.settings_subtitle_background_summary),
                settings.subtitleBackground,
                Settings.SubtitleBackground,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_appearance)) }
        item { ValueRow(stringResource(R.string.settings_font), stringResource(FontNames[settings.font.coerceIn(FontNames.indices)])) { sheet = SettingsSheet.Font } }
        item {
            ValueRow(stringResource(R.string.settings_text_size), stringResource(TextSizeNames[settings.textSize.coerceIn(TextSizeNames.indices)])) {
                sheet = SettingsSheet.TextSize
            }
        }
        item {
            SwitchRow(stringResource(R.string.settings_pure_black), stringResource(R.string.settings_pure_black_summary), settings.pureBlack, Settings.PureBlack)
        }
        item {
            SwitchRow(stringResource(R.string.settings_cover_accent), stringResource(R.string.settings_cover_accent_summary), settings.coverAccent, Settings.CoverAccent)
        }
        item {
            SwitchRow(stringResource(R.string.settings_ambient), stringResource(R.string.settings_ambient_summary), settings.ambientMode, Settings.AmbientMode)
        }
        item {
            SwitchRow(
                stringResource(R.string.settings_glass),
                stringResource(if (GlassSupported) R.string.settings_glass_summary else R.string.settings_glass_unsupported),
                settings.liquidGlass && GlassSupported,
                Settings.LiquidGlass,
                enabled = GlassSupported,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_network)) }
        item {
            ValueRow(
                stringResource(R.string.dpi_title),
                stringResource(if (settings.dpiBypass) R.string.settings_dpi_on else R.string.dpi_off),
                onClick = nav.openBypass,
            )
        }

        item { SectionHeader(stringResource(R.string.settings_extensions)) }
        item {
            SwitchRow(stringResource(R.string.settings_sponsorblock), stringResource(R.string.settings_sponsorblock_summary), settings.sponsorBlock, Settings.SponsorBlockOn)
        }
        if (settings.sponsorBlock) {
            item {
                val chosen = SponsorBlock.Categories.filter { it in settings.sponsorCategories }.map { stringResource(sponsorCategoryLabel(it)) }
                ValueRow(stringResource(R.string.settings_sponsorblock_categories), chosen.joinToString(", ").ifEmpty { stringResource(R.string.settings_none) }) {
                    sheet = SettingsSheet.SponsorCategories
                }
            }
        }
        item {
            SwitchRow(stringResource(R.string.settings_dislikes), stringResource(R.string.settings_dislikes_summary), settings.showDislikes, Settings.ShowDislikes)
        }
        item {
            SwitchRow(stringResource(R.string.settings_dearrow), stringResource(R.string.settings_dearrow_summary), settings.deArrow, Settings.DeArrowOn)
        }

        item { SectionHeader(stringResource(R.string.settings_recommendations)) }
        item {
            SwitchRow(stringResource(R.string.settings_hide_watched), stringResource(R.string.settings_hide_watched_summary), settings.hideWatched, Settings.HideWatched)
        }
        item {
            val summary = if (hidden.channels.isEmpty() && hidden.videos.isEmpty()) {
                stringResource(R.string.settings_hidden_none)
            } else {
                stringResource(R.string.settings_hidden_summary, hidden.channels.size, hidden.videos.size)
            }
            ValueRow(stringResource(R.string.settings_hidden), summary) { sheet = SettingsSheet.Hidden }
        }

        item { SectionHeader(stringResource(R.string.settings_history)) }
        item {
            SwitchRow(stringResource(R.string.settings_save_history), stringResource(R.string.settings_save_history_summary), settings.saveHistory, Settings.SaveHistory)
        }
        item {
            SwitchRow(stringResource(R.string.settings_report_watching), stringResource(R.string.settings_report_watching_summary), settings.reportWatching, Settings.ReportWatching)
        }
        item { ValueRow(stringResource(R.string.settings_clear_history), null) { clearOpen = true } }

        item { SectionHeader(stringResource(R.string.settings_backup)) }
        item { ValueRow(stringResource(R.string.settings_backup_export), stringResource(R.string.settings_backup_export_summary)) { exportFile.launch("wtube-backup.json") } }
        item { ValueRow(stringResource(R.string.settings_backup_import), stringResource(R.string.settings_backup_import_summary)) { importFile.launch(arrayOf("application/json", "text/*", "application/octet-stream")) } }

        item { SectionHeader(stringResource(R.string.settings_about)) }
        item { ValueRow(stringResource(R.string.settings_licenses), null) { licensesOpen = true } }
        item { ValueRow(stringResource(R.string.settings_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})", onClick = null) }
        item {
            SwitchRow(stringResource(R.string.settings_update_check), stringResource(R.string.settings_update_check_summary), settings.updateCheck, Settings.UpdateCheck)
        }
        availableUpdate?.let { found ->
            item {
                ValueRow(stringResource(R.string.update_title, found.version), stringResource(R.string.settings_update_available_summary)) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, found.url.toUri()))
                }
            }
        }
        item {
            ValueRow(stringResource(R.string.settings_update_now), null) {
                scope.launch {
                    val found = Updates.check(context, BuildConfig.VERSION_NAME, force = true)
                    if (found == null) Notices.show(R.string.settings_update_none)
                }
            }
        }
        item { ValueRow(stringResource(R.string.settings_extractor), BuildConfig.EXTRACTOR_VERSION, onClick = null) }
        item {
            ValueRow(stringResource(R.string.settings_share_log), stringResource(R.string.settings_share_log_summary)) {
                scope.launch { shareLog(context) }
            }
        }
    }

    val dismiss = { sheet = null }
    when (sheet) {
        SettingsSheet.Quality ->
            ChoiceSheet(stringResource(R.string.settings_quality), Settings.QualityChoices, settings.maxQuality, { qualityLabel(it) }, Settings.MaxQuality, dismiss)
        SettingsSheet.QualityMobile ->
            ChoiceSheet(stringResource(R.string.settings_quality_mobile), Settings.QualityChoices, settings.maxQualityMobile, { qualityLabel(it) }, Settings.MaxQualityMobile, dismiss)
        SettingsSheet.Speed -> ChoiceSheet(stringResource(R.string.settings_speed), SpeedChoices, settings.speed, { speedLabel(it) }, Settings.Speed, dismiss)
        SettingsSheet.DoubleTap -> ChoiceSheet(
            stringResource(R.string.settings_double_tap),
            Settings.DoubleTapChoices,
            settings.doubleTapSeconds,
            { stringResource(R.string.player_seek_amount, it) },
            Settings.DoubleTapSeconds,
            dismiss,
        )
        SettingsSheet.SubtitleLanguage -> ChoiceSheet(
            stringResource(R.string.settings_subtitle_language),
            (SubtitleLanguages + settings.subtitleLanguage).distinct(),
            settings.subtitleLanguage,
            { languageName(it) },
            Settings.SubtitleLanguage,
            dismiss,
        )
        SettingsSheet.SubtitleSize -> ChoiceSheet(
            stringResource(R.string.settings_subtitle_size),
            Settings.SubtitleScales.indices.toList(),
            settings.subtitleSize,
            { stringResource(SubtitleSizeNames[it]) },
            Settings.SubtitleSize,
            dismiss,
        )
        SettingsSheet.GestureSides -> ChoiceSheet(
            stringResource(R.string.settings_gesture_sides),
            listOf(false, true),
            settings.gestureSwapSides,
            { stringResource(gestureSidesName(it)) },
            Settings.GestureSwapSides,
            dismiss,
        )
        SettingsSheet.GestureZone -> ChoiceSheet(
            stringResource(R.string.settings_gesture_zone),
            Settings.GestureZones.indices.toList(),
            settings.gestureZone,
            { stringResource(GestureZoneNames[it]) },
            Settings.GestureZone,
            dismiss,
        )
        SettingsSheet.GestureSensitivity -> ChoiceSheet(
            stringResource(R.string.settings_gesture_sensitivity),
            Settings.GestureSpans.indices.toList(),
            settings.gestureSensitivity,
            { stringResource(GestureSensitivityNames[it]) },
            Settings.GestureSensitivity,
            dismiss,
        )
        SettingsSheet.SponsorCategories -> SponsorCategoriesSheet(settings.sponsorCategories, dismiss)
        SettingsSheet.Hidden -> HiddenSheet(hidden, dismiss)
        SettingsSheet.Font ->
            ChoiceSheet(stringResource(R.string.settings_font), FontNames.indices.toList(), settings.font, { stringResource(FontNames[it]) }, Settings.Font, dismiss)
        SettingsSheet.TextSize ->
            ChoiceSheet(stringResource(R.string.settings_text_size), TextSizeNames.indices.toList(), settings.textSize, { stringResource(TextSizeNames[it]) }, Settings.TextSize, dismiss)
        null -> Unit
    }
    if (licensesOpen) LicensesSheet { licensesOpen = false }
    if (clearOpen) {
        AlertDialog(
            onDismissRequest = { clearOpen = false },
            text = { Text(stringResource(R.string.settings_clear_history_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    History.clear()
                    SearchHistory.clear()
                    WatchStats.clear()
                    clearOpen = false
                }) { Text(stringResource(R.string.settings_clear)) }
            },
            dismissButton = { TextButton(onClick = { clearOpen = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

private suspend fun shareLog(context: Context) {
    val file = runCatching { ErrorLog.report(context) }.getOrElse {
        Notices.show(R.string.settings_share_log_failed)
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun qualityLabel(maxQuality: Int): String =
    if (maxQuality == 0) stringResource(R.string.settings_quality_auto) else stringResource(R.string.settings_quality_max, maxQuality)

private enum class SettingsSheet {
    Quality, QualityMobile, Speed, DoubleTap, GestureSides, GestureZone, GestureSensitivity, SubtitleLanguage, SubtitleSize, SponsorCategories, Font, TextSize, Hidden,
}

// In the order of the theme's font families and text scales.
private val FontNames = listOf(R.string.settings_font_inter, R.string.settings_font_system, R.string.settings_font_montserrat)
private val TextSizeNames = listOf(R.string.settings_text_small, R.string.settings_text_normal, R.string.settings_text_large)

// In the order of Settings.GestureZones and Settings.GestureSpans.
private val GestureZoneNames = listOf(R.string.settings_gesture_zone_narrow, R.string.settings_gesture_zone_medium, R.string.settings_gesture_zone_wide)
private val GestureSensitivityNames = listOf(
    R.string.settings_gesture_sensitivity_low,
    R.string.settings_gesture_sensitivity_normal,
    R.string.settings_gesture_sensitivity_high,
)

private fun gestureSidesName(swapped: Boolean) =
    if (swapped) R.string.settings_gesture_sides_swapped else R.string.settings_gesture_sides_default

private val SpeedChoices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SubtitleLanguages = listOf("ru", "en", "uk", "be", "kk", "de", "fr", "es", "it", "pt", "pl", "tr", "ja", "ko", "zh")
private val SubtitleSizeNames = listOf(
    R.string.settings_subtitle_size_small,
    R.string.settings_subtitle_size_normal,
    R.string.settings_subtitle_size_large,
    R.string.settings_subtitle_size_huge,
)

/** "Английский" for "en", in Russian whatever the phone's language. */
private fun languageName(tag: String): String {
    val russian = Locale.forLanguageTag("ru")
    return Locale.forLanguageTag(tag).getDisplayName(russian).replaceFirstChar { it.titlecase(russian) }
}

/** Which SponsorBlock categories to skip: a checklist that saves on every tap. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SponsorCategoriesSheet(selected: Set<String>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.settings_sponsorblock_categories),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SponsorBlock.Categories.forEach { category ->
                val checked = category in selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { Settings.set(Settings.SponsorCategories, if (checked) selected - category else selected + category) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                    Text(stringResource(sponsorCategoryLabel(category)).replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/** Hidden channels and "Not interested" videos, each with a way back into recommendations. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicensesSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember {
        listOf("Third-party.txt", "byedpi-MIT.txt", "Inter-OFL.txt", "Montserrat-OFL.txt").joinToString("\n\n----\n\n") { name ->
            runCatching { context.assets.open(name).bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxHeight(0.85f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.settings_licenses), style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp, bottom = 16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HiddenSheet(hidden: HiddenItems, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.settings_hidden), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.hidden_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (hidden.channels.isEmpty() && hidden.videos.isEmpty()) {
                item { Text(stringResource(R.string.settings_hidden_none), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp)) }
            }
            if (hidden.channels.isNotEmpty()) item { SectionHeader(stringResource(R.string.hidden_channels)) }
            items(hidden.channels, key = { "channel_" + it.url }) { channel ->
                HiddenRow(channel.name, onRestore = { Hidden.showChannel(channel.url) }) { Avatar(channel.avatar, 36.dp) }
            }
            if (hidden.videos.isNotEmpty()) item { SectionHeader(stringResource(R.string.hidden_videos)) }
            items(hidden.videos, key = { "video_" + it.id }) { video ->
                HiddenRow(video.title, onRestore = { Hidden.showVideo(video.id) }) {
                    Thumbnail(video.thumbnail, Modifier.width(64.dp).clip(RoundedCornerShape(6.dp)))
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.HiddenRow(title: String, onRestore: () -> Unit, leading: @Composable () -> Unit) {
    Row(
        Modifier.animateItem().fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        TextButton(onClick = onRestore) { Text(stringResource(R.string.hidden_restore), color = LocalContentColor.current) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceSheet(
    title: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    key: Preferences.Key<T>,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            choices.forEach { choice ->
                val isSelected = choice == selected
                Row(
                    Modifier.fillMaxWidth()
                        .clickable {
                            Settings.set(key, choice)
                            onDismiss()
                        }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label(choice),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) Icon(WIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SwitchRow(title: String, summary: String, checked: Boolean, key: Preferences.Key<Boolean>, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(enabled = enabled) { Settings.set(key, !checked) }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = MaterialTheme.typography.bodyMedium)
        }
        Switch(checked = checked, onCheckedChange = { Settings.set(key, it) }, enabled = enabled)
    }
}

@Composable
internal fun ValueRow(title: String, value: String?, onClick: (() -> Unit)?) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
