package cloud.nalet.chino.tv.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as tvRowItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.R
import cloud.nalet.chino.tv.ui.theme.LogoMark
import com.composables.icons.lucide.Bell
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Film
import com.composables.icons.lucide.House
import com.composables.icons.lucide.Info
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Tv
import com.composables.icons.lucide.User
import com.composables.icons.lucide.X
import com.composables.icons.lucide.Zap
import cloud.nalet.chino.tv.data.model.Item
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onItemSelected: (String) -> Unit = {},
    onHeroPlay: (String) -> Unit = onItemSelected,
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onZap: () -> Unit = {},
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val homeEntryNonce by viewModel.homeEntryNonce.collectAsState()
    // Refresh the continue-watching shelf whenever the screen comes back to
    // the foreground — covers "user finished an episode and pressed BACK"
    // (NavBackStackEntry transitions library back to RESUMED) and "user
    // pressed HOME mid-watch and reopened the app" (activity ON_RESUME). The
    // first event is skipped because init() already kicked off a full refresh
    // when the VM was constructed; doing it again would be a redundant HTTP.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        var skipFirst = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (skipFirst) skipFirst = false else viewModel.refreshContinueWatching()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            LibraryUiState.Loading -> CenterText(stringResource(R.string.library_loading))
            is LibraryUiState.Error -> CenterText(
                stringResource(R.string.library_error_prefix) + s.message,
                isError = true,
            )
            is LibraryUiState.Ready -> {
                // Even when both rows are empty (filter zeroed the list) we
                // still want to render the chip row so the user can change or
                // clear the filter — replacing the screen with "no items" stops
                // them from recovering without app restart.
                LibraryContent(
                    movies = s.movies,
                    series = s.series,
                    continueWatching = s.continueWatching,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
                    moviesHasMore = s.moviesHasMore,
                    seriesHasMore = s.seriesHasMore,
                    genres = s.genres,
                    filter = s.filter,
                    heroItem = s.heroPool.getOrNull(s.heroIndex) ?: s.movies.firstOrNull() ?: s.series.firstOrNull(),
                    heroIndex = s.heroIndex,
                    heroCount = s.heroPool.size,
                    topRated = s.topRated,
                    onItemSelected = onItemSelected,
                    onHeroPlay = onHeroPlay,
                    onSearch = onSearch,
                    onSettings = onSettings,
                    onWatchlist = onWatchlist,
                    onAccountClick = onAccountClick,
                    onMoviesNav = onMoviesNav,
                    onSeriesNav = onSeriesNav,
                    activeAccount = activeAccount,
                    onLoadMoreMovies = viewModel::loadMoreMovies,
                    onLoadMoreSeries = viewModel::loadMoreSeries,
                    onFilterChange = viewModel::applyFilter,
                    onHeroFocusChanged = viewModel::setHeroRotationPaused,
                    homeEntryNonce = homeEntryNonce,
                    onRailHome = viewModel::onRailHome,
                    onZap = onZap,
                    onRemoveFromContinueWatching = viewModel::removeFromContinueWatching,
                    onToggleWatched = viewModel::toggleWatched,
                )
            }
        }
    }
}

