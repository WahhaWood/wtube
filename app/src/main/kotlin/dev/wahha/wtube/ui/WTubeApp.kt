package dev.wahha.wtube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import dev.wahha.wtube.BuildConfig
import dev.wahha.wtube.R
import dev.wahha.wtube.data.LinkTarget
import dev.wahha.wtube.data.Settings
import dev.wahha.wtube.data.Updates
import dev.wahha.wtube.player.LocalPlayerConnection
import dev.wahha.wtube.player.LocalPlayerState
import dev.wahha.wtube.player.rememberPlayerState
import dev.wahha.wtube.ui.account.LoginScreen
import dev.wahha.wtube.ui.channel.ChannelScreen
import dev.wahha.wtube.ui.components.LocalBottomInset
import dev.wahha.wtube.ui.components.LocalNavigator
import dev.wahha.wtube.ui.components.LocalTabState
import dev.wahha.wtube.ui.components.Navigator
import dev.wahha.wtube.ui.components.Notices
import dev.wahha.wtube.ui.components.TAB_RESET
import dev.wahha.wtube.ui.components.systemBottom
import dev.wahha.wtube.ui.downloads.DownloadsScreen
import dev.wahha.wtube.ui.feed.FeedScreen
import dev.wahha.wtube.ui.glass.BarHeight
import dev.wahha.wtube.ui.glass.GlassSupported
import dev.wahha.wtube.ui.glass.GlassTab
import dev.wahha.wtube.ui.glass.LiquidGlassTabBar
import dev.wahha.wtube.ui.glass.LocalLiquidGlass
import dev.wahha.wtube.ui.glass.drawInteractiveGlass
import dev.wahha.wtube.ui.glass.liquidTabBarWidth
import dev.wahha.wtube.ui.glass.rememberBackdrop
import dev.wahha.wtube.ui.glass.rememberGlassInteraction
import dev.wahha.wtube.ui.glass.rememberGlassLuminance
import dev.wahha.wtube.ui.home.HomeScreen
import dev.wahha.wtube.ui.icon.Home
import dev.wahha.wtube.ui.icon.Search
import dev.wahha.wtube.ui.icon.Subscriptions
import dev.wahha.wtube.ui.icon.VideoLibrary
import dev.wahha.wtube.ui.icon.WIcons
import dev.wahha.wtube.ui.library.HistoryScreen
import dev.wahha.wtube.ui.library.StatsScreen
import dev.wahha.wtube.ui.settings.BypassScreen
import dev.wahha.wtube.ui.library.LibraryScreen
import dev.wahha.wtube.ui.playlist.PlaylistScreen
import dev.wahha.wtube.ui.search.SearchScreen
import dev.wahha.wtube.ui.settings.SettingsScreen
import dev.wahha.wtube.ui.subscriptions.SubscriptionsScreen
import dev.wahha.wtube.ui.theme.CoverAccent
import dev.wahha.wtube.ui.video.MiniPlayerHeight
import dev.wahha.wtube.ui.video.PlayerSheet
import dev.wahha.wtube.ui.video.PlayerSheetState
import dev.wahha.wtube.ui.video.VideoViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable object Home
@Serializable object Subscriptions
@Serializable object Search
@Serializable object Library
@Serializable data class Channel(val url: String)
@Serializable data class Playlist(val url: String)
// "Route" because the plain name is taken by data.Settings.
@Serializable object SettingsRoute
@Serializable object Login
@Serializable data class Feed(val browseId: String)
@Serializable object DownloadsRoute
@Serializable object HistoryRoute
@Serializable object StatsRoute
@Serializable object BypassRoute

// The bar's tabs; Search is its own glass button beside the capsule, as in SimpMusic.
private val TabRoutes = listOf(Home, Subscriptions, Library)

// Reached from the bar: these swap with a fade-through, every other page slides in over them.
private val TopLevelRoutes = listOf(Home::class, Subscriptions::class, Search::class, Library::class)

private val BarEdgePadding = 16.dp
private val SearchGap = 12.dp
private val SearchSize = 56.dp
private val BarBottomPadding = 8.dp
private val MiniPlayerGap = 8.dp

