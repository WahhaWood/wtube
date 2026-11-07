package dev.wahha.wtube.ui.channel

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import dev.wahha.wtube.data.VideoItem
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.LocalContentColor
import dev.wahha.wtube.data.MembersOnly
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.ui.components.openInYouTube
import dev.wahha.wtube.ui.icon.Star
import dev.wahha.wtube.ui.glass.CapsuleShape
import java.util.Date
import java.time.ZoneId
import java.text.DateFormat
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.Page
import kotlinx.coroutines.CancellationException
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.icon.OpenInNew
import dev.wahha.wtube.ui.icon.Search
import dev.wahha.wtube.ui.components.viewsText
import dev.wahha.wtube.ui.components.MessageState
import dev.wahha.wtube.data.innertube.Feeds
import dev.wahha.wtube.data.innertube.Channels
import dev.wahha.wtube.data.innertube.ChannelExtras
import dev.wahha.wtube.data.innertube.ChannelAbout
import dev.wahha.wtube.data.ItemsPage
import dev.wahha.wtube.data.AppLocale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.wahha.wtube.R
import dev.wahha.wtube.data.ChannelDetails
import dev.wahha.wtube.data.ChannelTab
import dev.wahha.wtube.data.Item
import dev.wahha.wtube.data.YouTube
import dev.wahha.wtube.data.innertube.Actions
import dev.wahha.wtube.ui.components.Avatar
import dev.wahha.wtube.ui.components.BackButton
import dev.wahha.wtube.ui.components.ErrorState
import dev.wahha.wtube.ui.components.FeedSkeleton
import dev.wahha.wtube.ui.components.ItemView
import dev.wahha.wtube.ui.components.ListFooter
import dev.wahha.wtube.ui.components.LoadMoreEffect
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.PagedList
import dev.wahha.wtube.ui.components.SubscribeButton
import dev.wahha.wtube.ui.components.VerifiedBadge
import dev.wahha.wtube.ui.components.launchAction
import dev.wahha.wtube.ui.components.subscribersText
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.components.systemTop
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs

/** What the channel page shows under its header: one of the extractor's tabs, the search, or About. */
enum class ChannelSection { TAB, MEMBERS, SEARCH, ABOUT }

class ChannelViewModel(private val url: String) : ViewModel() {
    var details by mutableStateOf<ChannelDetails?>(null)
        private set
    var error by mutableStateOf<Throwable?>(null)
        private set
    var tab by mutableStateOf<ChannelTab?>(null)
        private set
    var section by mutableStateOf(ChannelSection.TAB)
        private set

    /** Sort tokens of the Videos tab and the About token; null until loaded or when YouTube did not answer. */
    var extras by mutableStateOf<ChannelExtras?>(null)
        private set

    /** Index into [ChannelExtras.videoSorts]: 0 is Latest, the extractor's own list. */
    var videoSort by mutableIntStateOf(0)
        private set

    /** The query the search results belong to; null before the first search. */
    var searched by mutableStateOf<String?>(null)
        private set

    var about by mutableStateOf<ChannelAbout?>(null)
        private set
    var aboutError by mutableStateOf<Throwable?>(null)
        private set

    /** Whether the signed-in account follows the channel; null when signed out or unknown. */
    var subscribed by mutableStateOf<Boolean?>(null)
        private set
    private val lists = mutableMapOf<String, PagedList<Item>>()

    val list: PagedList<Item>?
        get() = when (section) {
            ChannelSection.ABOUT -> null
            ChannelSection.MEMBERS -> details?.id?.let { id ->
                paged("members") { page -> if (page == null) Channels.membersVideos(id) else Feeds.more(page) }
            }
            ChannelSection.SEARCH -> searched?.let { query ->
                val id = details?.id ?: return null
                paged("search:$query") { page -> if (page == null) Channels.search(id, query) else Feeds.more(page) }
            }
            ChannelSection.TAB -> tab?.let { t ->
                // Latest is the extractor's list; the others come from YouTube's chips.
                val token = extras?.videoSorts?.getOrNull(videoSort)?.token?.takeIf { t.kind == ChannelTabs.VIDEOS && videoSort > 0 }
                paged(if (token == null) t.kind else "${t.kind}#$videoSort") { page ->
                    when {
                        page != null && Feeds.isCursor(page) -> Feeds.more(page).ofThisChannel()
                        token != null -> Channels.sortedVideos(token).ofThisChannel()
                        else -> YouTube.channelTab(t, page)
                    }
                }
            }
        }

