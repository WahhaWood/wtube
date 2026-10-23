package dev.wahha.wtube.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.dpi.Dpi
import dev.wahha.wtube.dpi.DpiMemory
import dev.wahha.wtube.dpi.DpiState
import dev.wahha.wtube.dpi.Presets
import dev.wahha.wtube.dpi.ProbeResult
import dev.wahha.wtube.dpi.Strategies
import dev.wahha.wtube.dpi.Strategy
import dev.wahha.wtube.dpi.parseProxy
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import dev.wahha.wtube.ui.icon.ArrowBack
import dev.wahha.wtube.ui.icon.Check
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.launch

private enum class BypassDialog { Args, Proxy }

/** Settings → Обход блокировок: the switch, what the bypass is doing, and ways to steer it. */
@Composable
fun BypassScreen() {
    val nav = LocalNavigator.current
    val settings by Settings.current.collectAsStateWithLifecycle()
    val state by Dpi.state.collectAsStateWithLifecycle()
    val memory by Dpi.memory.collectAsStateWithLifecycle()
    val presets by Dpi.presets.collectAsStateWithLifecycle()
    val network by Dpi.networkKey.collectAsStateWithLifecycle()
    var manualOpen by rememberSaveable { mutableStateOf(false) }
    var dialog by rememberSaveable { mutableStateOf<BypassDialog?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = systemTop(), bottom = systemBottom() + LocalBottomInset.current),
    ) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(WIcons.ArrowBack, contentDescription = stringResource(R.string.back)) }
                Text(stringResource(R.string.dpi_title), style = MaterialTheme.typography.headlineMedium)
            }
        }
        item { SwitchRow(stringResource(R.string.dpi_switch), stringResource(R.string.dpi_switch_summary), settings.dpiBypass, Settings.DpiBypass) }
        if (!settings.dpiBypass) return@LazyColumn

        item { ValueRow(stringResource(R.string.dpi_status), statusText(state, network), onClick = null) }
        val testing = state as? DpiState.Testing
        if (testing != null) {
            item {
                LinearProgressIndicator(
                    progress = { if (testing.total == 0) 0f else testing.done.toFloat() / testing.total },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            item { ValueRow(stringResource(R.string.dpi_cancel), null) { Dpi.cancelSearch() } }
        } else {
            item { ValueRow(stringResource(R.string.dpi_retest), stringResource(R.string.dpi_retest_summary), onClick = network?.let { { Dpi.retest() } }) }
        }
        item {
            ValueRow(stringResource(R.string.dpi_presets), stringResource(R.string.dpi_presets_summary, presets.version, presets.strategies.size)) {
                scope.launch {
                    Notices.show(
                        when (Dpi.refreshPresets()) {
                            is Presets.Refresh.Updated -> R.string.dpi_presets_updated
                            Presets.Refresh.Unchanged -> R.string.dpi_presets_latest
                            Presets.Refresh.Failed -> R.string.dpi_presets_failed
                        },
                    )
                }
            }
        }
        item {
            val saved = network?.let { memory.networks[it] }?.takeIf { it != DpiMemory.DIRECT }
            ValueRow(stringResource(R.string.dpi_manual), saved, onClick = network?.let { { manualOpen = true } })
        }

        item { SectionHeader(stringResource(R.string.dpi_advanced)) }
        item {
            ValueRow(stringResource(R.string.dpi_custom_args), memory.customArgs.ifEmpty { stringResource(R.string.dpi_custom_args_summary) }) {
                dialog = BypassDialog.Args
            }
        }
        item {
            ValueRow(stringResource(R.string.dpi_custom_proxy_title), memory.customProxy.ifEmpty { stringResource(R.string.dpi_custom_proxy_summary) }) {
                dialog = BypassDialog.Proxy
            }
        }
    }

    if (manualOpen) {
        ManualSheet(
            presets.strategies,
            memory.results,
            selected = network?.let { memory.networks[it] },
            onPick = { Dpi.choose(it); manualOpen = false },
            onDismiss = { manualOpen = false },
        )
    }
    when (dialog) {
        BypassDialog.Args -> EditDialog(
            title = stringResource(R.string.dpi_custom_args),
            initial = memory.customArgs,
            hint = stringResource(R.string.dpi_custom_args_hint),
            invalid = stringResource(R.string.dpi_custom_args_invalid),
            valid = { Strategies.validate(it) != null },
            onSave = { Dpi.setCustomArgs(it); dialog = null },
            onDismiss = { dialog = null },
        )
        BypassDialog.Proxy -> EditDialog(
            title = stringResource(R.string.dpi_custom_proxy_title),
            initial = memory.customProxy,
            hint = stringResource(R.string.dpi_custom_proxy_hint),
            invalid = stringResource(R.string.dpi_custom_proxy_invalid),
            valid = { parseProxy(it) != null },
            onSave = { Dpi.setCustomProxy(it); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@Composable
private fun statusText(state: DpiState, network: String?): String = when (state) {
    DpiState.Off -> stringResource(R.string.dpi_off)
    DpiState.Starting -> stringResource(if (network == null) R.string.dpi_no_network else R.string.dpi_starting)
    is DpiState.Testing -> stringResource(R.string.dpi_testing, state.done, state.total)
    is DpiState.Running ->
        if (state.strategy == Dpi.CUSTOM) stringResource(R.string.dpi_running_custom) else stringResource(R.string.dpi_running, state.strategy)
    DpiState.NotNeeded -> stringResource(R.string.dpi_not_needed)
    DpiState.CustomProxy -> stringResource(R.string.dpi_custom_proxy)
    DpiState.NoStrategy -> stringResource(R.string.dpi_no_strategy)
    DpiState.EngineFailed -> stringResource(R.string.dpi_engine_failed)
    DpiState.Cancelled -> stringResource(R.string.dpi_cancelled)
}

@Composable
private fun resultText(result: ProbeResult): String = when {
    result.fast -> stringResource(R.string.dpi_result_fast, result.kbps / 1000)
    result.works && result.kbps > 0 -> stringResource(R.string.dpi_result_slow, result.kbps)
    result.works -> stringResource(R.string.dpi_result_works)
    result.score > 0 -> {
        val parts = listOfNotNull(
            R.string.dpi_part_site.takeIf { result.site },
            R.string.dpi_part_images.takeIf { result.images },
            R.string.dpi_part_video.takeIf { result.video },
        ).map { stringResource(it) }
        stringResource(R.string.dpi_result_partly, parts.joinToString(", "))
    }
    else -> stringResource(R.string.dpi_result_failed)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualSheet(
    strategies: List<Strategy>,
    results: Map<String, ProbeResult>,
    selected: String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxHeight(0.85f), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.dpi_manual), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.dpi_manual_hint), style = MaterialTheme.typography.bodyMedium)
                }
            }
            // Strategies that worked last time first, fastest on top; the rest in list order.
            val ordered = strategies.sortedByDescending { results[it.id]?.let { r -> if (r.works) r.kbps + 1 else 0 } ?: 0 }
            items(ordered, key = { it.id }) { strategy ->
                val isSelected = strategy.id == selected
                Row(
                    Modifier.fillMaxWidth().clickable { onPick(strategy.id) }.padding(horizontal = 24.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            strategy.id + (results[strategy.id]?.let { " · " + resultText(it) } ?: ""),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(strategy.args, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                    if (isSelected) Icon(WIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EditDialog(
    title: String,
    initial: String,
    hint: String,
    invalid: String,
    valid: (String) -> Boolean,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val ok = text.isBlank() || valid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint) },
                isError = !ok,
                supportingText = if (ok) null else { { Text(invalid) } },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = ok) {
                Text(stringResource(if (text.isBlank()) R.string.dpi_clear else R.string.dpi_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