@Composable
private fun LibraryContent(
    movies: List<Item>,
    series: List<Item>,
    continueWatching: List<cloud.nalet.chino.tv.data.api.ContinueWatchingItem>,
    baseUrl: String,
    streamToken: String,
    moviesHasMore: Boolean,
    seriesHasMore: Boolean,
    genres: List<String>,
    filter: BrowseFilter,
    heroItem: Item?,
    heroIndex: Int = 0,
    heroCount: Int = 0,
    topRated: List<Item> = emptyList(),
    onItemSelected: (String) -> Unit,
    onHeroPlay: (String) -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onWatchlist: () -> Unit,
    onAccountClick: () -> Unit,
    onMoviesNav: () -> Unit,
    onSeriesNav: () -> Unit,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account?,
    onLoadMoreMovies: () -> Unit,
    onLoadMoreSeries: () -> Unit,
    onFilterChange: (BrowseFilter) -> Unit,
    onHeroFocusChanged: (Boolean) -> Unit = {},
    homeEntryNonce: Int = 0,
    onRailHome: () -> Unit = {},
    onZap: () -> Unit = {},
    onRemoveFromContinueWatching: (String) -> Unit = {},
    // Card-action watched toggle. `markWatched` is the NEXT desired state.
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
) {
    val featured = heroItem
    // Hold-OK on any card opens a single shared actions menu hosted here at the
    // top of the content (so its scrim covers the whole page). Cards request it
    // via onShowCardMenu; null = closed.
    var activeCardMenu by remember { mutableStateOf<CardMenuRequest?>(null) }
    val showCardMenu: (CardMenuRequest) -> Unit = { activeCardMenu = it }
    // Park focus on the hero Play button whenever the home (re)enters
    // composition. NavHost drops the LIBRARY destination off-screen while the
    // player/detail is up, so popping BACK re-composes this content and the
    // effect re-fires — landing the "camera" back on top of the main page
    // (the hero) instead of stranded on the rail or wherever focus last sat.
    // Same reset web/mobile do when you return to home. requestFocus is
    // wrapped because the hero is null while a type-filter zeroes the rows.
    val heroPlayFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val pageScroll = rememberScrollState()
    // Invisible 1dp focus "catcher" that HOLDS focus on every home entry so the
    // screen reads as "nothing selected" (it paints no ring) yet still receives
    // the first D-pad key (onPreviewKeyEvent only fires for the focused node;
    // with literally nothing focused the key would go to the host View instead).
    // Because the catcher — not the hero — holds focus, HeroBanner.hasFocus stays
    // false, so heroRotationPaused stays false and the 12s carousel keeps running.
    // engaged is keyed on homeEntryNonce so EVERY home (re)entry — login,
    // BACK-from-player, and the in-place rail-Home nonce bump — re-arms it.
    val catcherFocus = remember { FocusRequester() }
    var engaged by remember(homeEntryNonce) { mutableStateOf(false) }
    // Whenever the hero gains focus — on home entry AND when the user presses
    // UP from a shelf back into it — snap the page to the TOP so the WHOLE
    // hero (title and all) shows, not just the bottom-left Play button that
    // the focus-driven bringIntoView would otherwise reveal. animateScrollTo
    // wins the scroll mutex over that bringIntoView (it's launched a frame
    // later), so there's no slide-then-snap fight.
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(heroFocused) {
        if (heroFocused) runCatching { pageScroll.animateScrollTo(0) }
    }
    // Park focus on the INVISIBLE CATCHER (not the hero Play) on EVERY home
    // entry. This is the core of "nothing selected on entry + carousel keeps
    // running": parking on Play would set hero.hasFocus=true and FREEZE the
    // carousel immediately, which is exactly what the new requirement forbids.
    // Keyed on homeEntryNonce so it re-fires for the in-place rail-Home path
    // (which never recomposes); the navigation/back paths compose fresh and run
    // it once anyway. NOT keyed on the hero id, so a 12s carousel rotation can't
    // yank focus while the catcher holds it. The first directional/center key
    // (handled in the catcher's onPreviewKeyEvent below) hands focus off to
    // hero Play; align-to-top is then driven by the heroFocused effect above.
    LaunchedEffect(homeEntryNonce) {
        runCatching { catcherFocus.requestFocus() }
    }
    // Rail-and-topbar layout matching chino-web's Header + Sidebar + the
    // chino-mobile tablet form factor. SideRail pinned to the left, TopBar
    // above the scroll region with the inline search bar; everything else
    // (hero + filter chips + shelves) stacks vertically inside the
    // scrollable Column on the right.
    // Outer Box hosts the card-actions menu overlay on top of the whole page.
    Box(modifier = Modifier.fillMaxSize()) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxSize()) {
        TvSideRail(
            // On the library Home is always active; the Film/Tv buttons now
            // navigate to the dedicated Movies/Shows browse pages.
            activeType = null,
            // Rail Home while already on the library: re-park focus on the
            // hero Play (bump the nonce) AND clear any active filter. Plain
            // onFilterChange wouldn't re-fire the focus effect.
            onHome = onRailHome,
            onMovies = onMoviesNav,
            onSeries = onSeriesNav,
            onWatchlist = onWatchlist,
            onSettings = onSettings,
            onZap = onZap,
        )
        Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
            TvTopBar(
                onSearch = onSearch,
                onWatchlist = onWatchlist,
                activeAccount = activeAccount,
                onAccountClick = onAccountClick,
                searchFocusRequester = searchFocus,
                searchDownTarget = heroPlayFocus,
            )
            // Outer column scrolls so DPAD_DOWN can walk past the visible
            // portion. The rail + TopBar stay fixed; only the
            // hero / chips / shelves move under the camera.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScroll),
            ) {
        // Invisible 1dp focus catcher — the FIRST child of the scroll column,
        // sitting ABOVE the Crossfade so a carousel rotation never destroys the
        // focused node. It paints nothing (plain Box, NOT a tv Button), so on
        // entry the screen looks unselected while it silently holds focus.
        //   - canFocus = !engaged + focusable(enabled = !engaged): the instant
        //     it engages it leaves the spatial-nav graph for the rest of this
        //     home visit, so Play DOWN skips past it to Continue-Watching and
        //     LEFT falls through to the rail. It re-arms only when the
        //     homeEntryNonce-keyed `engaged` resets on the next home entry.
        //   - onPreviewKeyEvent (not onKeyEvent): decide engagement on the
        //     dispatch-DOWN pass, BEFORE the focusable's built-in spatial nav
        //     could fling focus to the rail (LEFT) or nowhere (UP).
        //   - It declares no left/up/down targets, so it never competes with
        //     the existing rail/search/Play edges.
        Box(
            modifier = Modifier
                .size(1.dp)
                .focusRequester(catcherFocus)
                .focusProperties { canFocus = !engaged }
                .onPreviewKeyEvent { e ->
                    if (engaged || e.type != KeyEventType.KeyDown) {
                        false
                    } else when (e.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_UP,
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        -> {
                            // Hand off to hero Play. Consume ONLY if focus
                            // actually moved, else a single press would both
                            // engage AND spatial-navigate off the 1dp catcher.
                            // When the hero is null (filter zeroed both rows)
                            // requestFocus throws -> stay un-engaged & un-
                            // consumed so the user can still DPAD to the rail.
                            val moved = runCatching { heroPlayFocus.requestFocus() }.isSuccess
                            engaged = moved
                            moved
                        }
                        else -> false
                    }
                }
                .focusable(enabled = !engaged),
        )
        // Crossfade so hero swaps every 20s glide between backdrops instead of
        // hard-cutting — feels much less jarring when you're not actively
        // looking at the screen.
        androidx.compose.animation.Crossfade(
            targetState = featured,
            animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
            label = "hero-crossfade",
        ) { item ->
            if (item != null) {
                HeroBanner(
                    item = item,
                    backdropUrl = "$baseUrl/v1/items/${item.id}/backdrop?stream=$streamToken",
                    heroIndex = heroIndex,
                    heroCount = heroCount,
                    playFocusRequester = heroPlayFocus,
                    upTarget = searchFocus,
                    onFocusWithin = { f -> heroFocused = f; onHeroFocusChanged(f) },
                    onPlay = { onHeroPlay(item.id) },
                    onMoreInfo = { onItemSelected(item.id) },
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
            // No filter-chip row on Home — chino-web's home is hero + shelves
            // only; Movies/Series filtering lives on the dedicated browse
            // pages (rail Film/Tv). Keeps the home identical to web.
            // Split chino-api's continue-watching feed into two shelves —
            // mirrors web's HomeSection (31b756d). "Continue watching" gets
            // rows where the user is mid-progress; "Next Up" gets rows where
            // chino-api substituted the next episode (server stamps
            // up_next: true; no saved position).
            val cwRows = continueWatching.filterNot { it.upNext }
            val nextUpRows = continueWatching.filter { it.upNext }
            if (cwRows.isNotEmpty()) {
                Text(
                    text = "Continue Watching",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ContinueWatchingRow(
                    items = cwRows,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    showProgress = true,
                    onItemSelected = onItemSelected,
                    // Only the in-progress shelf gets the dismiss action — Next
                    // Up rows are server-substituted and have nothing to remove.
                    onRemove = onRemoveFromContinueWatching,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (nextUpRows.isNotEmpty()) {
                Text(
                    text = "Next Up",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ContinueWatchingRow(
                    items = nextUpRows,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    showProgress = false,
                    onItemSelected = onItemSelected,
                    // Next Up rows carry no dismiss, but they DO get the watched
                    // toggle so the menu is present on every card surface.
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            // Two stacked shelves matching chino-web's HomeSection — one for
            // movies, one for series. When the user picks a Type filter chip,
            // the off-axis shelf goes empty + collapses. Both rows scroll
            // horizontally; paging is per-shelf.
            // Home rails are capped at ~20 with a "See All" tile that jumps to
            // the full Movies/Series overview (#150 web parity). No inline
            // paging — the shelf no longer grows endlessly as the user
            // D-pads right; paging-to-full lives on the BrowseScreen.
            if (movies.isNotEmpty()) {
                Text(
                    text = "Recently added — Movies",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ItemGrid(
                    items = movies,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    onItemSelected = onItemSelected,
                    onSeeAll = onMoviesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (series.isNotEmpty()) {
                Text(
                    text = "Recently added — Shows",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ItemGrid(
                    items = series,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    onItemSelected = onItemSelected,
                    onSeeAll = onSeriesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (topRated.isNotEmpty()) {
                Text(
                    text = "Top Rated",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ItemGrid(
                    items = topRated,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    onItemSelected = onItemSelected,
                    // Top Rated is a movies-only shelf — See All goes to Movies.
                    onSeeAll = onMoviesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                )
            }
            if (movies.isEmpty() && series.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (filter.isActive) "No items match the current filter." else stringResource(R.string.library_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
            }
        }
        }
        // Card-actions overlay — a single instance hosted above the whole page
        // so its scrim dims the rail + topbar + shelves. Opened by hold-OK on
        // any card (Home rails or CW shelf); BACK / scrim tap closes it.
        activeCardMenu?.let { req ->
            CardActionsMenu(
                title = req.title,
                actions = req.actions,
                onDismiss = { activeCardMenu = null },
            )
        }
    }
}

/** What a card hands [LibraryContent] to open the shared actions menu. */
internal data class CardMenuRequest(val title: String, val actions: List<CardAction>)

/* ────────────────────────────  SideRail + TopBar  ────────────────────────── */

/** Vertical nav rail — `>c` brand mark up top, House / Film / Tv stacked
 *  in the middle, Settings pinned at bottom. Mirrors chino-mobile's
 *  SideRail composable so the two clients have visual parity on the same
 *  form factor. Rail items use tv-material3 Button so DPAD focus picks up
 *  the scale + outline focused state for free. */
@Composable
internal fun TvSideRail(
    activeType: String?,
    onHome: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onSettings: () -> Unit,
    settingsActive: Boolean = false,
    onZap: () -> Unit = {},
    zapActive: Boolean = false,
    onWatchlist: () -> Unit = {},
    watchlistActive: Boolean = false,
) {
    // Flat canvas rail (#0D1117) with a 1dp #30363D right-edge divider and a
    // logo-cell bottom divider — the chino-web / mobile SideRail (NOT an
    // elevated #161B22 surface). 80dp wide incl. the divider.
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Color(0xFF0D1117)),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Logo cell: 64dp tall (= topbar height) with a bottom divider so
            // it meets the topbar's bottom divider at the L corner.
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    LogoMark(sizeDp = 36)
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
            }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvRailButton(icon = Lucide.House, label = "Home", isActive = activeType == null, onClick = onHome)
                TvRailButton(icon = Lucide.Film, label = "Movies", isActive = activeType == "movie", onClick = onMovies)
                TvRailButton(icon = Lucide.Tv, label = "Series", isActive = activeType == "series", onClick = onSeries)
                // Watchlist hub — primary entry (web/mobile parity); the
                // top-bar bell stays as a secondary entry to the same route.
                TvRailButton(icon = Lucide.Bookmark, label = "Watchlist", isActive = watchlistActive, onClick = onWatchlist)
                TvRailButton(icon = Lucide.Zap, label = "Zap", isActive = zapActive, onClick = onZap)
            }
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                TvRailButton(icon = Lucide.Settings, label = "Settings", isActive = settingsActive, onClick = onSettings)
            }
        }
        // Right-edge divider — full height, forms the L with the topbar divider.
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(Color(0xFF30363D)))
    }
}

@Composable
private fun TvRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    // 48dp rounded-square (matches mobile RailButton): active slot #161B22 so
    // it stands out against the #0D1117 rail; DPAD focus bumps to #21262D + a
    // blue ring. Icon blue when active/focused, else #8B949E.
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    focused -> Color(0xFF21262D)
                    isActive -> Color(0xFF161B22)
                    else -> Color.Transparent
                },
            )
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(8.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (isActive || focused) Color(0xFF58A6FF) else Color(0xFF8B949E),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Top bar with inline search field + watchlist bell + account avatar.
 *  Visual parity with chino-web's Header (the bar above the catalogue):
 *  wide cosmetic search input on the left taking all remaining width, then
 *  a Lucide.Bell icon cell for the watchlist, then the user's avatar on
 *  the far right. CENTER on the search field pushes SearchScreen rather
 *  than opening an inline dropdown — DPAD typing through a soft keyboard
 *  is impractical inside a 56dp pill, and the dedicated screen gives the
 *  keyboard the room it needs.
 */
@Composable
internal fun TvTopBar(
    onSearch: () -> Unit = {},
    onWatchlist: () -> Unit,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account?,
    onAccountClick: () -> Unit,
    searchFocusRequester: FocusRequester? = null,
    searchDownTarget: FocusRequester? = null,
    // When provided, this replaces the cosmetic search bar with a live element
    // (the Search screen passes its real text field here, web-parity: the
    // search input lives in the header).
    searchSlot: (@Composable () -> Unit)? = null,
) {
    // Flat canvas top bar (#0D1117) with a 1dp #30363D bottom divider —
    // matches the mobile/web Header (not an elevated #161B22 surface). The
    // 63dp row + 1dp divider = 64dp, aligning with the rail's logo cell.
    androidx.compose.foundation.layout.Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF0D1117)),
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(63.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Search fills the remaining width, capped at 576dp (web's
            // max-w-xl), then a 24dp gap to the right cluster; bell + avatar
            // 16dp apart (web `ml-6` + `gap-4`). Matches the mobile TopBar.
            if (searchSlot != null) {
                Box(modifier = Modifier.widthIn(max = 576.dp).fillMaxWidth()) { searchSlot() }
            } else {
                TvSearchBar(
                    modifier = Modifier.widthIn(max = 576.dp).fillMaxWidth(),
                    onClick = onSearch,
                    focusRequester = searchFocusRequester,
                    downTarget = searchDownTarget,
                )
            }
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(start = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TvIconCell(icon = Lucide.Bell, contentDescription = "Watchlist", onClick = onWatchlist)
                if (activeAccount != null) {
                    AccountAvatarButton(account = activeAccount, onClick = onAccountClick)
                } else {
                    TvIconCell(icon = Lucide.User, contentDescription = "Account", onClick = onAccountClick)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF30363D)))
    }
}

/** Cosmetic search bar — wide pill with magnifying-glass icon + placeholder
 *  text. Receives DPAD focus; CENTER pushes SearchScreen. Mirrors
 *  chino-mobile's SearchField so the two clients have visual parity. */
@Composable
private fun TvSearchBar(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    downTarget: FocusRequester? = null,
) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            // Web/mobile search field: 42dp tall, 8dp radius (NOT a pill),
            // #161B22 fill, 1dp #30363D border (focus bumps it to 2dp blue),
            // #C9D1D9 search glyph. (CDP-verified via the mobile SearchField.)
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF58A6FF) else Color(0xFF30363D),
                shape = RoundedCornerShape(8.dp),
            )
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // DOWN from the search field goes to the hero Play (not a rail icon).
            .then(
                if (downTarget != null) {
                    Modifier.focusProperties { down = downTarget }
                } else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Lucide.Search,
            contentDescription = null,
            tint = Color(0xFFC9D1D9),
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Search movies, shows…",
            color = Color(0xFF8B949E),
            fontSize = 16.sp,
        )
    }
}