    // A chip's list leaves the channel's name out of its rows, and the row's first part, the views, would
    // be read as the name; the extractor fills it in only for its own lists.
    private fun ItemsPage.ofThisChannel(): ItemsPage {
        val channel = details ?: return this
        return ItemsPage(items.map { if (it is VideoItem) it.copy(channelName = channel.name, channelUrl = channel.url) else it }, next)
    }

    private fun paged(key: String, load: suspend (Page?) -> ItemsPage): PagedList<Item> =
        lists.getOrPut(key) { PagedList(viewModelScope, Item::url, load = load).also { it.refresh() } }

    init {
        load()
    }

    fun load() {
        error = null
        viewModelScope.launch {
            runCatching { YouTube.channel(url) }
                .onSuccess {
                    details = it
                    tab = it.tabs.firstOrNull()
                    launch { extras = runCatching { Channels.extras(it.id) }.getOrNull() }
                    subscribed = runCatching { Actions.subscribed(it.id) }.getOrNull()
                }
                .onFailure { error = it }
        }
    }

    fun subscribe(subscribe: Boolean) {
        val channel = details?.id ?: return
        val before = subscribed ?: return
        subscribed = subscribe
        launchAction(onFailure = { subscribed = before }) { Actions.subscribe(channel, subscribe) }
    }

    fun select(t: ChannelTab) {
        tab = t
        section = ChannelSection.TAB
    }

    fun sortVideos(index: Int) {
        videoSort = index
    }

    fun openMembers() {
        section = ChannelSection.MEMBERS
    }

    fun openSearch() {
        section = ChannelSection.SEARCH
    }

    fun search(query: String) {
        searched = query.trim().takeIf { it.isNotEmpty() } ?: return
    }

    fun openAbout() {
        section = ChannelSection.ABOUT
        if (about == null) loadAbout()
    }

    fun loadAbout() {
        val id = details?.id ?: return
        aboutError = null
        viewModelScope.launch {
            try {
                val token = (extras ?: Channels.extras(id).also { extras = it }).aboutToken ?: throw ContentNotAvailableException("No About panel")
                about = Channels.about(token) ?: throw ContentNotAvailableException("No About panel")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aboutError = e
            }
        }
    }
}

// By YouTube's English titles; one that is not here shows as YouTube wrote it.
private val VideoChipLabels = mapOf(
    "Latest" to R.string.channel_sort_latest,
    "Popular" to R.string.channel_sort_popular,
    "Oldest" to R.string.channel_sort_oldest,
    "Public" to R.string.channel_sort_public,
)

private val TabLabels = mapOf(
    ChannelTabs.VIDEOS to R.string.channel_tab_videos,
    ChannelTabs.LIVESTREAMS to R.string.channel_tab_livestreams,
    ChannelTabs.PLAYLISTS to R.string.channel_tab_playlists,
)