// Content scrolls under the floating bar; lists end this far above the navigation bar inset.
private val BottomBarSpace = BarHeight + 8.dp + 16.dp

// Material's fade-through between tabs; pages slide in on the emphasized curve.
private const val FADE_OUT_MS = 90
private const val FADE_IN_MS = 210
private const val SLIDE_MS = 350
private const val BAR_HIDE_MS = 250
// Pixels a list must move in one step to count as scrolling, so a resting finger does not flicker the bar.
private const val BAR_SCROLL_SLOP = 6f
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

// Going back is Material's shared axis X: both pages shift a little to the right while the old one fades out
// and the previous one fades in. It plays once the gesture is released, not along with it.
private const val AXIS_MS = 300
private val AxisShift = 30.dp

/** [deepLink] is where a shared or opened YouTube link points; [onDeepLinkHandled] clears it. */
@Composable
fun WTubeApp(deepLink: LinkTarget? = null, onDeepLinkHandled: () -> Unit = {}) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    // Read with the entry, so it is fresh whenever the back stack changes.
    val canGoBack = backStackEntry != null && navController.previousBackStackEntry != null
    val background = MaterialTheme.colorScheme.background
    val backdrop = rememberBackdrop(background)
    val connection = LocalPlayerConnection.current
    val player = connection.player
    val playerState = player?.let { rememberPlayerState(it) }
    val settings by Settings.current.collectAsStateWithLifecycle()
    val glass = GlassSupported && settings.liquidGlass
    // Only the capsule records into [glassLayer]; the search button and the mini player read the same sample.
    val glassLayer = rememberGraphicsLayer()
    val luminance = rememberGlassLuminance(glassLayer, enabled = glass)

    val video: VideoViewModel = viewModel()
    val scope = rememberCoroutineScope()
    Notices.Host()
    UpdateOffer()
    val sheet = remember { PlayerSheetState(scope) }

    val navigator = remember(navController) {
        // Opening another page tucks the video into the mini player first, as in YouTube.
        fun leaveVideo() {
            if (video.id != null) sheet.collapse()
        }
        Navigator(
            showVideo = { id, from ->
                if (from != null && !sheet.expanded) {
                    sheet.expandFrom(from)
                } else {
                    // Without a card to grow out of, a new sheet grows out of where the mini player sits.
                    if (video.id == null) sheet.snapCollapsed()
                    sheet.expand()
                }
                video.open(id)
            },
            openChannel = { url ->
                leaveVideo()
                navController.navigate(Channel(url))
            },
            openPlaylist = { url ->
                leaveVideo()
                navController.navigate(Playlist(url))
            },
            openSearch = {
                leaveVideo()
                navController.navigateToTab(Search)
            },
            openSettings = {
                leaveVideo()
                navController.navigate(SettingsRoute) { launchSingleTop = true }
            },
            openLogin = {
                leaveVideo()
                navController.navigate(Login) { launchSingleTop = true }
            },
            openFeed = { browseId ->
                leaveVideo()
                navController.navigate(Feed(browseId))
            },
            openDownloads = {
                leaveVideo()
                navController.navigate(DownloadsRoute)
            },
            openHistory = {
                leaveVideo()
                navController.navigate(HistoryRoute)
            },
            openStats = {
                leaveVideo()
                navController.navigate(StatsRoute)
            },
            openBypass = {
                leaveVideo()
                navController.navigate(BypassRoute) { launchSingleTop = true }
            },
            back = { navController.popBackStack() },
        )
    }
    // In the app's scope: a live page is looked up first, and clearing the link restarts this effect.
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            scope.launch { navigator.open(deepLink) }
            onDeepLinkHandled()
        }
    }

    // The sheet follows the player: it adopts a video still playing from before the activity was
    // recreated or switched to by autoplay, and the mini player goes away when playback is stopped
    // from the notification.
    val mediaId = playerState?.mediaId
    var lastMediaId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mediaId) {
        if (mediaId != null && mediaId != video.id) {
            val wasClosed = video.id == null
            video.open(mediaId, play = false)
            if (wasClosed) sheet.snapCollapsed()
        } else if (mediaId == null && lastMediaId != null && lastMediaId == video.id && !sheet.expanded) {
            video.close()
        }
        lastMediaId = mediaId
    }

    // A killed process takes the player and the view model along: the open video and the sheet's state
    // come back from the saved instance state, paused where the video was left.
    var savedVideo by rememberSaveable { mutableStateOf<String?>(null) }
    var savedExpanded by rememberSaveable { mutableStateOf(false) }
    val restored = remember { savedVideo to savedExpanded }
    LaunchedEffect(connection.player) {
        val (id, expanded) = restored
        if (id != null && video.id == null && connection.player != null && connection.player?.currentMediaItem == null) {
            connection.restoring = id
            video.open(id)
            if (expanded) sheet.expand() else sheet.snapCollapsed()
        }
    }
    LaunchedEffect(video.id, sheet.expanded) {
        savedVideo = video.id
        savedExpanded = sheet.expanded
    }

    val sheetOpen = video.id != null
    val loginHidden by animateFloatAsState(if (destination.isOn(Login::class)) 1f else 0f, tween(SLIDE_MS))
    // The bar steps aside while a list scrolls down and comes back on the way up or on a new page.
    val haptic = LocalHapticFeedback.current
    var barScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(destination) { barScrolledAway = false }
    val barScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed.y < -BAR_SCROLL_SLOP) barScrolledAway = true else if (consumed.y > BAR_SCROLL_SLOP) barScrolledAway = false
                return Offset.Zero
            }
        }
    }
    val barAway by animateFloatAsState(if (barScrolledAway) 1f else 0f, tween(BAR_HIDE_MS), label = "bar_away")
    val bottomInset = BottomBarSpace + if (sheetOpen) MiniPlayerHeight + MiniPlayerGap else 0.dp

    CompositionLocalProvider(
        LocalLiquidGlass provides glass,
        LocalNavigator provides navigator,
        LocalPlayerState provides playerState,
        LocalBottomInset provides bottomInset,
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().background(background)) {
            val capsuleBudget = maxWidth - BarEdgePadding * 2 - SearchGap - SearchSize
            val rowWidth = liquidTabBarWidth(TabRoutes.size, capsuleBudget) + SearchGap + SearchSize
            val barSpace = systemBottom() + BarBottomPadding + BarHeight

            val axisShift = with(LocalDensity.current) { AxisShift.roundToPx() }
            // The backdrop source; the bar and the sheet below are its siblings, never its children.
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop).nestedScroll(barScroll)) {
                NavHost(
                    navController = navController,
                    startDestination = Home,
                    // Landscape: a side navigation bar or the camera cutout must not cover the pages.
                    modifier = Modifier.windowInsetsPadding(
                        WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
                    ),
                    enterTransition = { if (betweenTopLevel()) fadeThroughIn() else pageSlideIn() },
                    exitTransition = { if (betweenTopLevel()) fadeThroughOut() else pageSlideOut() },
                    popEnterTransition = { if (betweenTopLevel()) fadeThroughIn() else pageBackIn(axisShift) },
                    popExitTransition = { if (betweenTopLevel()) fadeThroughOut() else pageBackOut(axisShift) },
                ) {
                    composable<Home> { entry -> TabPage(entry) { HomeScreen() } }
                    composable<Subscriptions> { entry -> TabPage(entry) { SubscriptionsScreen() } }
                    composable<Search> { entry -> TabPage(entry) { SearchScreen() } }
                    composable<Library> { entry -> TabPage(entry) { LibraryScreen() } }
                    composable<Channel> { entry -> Page { ChannelScreen(entry.toRoute<Channel>().url) } }
                    composable<Playlist> { entry -> Page { PlaylistScreen(entry.toRoute<Playlist>().url) } }
                    composable<SettingsRoute> { Page { SettingsScreen() } }
                    composable<Login> { Page { LoginScreen() } }
                    composable<Feed> { entry -> Page { FeedScreen(entry.toRoute<Feed>().browseId) } }
                    composable<DownloadsRoute> { Page { DownloadsScreen() } }
                    composable<HistoryRoute> { Page { HistoryScreen() } }
                    composable<StatsRoute> { Page { StatsScreen() } }
                    composable<BypassRoute> { Page { BypassScreen() } }
                }
                // Keeps the clock and icons readable over text scrolling beneath the transparent status bar.
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .windowInsetsTopHeight(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                        .background(Brush.verticalGradient(listOf(background.copy(alpha = 0.7f), background.copy(alpha = 0f)))),
                )
                // Registered after NavHost's own predictive handler, so it wins: pages go back on release, while
                // the expanded video sheet keeps following the gesture with its own handler.
                BackHandler(enabled = canGoBack && !sheet.expanded) {
                    navController.popBackStack()
                }
            }
            GlassBottomBar(
                backdrop = backdrop,
                layer = glassLayer,
                luminance = luminance,
                capsuleBudget = capsuleBudget,
                selectedTab = TabRoutes.indexOfFirst { destination.isOn(it::class) },
                searchSelected = destination.isOn(Search::class),
                onTabSelected = {
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    navController.selectTab(TabRoutes[it])
                },
                onSearch = { navController.selectTab(Search) },
                gapAbove = if (sheetOpen) MiniPlayerGap else 0.dp,
                modifier = Modifier.align(Alignment.BottomCenter).offset {
                    // Slides away under the video as the sheet opens, on the sheet's own progress, and
                    // off Google's sign-in page, whose buttons sit at the bottom.
                    val hidden = maxOf(if (video.id != null) sheet.coverage else 0f, loginHidden, barAway)
                    IntOffset(0, (hidden * (barSpace + 16.dp).toPx()).roundToInt())
                },
            )
            if (sheetOpen) {
                CoverAccent(video.details?.item?.thumbnail) {
                    PlayerSheet(
                        vm = video,
                        sheet = sheet,
                        miniWidth = rowWidth,
                        // With the bar away the mini player takes its place at the bottom.
                        miniBottom = lerp(barSpace, systemBottom() + BarBottomPadding, barAway) + MiniPlayerGap,
                        backdrop = backdrop,
                        luminance = { luminance.value },
                        onClose = {
                            connection.stop()
                            video.close()
                        },
                    )
                }
            }
        }
    }
}