/** Circular icon cell matching chino-mobile's IconCell — focusable, DPAD-
 *  selectable, brand-blue focus ring. Used for the bell (watchlist) slot
 *  in TvTopBar and the Watchlist MORE-view header actions; can host any
 *  single Lucide glyph. */
@Composable
internal fun TvIconCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
) {
    var focused by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Web/mobile header icon button: 36×36, 8dp radius (NOT a circle),
    // transparent at rest (just the white glyph), surface step + blue ring
    // only on DPAD focus. (CDP-verified via the mobile IconCell.)
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0xFF21262D) else Color.Transparent)
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(8.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

private data class Decade(val label: String, val min: Int, val max: Int)

private val DECADES = listOf(
    Decade("2020s", 2020, 2099),
    Decade("2010s", 2010, 2019),
    Decade("2000s", 2000, 2009),
    Decade("1990s", 1990, 1999),
    Decade("1980s", 1980, 1989),
    Decade("Older", 1900, 1979),
)

private val RATING_THRESHOLDS = listOf(8.0, 7.0, 6.0)

@Composable
private fun FilterChipsRow(
    genres: List<String>,
    filter: BrowseFilter,
    onFilterChange: (BrowseFilter) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 32.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // "All" chip — clears every filter. Only meaningful when something is
        // active; rendered always so its position is stable as the user
        // navigates with DPAD.
        item("clear") {
            FilterChip(
                label = "All",
                active = !filter.isActive,
                onClick = { onFilterChange(BrowseFilter()) },
            )
        }
        // Type chips (Movies / Series) — sit next to All since they're the
        // most semantic axis; web exposes them as separate sections, TV folds
        // them into the filter row so all axes live in one place.
        item("type:movie") {
            val on = filter.type == "movie"
            FilterChip(
                label = "Movies",
                active = on,
                onClick = { onFilterChange(filter.copy(type = if (on) null else "movie")) },
            )
        }
        item("type:series") {
            val on = filter.type == "series"
            FilterChip(
                label = "Series",
                active = on,
                onClick = { onFilterChange(filter.copy(type = if (on) null else "series")) },
            )
        }
        tvRowItems(DECADES, key = { "d:${it.label}" }) { d ->
            val on = filter.yearMin == d.min && filter.yearMax == d.max
            FilterChip(
                label = d.label,
                active = on,
                onClick = {
                    onFilterChange(
                        filter.copy(
                            yearMin = if (on) null else d.min,
                            yearMax = if (on) null else d.max,
                        ),
                    )
                },
            )
        }
        tvRowItems(RATING_THRESHOLDS, key = { "r:$it" }) { r ->
            val on = filter.ratingMin == r
            FilterChip(
                label = "${r.toInt()}+",
                active = on,
                onClick = { onFilterChange(filter.copy(ratingMin = if (on) null else r)) },
            )
        }
        tvRowItems(genres, key = { "g:$it" }) { g ->
            val on = filter.genre.equals(g, ignoreCase = true)
            FilterChip(
                label = g,
                active = on,
                onClick = { onFilterChange(filter.copy(genre = if (on) null else g)) },
            )
        }
    }
}