@Composable
fun ChannelScreen(url: String) {
    val vm: ChannelViewModel = viewModel(key = url) { ChannelViewModel(url) }
    val details = vm.details
    val list = vm.list
    val listState = rememberLazyListState()
    LoadMoreEffect(listState, loaded = list?.items?.size ?: 0) { list?.loadMore() }
    val settings by Settings.current.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        when {
            details == null && vm.error != null -> ErrorState(vm.error!!, vm::load, Modifier.align(Alignment.Center))
            details == null -> FeedSkeleton(Modifier.statusBarsPadding().padding(top = 64.dp))
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = systemBottom() + LocalBottomInset.current,
                ),
            ) {
                item(key = "header") { Header(details, vm.subscribed, vm::subscribe) }
                item(key = "tabs") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 8.dp),
                    ) {
                        items(details.tabs, key = { it.kind }) { t ->
                            FilterChip(
                                selected = vm.section == ChannelSection.TAB && vm.tab?.kind == t.kind,
                                onClick = { vm.select(t) },
                                label = { Text(stringResource(TabLabels.getValue(t.kind))) },
                            )
                        }
                        if (vm.extras?.joinable == true) item(key = "members") {
                            FilterChip(
                                selected = vm.section == ChannelSection.MEMBERS,
                                onClick = vm::openMembers,
                                label = { Text(stringResource(R.string.channel_tab_members)) },
                                leadingIcon = { Icon(WIcons.Star, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                        item(key = "search") {
                            FilterChip(
                                selected = vm.section == ChannelSection.SEARCH,
                                onClick = vm::openSearch,
                                label = { Text(stringResource(R.string.channel_search)) },
                                leadingIcon = { Icon(WIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            )
                        }
                        item(key = "about") {
                            FilterChip(
                                selected = vm.section == ChannelSection.ABOUT,
                                onClick = vm::openAbout,
                                label = { Text(stringResource(R.string.channel_tab_about)) },
                            )
                        }
                    }
                }
                val sorts = vm.extras?.videoSorts.orEmpty()
                if (vm.section == ChannelSection.TAB && vm.tab?.kind == ChannelTabs.VIDEOS && sorts.size > 1) {
                    item(key = "sorts") {
                        // Four chips with a channel's memberships: too wide for a phone in a large font.
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            sorts.forEachIndexed { index, chip ->
                                FilterChip(
                                    selected = vm.videoSort == index,
                                    onClick = { vm.sortVideos(index) },
                                    label = { Text(VideoChipLabels[chip.title]?.let { stringResource(it) } ?: chip.title) },
                                )
                            }
                        }
                    }
                }
                if (vm.section == ChannelSection.MEMBERS) {
                    item(key = "members_join") {
                        FilledTonalButton(
                            onClick = { openInYouTube(context, MembersOnly.joinUrl(details.url)) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(stringResource(R.string.members_only_join), color = LocalContentColor.current, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (list != null && list.items.isEmpty() && list.endReached && list.error == null) {
                        item(key = "members_empty") { MessageState(WIcons.Star, stringResource(R.string.channel_members_empty)) }
                    }
                }
                if (vm.section == ChannelSection.SEARCH) {
                    item(key = "search_field") { SearchBox(vm.searched.orEmpty(), vm::search) }
                    if (vm.searched == null) item(key = "search_hint") { MessageState(WIcons.Search, stringResource(R.string.channel_search_hint)) }
                    if (list != null && list.items.isEmpty() && !list.loading && list.error == null) {
                        item(key = "search_empty") { MessageState(WIcons.Search, stringResource(R.string.search_empty)) }
                    }
                }
                if (vm.section == ChannelSection.ABOUT) {
                    item(key = "about") {
                        val about = vm.about
                        when {
                            about != null -> About(about, details)
                            vm.aboutError != null -> ErrorState(vm.aboutError!!, vm::loadAbout)
                            else -> FeedSkeleton(compact = true)
                        }
                    }
                }
                if (list != null) {
                    if (list.initialLoading) item(key = "skeleton") { FeedSkeleton(compact = true) }
                    // The members tab is where the filter sends people for these videos.
                    val shown = if (vm.section == ChannelSection.MEMBERS) list.items else list.items.filter { MembersOnly.inFeeds(it, settings.membersOnly) }
                    items(shown, key = { it.url }) { ItemView(it, compact = true) }
                    item(key = "footer") { ListFooter(list.loading && !list.initialLoading, list.error, list::retry) }
                }
            }
        }
        BackButton(Modifier.statusBarsPadding().padding(8.dp))
    }
}

@Composable
private fun Header(details: ChannelDetails, subscribed: Boolean?, onSubscribe: (Boolean) -> Unit) {
    val top = systemTop()
    Column {
        if (details.banner != null) {
            AsyncImage(
                model = details.banner,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 5f).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        } else {
            Box(Modifier.padding(top = top + 56.dp))
        }
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(details.avatar, 72.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(details.name, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (details.verified) VerifiedBadge()
                }
                subscribersText(details.subscribers)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (details.description.isNotBlank()) {
            Text(
                details.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (subscribed != null) {
            SubscribeButton(subscribed, details.name, onSubscribe, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp))
        }
    }
}

@Composable
private fun SearchBox(initial: String, onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.channel_search_hint)) },
        leadingIcon = { Icon(WIcons.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch(text) }),
        shape = CapsuleShape,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The About panel: full description, numbers, where and since when, and the channel's links. */
@Composable
private fun About(about: ChannelAbout, details: ChannelDetails) {
    val uriHandler = LocalUriHandler.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val description = about.description.ifBlank { details.description }
        if (description.isNotBlank()) {
            SelectionContainer { Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface) }
        }
        val facts = listOfNotNull(
            subscribersText(details.subscribers),
            viewsText(about.views),
            about.videos.takeIf { it >= 0 }?.let { pluralStringResource(R.plurals.videos_count, it.toInt(), it.toString()) },
            about.joined?.let {
                stringResource(R.string.channel_joined, DateFormat.getDateInstance(DateFormat.MEDIUM, AppLocale).format(Date.from(it.atStartOfDay(ZoneId.systemDefault()).toInstant())))
            },
            about.country,
        )
        facts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (about.links.isNotEmpty()) {
            Text(stringResource(R.string.channel_links), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            about.links.forEach { link ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { runCatching { uriHandler.openUri(link.url) } }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(WIcons.OpenInNew, contentDescription = null, tint = colors.outline, modifier = Modifier.size(20.dp))
                    Column {
                        Text(link.title, style = MaterialTheme.typography.bodyMedium)
                        Text(link.url.removePrefix("https://"), style = MaterialTheme.typography.bodySmall, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