private fun NavDestination?.isOn(route: kotlin.reflect.KClass<*>): Boolean = this?.hierarchy?.any { it.hasRoute(route) } == true

private fun NavController.navigateToTab(route: Any) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

// A tap on the open tab: a page opened inside it goes back to the tab first, the tab itself starts over.
private fun NavController.selectTab(route: Any) {
    // Tabs replace each other above Home, so at most one of them is on the back stack.
    val tab = listOf(Subscriptions, Search, Library).firstNotNullOfOrNull { runCatching { getBackStackEntry(it) }.getOrNull() }
        ?: getBackStackEntry(Home)
    when {
        !tab.destination.hasRoute(route::class) -> navigateToTab(route)
        tab != currentBackStackEntry -> popBackStack(tab.destination.id, inclusive = false)
        else -> tab.savedStateHandle[TAB_RESET] = true
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTopLevel(): Boolean =
    TopLevelRoutes.any { initialState.destination.hasRoute(it) } && TopLevelRoutes.any { targetState.destination.hasRoute(it) }

// Fade-through: the old tab is gone before the new one appears, so two half-transparent pages never overlap.
private fun fadeThroughIn(): EnterTransition =
    fadeIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(FADE_IN_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing), initialScale = 0.92f)

private fun fadeThroughOut(): ExitTransition = fadeOut(tween(FADE_OUT_MS, easing = FastOutLinearInEasing))

private fun pageSlideIn(): EnterTransition = slideInHorizontally(tween(SLIDE_MS, easing = EmphasizedDecelerate)) { it }

// The page underneath drifts a little the same way, dimmed, for depth.
private fun pageSlideOut(): ExitTransition =
    slideOutHorizontally(tween(SLIDE_MS, easing = EmphasizedDecelerate)) { -it / 4 } + fadeOut(tween(SLIDE_MS), targetAlpha = 0.6f)

// [shift] is [AxisShift] in px. The old page is gone before the previous one shows, as in fade-through.
private fun pageBackOut(shift: Int): ExitTransition =
    slideOutHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { shift } +
        fadeOut(tween(FADE_OUT_MS, easing = FastOutLinearInEasing))

private fun pageBackIn(shift: Int): EnterTransition =
    slideInHorizontally(tween(AXIS_MS, easing = FastOutSlowInEasing)) { -shift } +
        fadeIn(tween(AXIS_MS - FADE_OUT_MS, delayMillis = FADE_OUT_MS, easing = LinearOutSlowInEasing))

/** A destination's own opaque surface, so a page sliding over another hides it completely. */
@Composable
private fun Page(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
}

/** A tab's page; tapping its open tab again resets it through [LocalTabState]. */
@Composable
private fun TabPage(entry: NavBackStackEntry, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTabState provides entry.savedStateHandle) { Page(content) }
}