@Composable
internal fun FilterChip(label: String, active: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = if (active) {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = Color.White,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
        },
    ) {
        Text(text = label)
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<cloud.nalet.chino.tv.data.api.ContinueWatchingItem>,
    baseUrl: String,
    streamToken: String,
    showProgress: Boolean,
    onItemSelected: (String) -> Unit,
    // Non-null only on the in-progress shelf. When set, the card's actions menu
    // gains a "Remove from Continue Watching" row and a LONG-press of CENTER
    // opens the menu.
    onRemove: ((String) -> Unit)? = null,
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    onShowMenu: (CardMenuRequest) -> Unit = {},
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(end = 32.dp, top = 12.dp, bottom = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tvRowItems(
            items = items,
            key = { it.id },
        ) { item ->
            val isEpisode = item.type == "episode"
            val cardTitle =
                if (isEpisode && item.seriesTitle != null) item.seriesTitle!! else item.title
            // Hold-OK opens the shared menu: watched toggle on every CW card,
            // plus "Remove from Continue Watching" on the in-progress shelf.
            val openMenu: () -> Unit = {
                val watched = item.watchedAt != null
                val actions = buildList {
                    add(watchedCardAction(watched) { next -> onToggleWatched(item.id, next) })
                    if (onRemove != null) {
                        add(
                            CardAction(
                                icon = Lucide.X,
                                label = "Remove from Continue Watching",
                                destructive = true,
                                onClick = { onRemove(item.id) },
                            ),
                        )
                    }
                }
                onShowMenu(CardMenuRequest(title = cardTitle, actions = actions))
            }
            ContinueWatchingCard(
                item = item,
                baseUrl = baseUrl,
                streamToken = streamToken,
                showProgress = showProgress,
                onClick = { onItemSelected(item.id) },
                onLongPress = openMenu,
                // The hint reads "remove" only on the in-progress shelf; the
                // Next Up shelf still has the menu but no remove, so it gets a
                // generic "options" hint.
                hasRemove = onRemove != null,
            )
        }
    }
}

