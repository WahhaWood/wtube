package dev.wahha.wtube.ui.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.wahha.wtube.R
import dev.wahha.wtube.data.Hidden
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.SearchHistory
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.TabResetEffect
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.glass.CapsuleShape
import dev.wahha.wtube.ui.icon.Close
import dev.wahha.wtube.ui.icon.History
import dev.wahha.wtube.ui.icon.Mic
import dev.wahha.wtube.ui.icon.NorthWest
import dev.wahha.wtube.ui.icon.Search
import dev.wahha.wtube.ui.icon.WIcons
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    var query by mutableStateOf("")
        private set
    /** The query the results belong to; null before the first search. */
    var submitted by mutableStateOf<String?>(null)
        private set
    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    val results = PagedList(viewModelScope, Item::url) { page -> YouTube.search(submitted.orEmpty(), page = page) }
    private var suggestJob: Job? = null

    fun onQueryChange(text: String) {
        query = text
        suggestJob?.cancel()
        if (text.isBlank()) {
            suggestions = emptyList()
            return
        }
        suggestJob = viewModelScope.launch {
            delay(250)
            suggestions = runCatching { YouTube.suggestions(text) }.getOrDefault(suggestions)
        }
    }

    fun submit(text: String = query) {
        val q = text.trim()
        if (q.isEmpty()) return
        query = q
        suggestJob?.cancel()
        submitted = q
        SearchHistory.record(q)
        results.refresh()
    }

    /** Empties the field after a pasted link was opened instead of searched. */
    fun reset() {
        suggestJob?.cancel()
        query = ""
        suggestions = emptyList()
    }

    /** Back to an empty search, as when the tab is first opened. */
    fun clear() {
        reset()
        submitted = null
    }
}

@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val nav = LocalNavigator.current
    val context = LocalContext.current
    val past by SearchHistory.queries.collectAsStateWithLifecycle()
    // Typing shows suggestions; submitting (or leaving the field) shows results.
    val showSuggestions = focused && (vm.submitted == null || vm.query != vm.submitted)
    LaunchedEffect(Unit) { if (vm.submitted == null) focus.requestFocus() }
    TabResetEffect {
        vm.clear()
        focus.requestFocus()
        keyboard?.show()
    }
    val leaveField = {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // A pasted YouTube link opens what it points to instead of searching for it.
    fun openLink(text: String): Boolean {
        val target = YouTube.linkTarget(text) ?: return false
        vm.reset()
        leaveField()
        vm.viewModelScope.launch { nav.open(target) }
        return true
    }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == Activity.RESULT_OK && !text.isNullOrBlank()) {
            vm.submit(text)
            leaveField()
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchField(
            value = vm.query,
            onValueChange = { if (!openLink(it)) vm.onQueryChange(it) },
            onSearch = {
                if (!openLink(vm.query)) vm.submit()
                leaveField()
            },
            onVoice = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                try {
                    voice.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.search_voice_missing, Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused },
        )
        if (showSuggestions) {
            // Past queries first, then YouTube's suggestions without repeating them.
            val history = remember(past, vm.query) { SearchHistory.matching(vm.query).take(if (vm.query.isBlank()) 50 else 3) }
            val remote = if (vm.query.isBlank()) emptyList() else vm.suggestions.filterNot { s -> history.any { it.equals(s, ignoreCase = true) } }
            // Scrolling suggestions only lowers the keyboard: losing focus would swap them for results.
            Suggestions(history, remote, onPick = {
                vm.submit(it)
                leaveField()
            }, onFill = { vm.onQueryChange("$it ") }, onRemove = SearchHistory::remove, onScroll = { keyboard?.hide() })
        } else {
            Results(vm, onScroll = leaveField)
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit, onVoice: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(52.dp)
            .background(colors.surfaceContainerHigh, CapsuleShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(WIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (value.isEmpty()) {
                Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyLarge, color = colors.outline)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = modifier.fillMaxWidth().testTag("search_field"),
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(WIcons.Close, contentDescription = stringResource(R.string.search_clear))
            }
        } else {
            IconButton(onClick = onVoice) {
                Icon(WIcons.Mic, contentDescription = stringResource(R.string.search_voice), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Suggestions(
    history: List<String>,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    onRemove: (String) -> Unit,
    onScroll: () -> Unit,
) {
    val listState = rememberLazyListState()
    OnScrollEffect(listState, onScroll)
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        state = listState,
        contentPadding = PaddingValues(
            bottom = systemBottom() + LocalBottomInset.current,
        ),
    ) {
        items(history, key = { "history_$it" }) { text ->
            Box(Modifier.animateItem()) {
                SuggestionRow(text, WIcons.History, onPick, onFill) {
                    IconButton(onClick = { onRemove(text) }) {
                        Icon(WIcons.Close, contentDescription = stringResource(R.string.search_history_remove), tint = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
        items(suggestions, key = { "suggestion_$it" }) { text -> SuggestionRow(text, WIcons.Search, onPick, onFill) }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    icon: ImageVector,
    onPick: (String) -> Unit,
    onFill: (String) -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(text) }.padding(start = 20.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.outline)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
        )
        trailing()
        IconButton(onClick = { onFill(text) }) {
            Icon(WIcons.NorthWest, contentDescription = stringResource(R.string.search_fill), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun Results(vm: SearchViewModel, onScroll: () -> Unit) {
    val results = vm.results
    val hidden by Hidden.items.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LoadMoreEffect(listState) { results.loadMore() }
    OnScrollEffect(listState, onScroll)
    LaunchedEffect(vm.submitted) { listState.scrollToItem(0) }

    val bottom = systemBottom() + LocalBottomInset.current
    when {
        vm.submitted == null -> MessageState(WIcons.Search, stringResource(R.string.search_start))
        results.initialLoading -> FeedSkeleton(Modifier.padding(top = 8.dp))
        results.items.isEmpty() && results.error != null -> ErrorState(results.error!!, results::retry)
        results.items.isEmpty() && !results.loading -> MessageState(WIcons.Search, stringResource(R.string.search_empty))
        else -> LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("search_results"),
            contentPadding = PaddingValues(top = 8.dp, bottom = bottom),
        ) {
            items(results.items.filter(hidden::allows), key = { it.url }) { ItemView(it, recommended = true) }
            item(key = "footer") { ListFooter(results.loading, results.error, results::retry) }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

/** Calls [onScroll] whenever the user starts scrolling [listState]. */
@Composable
private fun OnScrollEffect(listState: LazyListState, onScroll: () -> Unit) {
    val current by rememberUpdatedState(onScroll)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { current() }
    }
}