@Composable
private fun GlassBottomBar(
    backdrop: LayerBackdrop,
    layer: GraphicsLayer,
    luminance: State<Float>,
    capsuleBudget: Dp,
    selectedTab: Int,
    searchSelected: Boolean,
    onTabSelected: (Int) -> Unit,
    onSearch: () -> Unit,
    // The gap up to the mini player, which must not let taps through either.
    gapAbove: Dp,
    modifier: Modifier = Modifier,
) {
    val glass = LocalLiquidGlass.current
    val searchInteraction = rememberGlassInteraction()
    val colors = MaterialTheme.colorScheme
    val tabs = listOf(
        GlassTab(stringResource(R.string.tab_home), WIcons.Home),
        GlassTab(stringResource(R.string.tab_subscriptions), WIcons.Subscriptions),
        GlassTab(stringResource(R.string.tab_library), WIcons.VideoLibrary),
    )

    Row(
        modifier
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = BarBottomPadding)
            // Swallow taps in the gaps between the glass pieces, so they never reach the list below.
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(top = gapAbove),
        horizontalArrangement = Arrangement.spacedBy(SearchGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidGlassTabBar(
            tabs = tabs,
            selectedTab = selectedTab,
            backdrop = backdrop,
            layer = layer,
            luminance = luminance,
            onTabSelected = onTabSelected,
            availableWidth = capsuleBudget,
        )
        Box(
            Modifier
                .size(SearchSize)
                .drawInteractiveGlass(
                    backdrop = backdrop,
                    layer = null,
                    luminance = { luminance.value },
                    shape = CircleShape,
                    interaction = searchInteraction,
                    fallbackColor = colors.surfaceContainerHigh,
                    enabled = glass,
                )
                .clickable(interactionSource = null, indication = null, onClick = onSearch)
                .testTag("tab_search"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                WIcons.Search,
                contentDescription = stringResource(R.string.tab_search),
                tint = if (searchSelected) colors.primary else colors.onSurface,
            )
        }
    }
}

/** Offers a newer release in a dialog on every launch: the one found earlier at once, then a fresh check (at most hourly). */
@Composable
private fun UpdateOffer() {
    val context = LocalContext.current
    val settings by Settings.current.collectAsStateWithLifecycle()
    val release by Updates.available.collectAsStateWithLifecycle()
    LaunchedEffect(settings.updateCheck) {
        if (!settings.updateCheck) return@LaunchedEffect
        Updates.restore(context, BuildConfig.VERSION_NAME)
        Updates.check(context, BuildConfig.VERSION_NAME)
    }
    release?.let { found ->
        AlertDialog(
            onDismissRequest = { Updates.dismiss() },
            title = { Text(stringResource(R.string.update_title, found.version)) },
            text = { if (found.notes.isNotEmpty()) Text(found.notes.take(600)) },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, found.url.toUri()))
                    Updates.dismiss()
                }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = { TextButton(onClick = { Updates.dismiss() }) { Text(stringResource(R.string.update_later)) } },
        )
    }
}