@Composable
private fun ContinueWatchingCard(
    item: cloud.nalet.chino.tv.data.api.ContinueWatchingItem,
    baseUrl: String,
    streamToken: String,
    showProgress: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    hasRemove: Boolean,
) {
    // Portrait poster matching the mobile MediaCard: the SERIES title (for
    // episodes), an "SxxExx · episode-title" badge, and a #58A6FF progress
    // bar over the poster bottom — shown only for Continue Watching, hidden
    // for Next Up (showProgress=false). Was a landscape backdrop tile.
    val isEpisode = item.type == "episode"
    val displayTitle = if (isEpisode && item.seriesTitle != null) item.seriesTitle!! else item.title
    val progress = if (showProgress && item.durationSec > 0)
        (item.positionSec.toFloat() / item.durationSec.toFloat()).coerceIn(0f, 1f) else null
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        // LONG-press of CENTER on a focused card opens the actions menu (web
        // parity: the MediaCard overflow menu) — "Mark as watched/unwatched"
        // on every card, plus "Remove from Continue Watching" on the in-
        // progress shelf. Short press still opens detail.
        onLongClick = onLongPress,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused },
    ) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Watched ✓ badge — top-right, shown once the user finishes a
                // CW/Next-Up item (e.g. just un-marked then re-marked). Mirrors
                // the PosterCard / Browse badge so the surface is consistent.
                if (item.watchedAt != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2EA043))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "✓",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color(0xFF30363D)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(Color(0xFF58A6FF)),
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (isEpisode && item.seasonNumber != null && item.episodeNumber != null) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "S%02dE%02d".format(item.seasonNumber, item.episodeNumber),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        item.title.takeIf { it.isNotBlank() }?.let { ep ->
                            Text("·", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = ep,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else if (!isEpisode && (item.year != null || item.rating != null)) {
                    // Movie meta line — "<year> • <rating>" with the rating in
                    // accent blue, mirroring web's MediaCard year·rating row
                    // (and the PosterCard meta strip below the home shelves).
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.year?.let { y ->
                            Text(
                                text = y.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item.rating?.let { r ->
                            if (item.year != null) {
                                Text("•", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                text = String.format("%.1f", r),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                // Long-press discoverability hint — only on the focused card,
                // so the rest of the shelf stays clean. Wording matches the
                // available actions: the in-progress shelf can remove; both
                // shelves expose the watched toggle.
                if (focused) {
                    Text(
                        text = if (hasRemove) "Hold OK for options" else "Hold OK to mark watched",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroBanner(
    item: Item,
    backdropUrl: String,
    heroIndex: Int = 0,
    heroCount: Int = 0,
    playFocusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    onFocusWithin: (Boolean) -> Unit = {},
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
) {
    // Black card with the backdrop anchored RIGHT (60% wide, cropped) and a
    // left black-to-transparent gradient over the image — the chino-web /
    // tablet HeroSection layout (CDP-verified in the mobile HeroContentWide).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            // hasFocus is true while Play OR More Info (descendants) hold focus;
            // pauses the carousel so a focused button is never swapped away.
            .onFocusChanged { onFocusWithin(it.hasFocus) },
    ) {
        AsyncImage(
            model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                .data(backdropUrl)
                .crossfade(true)
                .build(),
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.6f)
                .align(Alignment.CenterEnd),
        )
        // Left mask: opaque black for the left 40% (the text inset), fading to
        // the visible image by 64%. Matches web's
        // mask-image: linear-gradient(to left, black 0 60%, transparent 100%).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.0f to Color.Black,
                        0.4f to Color.Black,
                        0.64f to Color.Transparent,
                        1.0f to Color.Transparent,
                    ),
                ),
        )
        // Title + year-rating + overview — pinned TOP-left.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 48.dp, end = 16.dp, top = 48.dp)
                .widthIn(max = 476.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = item.title,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.year?.let { y -> Text(y.toString(), color = Color.White, fontSize = 16.sp) }
                item.rating?.let { r ->
                    if (item.year != null) Text("•", color = Color.White)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = String.format("%.1f", r),
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            item.overview?.let { o ->
                Text(
                    text = o,
                    color = Color(0xFFC9D1D9),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    maxLines = 3,
                    // Trailing "…" when the synopsis is longer than 3 lines,
                    // like web (default Clip just hard-cuts mid-word).
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        // Play + More Info — pinned BOTTOM-left (so they stay put when the
        // overview is null, matching web's flex-col layout).
        androidx.compose.foundation.layout.Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, bottom = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = onPlay,
                // Hero Play is the home's default focus target; requester is
                // driven from LibraryContent so BACK from the player lands here.
                // UP goes to the search bar (not a rail icon — the rail is
                // reachable only via LEFT).
                modifier = Modifier
                    .then(
                        if (playFocusRequester != null) Modifier.focusRequester(playFocusRequester)
                        else Modifier,
                    )
                    .then(
                        if (upTarget != null) Modifier.focusProperties { up = upTarget }
                        else Modifier,
                    ),
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.White,
                    // Brighter blue on DPAD focus (#79C0FF) so the focused
                    // state actually reads at 10ft — the resting #58A6FF and a
                    // matching focus colour were indistinguishable. Pairs with
                    // tv Button's default focus scale-up.
                    focusedContainerColor = Color(0xFF79C0FF),
                    focusedContentColor = Color.White,
                ),
            ) {
                Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Play", fontSize = 16.sp)
            }
            Button(
                onClick = onMoreInfo,
                modifier = if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier,
                shape = ButtonDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ButtonDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White.copy(alpha = 0.3f),
                    focusedContentColor = Color.White,
                ),
            ) {
                Icon(Lucide.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(text = "More Info", fontSize = 16.sp)
            }
        }
        // Hero pagination dots — bottom-left, below the buttons (8dp circles,
        // 6dp gap, active white / inactive white@30%). Non-interactive.
        if (heroCount > 1) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(heroCount) { i ->
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (i == heroIndex) Color.White else Color.White.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

/** Shared icon-plus-label button for the LibraryScreen's top action row. */
@Composable
private fun ChromeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContentColor = MaterialTheme.colorScheme.onBackground,
        ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

@Composable
internal fun ItemGrid(
    items: List<Item>,
    baseUrl: String,
    streamToken: String,
    onItemSelected: (String) -> Unit,
    // Navigates to the full Movies/Series overview. The home shelf is capped
    // (~20 items, VM-side) and terminates in a "See All" tile instead of
    // paging the whole catalogue inline — that endless inline paging is what
    // made the rail look like it looped forever (#150).
    onSeeAll: (() -> Unit)? = null,
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    onShowMenu: (CardMenuRequest) -> Unit = {},
) {
    // Web's home uses finite MediaRow shelves — a single horizontal scroll of
    // fixed-width PosterCards, capped, with a See All escape hatch. DPAD_DOWN
    // lands cleanly on the next row and the outer page glides up via
    // bringIntoView.
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(end = 32.dp, top = 12.dp, bottom = 12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tvRowItems(items, key = { it.id }) { item ->
            PosterCard(
                item = item,
                posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                onItemSelected = onItemSelected,
                // Hold-OK opens the shared menu with the watched toggle. Marking
                // watched drops the card from this rail (rails are unwatched=true).
                onLongPress = {
                    val watched = item.watchedAt != null
                    onShowMenu(
                        CardMenuRequest(
                            title = item.title,
                            actions = listOf(
                                watchedCardAction(watched) { next -> onToggleWatched(item.id, next) },
                            ),
                        ),
                    )
                },
            )
        }
        // End-of-row "See All" tile — same footprint as a PosterCard so the
        // row keeps a flush right edge. Mirrors web's MediaRow See All tile.
        if (onSeeAll != null && items.isNotEmpty()) {
            tvRowItems(listOf("__see_all__"), key = { it }) {
                SeeAllCard(onClick = onSeeAll)
            }
        }
    }
}

@Composable
internal fun SeeAllCard(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(Color(0xFF161B22))
                .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF58A6FF),
                modifier = Modifier.size(32.dp),
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "See All",
                color = Color(0xFF58A6FF),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PosterCard(
    item: Item,
    posterUrl: String,
    onItemSelected: (String) -> Unit,
    onLongPress: () -> Unit = {},
) {
    // Card body: poster on top in 2:3, meta strip below with title + year.
    // Matches chino-web MediaCard layout — title outside the poster, not
    // overlaid, so the artwork reads cleanly without a gradient scrim.
    // Fixed 180dp width because the parent is a TvLazyRow (Netflix-style
    // horizontal shelf); the previous 6-col grid measured cards via the
    // grid's column-width.
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = { onItemSelected(item.id) },
        // Hold-OK opens the card actions menu (web MediaCard overflow parity).
        onLongClick = onLongPress,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused },
    ) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Watched badge — top-right of the poster. Server stamps
                // watched_at when the user has finished an item; non-null
                // means "show the check". Matches MediaCard's
                // bg-emerald-500 corner pill on web.
                if (item.watchedAt != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF2EA043))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "✓",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                // Year · rating, both in muted style — matches chino-web
                // MediaCard's small bodySm meta row.
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.year?.let { y ->
                        Text(
                            text = y.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    item.rating?.let { r ->
                        if (item.year != null) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = String.format("%.1f", r),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                // Long-press discoverability hint — only on the focused card so
                // the hold-OK actions menu is findable at 10ft (web MediaCard
                // surfaces a hover overflow button).
                if (focused) {
                    Text(
                        text = if (item.watchedAt != null) "Hold OK to mark unwatched" else "Hold OK to mark watched",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF8B949E),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/* ───────────────────────────  Card actions menu  ──────────────────────────
 * Hold-OK (long-press CENTER) on a focused poster card opens this. It's the
 * player MenuPopover idiom lifted to a card surface: a dimmed scrim with a
 * centered #161B22 / 8dp card of focusable rows; the first row auto-focuses,
 * CENTER selects (and closes), BACK closes (BackHandler). Shared by the Home
 * rails (LibraryScreen) and the Browse grid (BrowseScreen) so the affordance
 * is identical on every card surface. Web parity: the MediaCard overflow menu.
 */

/** One row in [CardActionsMenu]: a Lucide glyph + label, plus the click
 *  handler. `destructive` paints the label red (the CW "Remove" row). */
internal data class CardAction(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** Builds the "Mark as watched" / "Mark as unwatched" action — exact web
 *  wording, Eye when unwatched (tap to mark) / EyeOff when watched (tap to
 *  un-mark). `watched` is the item's CURRENT state (watchedAt != null);
 *  `onToggle` receives the NEXT desired state (true = mark watched). Reused on
 *  every card surface so wording/behaviour stay identical. */
internal fun watchedCardAction(watched: Boolean, onToggle: (Boolean) -> Unit): CardAction =
    CardAction(
        icon = if (watched) Lucide.EyeOff else Lucide.Eye,
        label = if (watched) "Mark as unwatched" else "Mark as watched",
        onClick = { onToggle(!watched) },
    )

@Composable
internal fun CardActionsMenu(
    title: String,
    actions: List<CardAction>,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler { onDismiss() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Scrim — dim the page behind so the focused menu reads at 10ft.
            // The scrim itself is clickable so a pointer tap outside dismisses;
            // DPAD users press BACK (handled above).
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(320.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                // Swallow scrim clicks that land on the card itself.
                .clickable(enabled = false) {}
                .padding(vertical = 4.dp),
        ) {
            // Title row — the item being acted on, muted like the player menu
            // header so it reads as a label not an action.
            Text(
                text = title,
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            actions.forEachIndexed { idx, action ->
                CardActionRow(
                    action = action,
                    focusRequester = firstFocus.takeIf { idx == 0 },
                    // CENTER fires the action AND closes the menu (the actions
                    // are one-shot — toggle watched / remove — so leaving the
                    // menu open would be stale).
                    onSelected = { action.onClick(); onDismiss() },
                )
            }
        }
    }
}

@Composable
private fun CardActionRow(
    action: CardAction,
    focusRequester: FocusRequester?,
    onSelected: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .fillMaxWidth()
        .background(if (focused) Color(0xFF58A6FF).copy(alpha = 0.22f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 12.dp)
    val m = if (focusRequester != null) base.focusRequester(focusRequester) else base
    val fg = when {
        action.destructive -> Color(0xFFF85149)
        else -> Color.White
    }
    androidx.compose.foundation.layout.Row(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onSelected(); true
                } else false
            }
            .clickable(onClick = onSelected),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = fg,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = action.label,
            color = fg,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenterText(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Round, focusable, clickable avatar tile for the library top bar.
 *  Renders the active user's gravatar (or initial+colour fallback) inside
 *  a 44dp circle with a focus ring. Matches the avatar pattern used in
 *  chino-web's top-right account chip, scaled for TV viewing distance. */
@Composable
private fun AccountAvatarButton(
    account: cloud.nalet.chino.tv.data.auth.Account,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .then(
                if (focused) Modifier.border(
                    width = 2.dp,
                    color = Color(0xFF58A6FF),
                    shape = CircleShape,
                ) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        cloud.nalet.chino.tv.ui.auth.Avatar(
            displayName = account.displayName,
            email = account.email,
            size = 36.dp,
        )
    }
}
