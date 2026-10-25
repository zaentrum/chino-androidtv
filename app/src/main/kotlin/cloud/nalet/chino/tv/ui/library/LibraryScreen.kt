package cloud.nalet.chino.tv.ui.library

import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import cloud.nalet.chino.tv.ui.theme.ChinoSurfaceHi
import cloud.nalet.chino.tv.ui.theme.ChinoFg
import cloud.nalet.chino.tv.ui.theme.ChinoPage
import cloud.nalet.chino.tv.ui.theme.scrim
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as tvRowItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.R
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.episodeCode
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoCard
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.ItemFocus
import cloud.nalet.chino.tv.ui.theme.LogoMark
import cloud.nalet.chino.tv.ui.theme.PosterImage
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.shelfPadding
import cloud.nalet.chino.tv.ui.theme.shelfRoom
import cloud.nalet.chino.tv.ui.theme.SafeArea
import cloud.nalet.chino.tv.ui.theme.ScrollsInSafeArea
import cloud.nalet.chino.tv.ui.theme.runToScreenEnd
import cloud.nalet.chino.tv.ui.theme.ReturnFocus
import cloud.nalet.chino.tv.ui.theme.rememberReturnFocus
import cloud.nalet.chino.tv.ui.theme.focusFirstOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.input.pointer.pointerInput
import cloud.nalet.chino.tv.ui.theme.scrollsToScreenEdges
import cloud.nalet.chino.tv.ui.theme.LoadError
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.ChevronRight
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
import com.composables.icons.lucide.Check
import cloud.nalet.chino.tv.ui.theme.WatchedMark
import cloud.nalet.chino.tv.ui.theme.WatchlistMark

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onItemSelected: (String) -> Unit = {},
    onHeroPlay: (String) -> Unit = onItemSelected,
    /** A card menu's play: the title, from [resumeSec] where it is above 0,
     *  else where the player picks it up. */
    onPlayCard: (itemId: String, resumeSec: Int) -> Unit = { id, _ -> onHeroPlay(id) },
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onZap: () -> Unit,
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val homeEntryNonce by viewModel.homeEntryNonce.collectAsState()
    // The ids in the Watchlist: every card's bookmark.
    val watchlist by viewModel.watchlist.collectAsState()
    // Refresh the continue-watching shelf whenever the screen comes back to
    // the foreground — covers "user finished an episode and pressed BACK"
    // (LocalLifecycleOwner here is the NavBackStackEntry, which returns to
    // RESUMED on pop) and "user pressed HOME mid-watch and reopened the app"
    // (activity ON_RESUME propagates into the entry). The skip-the-redundant-
    // first-refresh decision lives in the VIEWMODEL (onScreenResumed), not in
    // a composition-local flag: this destination leaves composition while
    // Detail/Player sits on top, so this DisposableEffect is recreated on
    // every return — a local `skipFirst = true` here swallowed the catch-up
    // ON_RESUME delivered on observer re-registration (the very event that
    // means "user came back"), while the surviving nav-entry-scoped VM never
    // re-ran init(); net effect: the shelf never refreshed on in-app BACK.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ChinoPage {
        when (val s = state) {
            LibraryUiState.Loading -> CenterText(stringResource(R.string.library_loading))
            // The screen's way on: try again, focused first.
            is LibraryUiState.Error -> LoadError(
                title = stringResource(R.string.library_error_title),
                failure = s.failure,
                onRetry = viewModel::refresh,
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
                    watchlist = watchlist,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
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
                    noticesBell = noticesBell,
                    activeAccount = activeAccount,
                    onFilterChange = viewModel::applyFilter,
                    onHeroFocusChanged = viewModel::setHeroRotationPaused,
                    homeEntryNonce = homeEntryNonce,
                    onRailHome = viewModel::onRailHome,
                    onZap = onZap,
                    onRemoveFromContinueWatching = viewModel::removeFromContinueWatching,
                    onToggleWatched = viewModel::toggleWatched,
                    onPlayCard = onPlayCard,
                )
            }
        }
    }
}

@Composable
internal fun LibraryContent(
    movies: List<Item>,
    series: List<Item>,
    continueWatching: List<cloud.nalet.chino.tv.data.api.ContinueWatchingItem>,
    /** The ids in the Watchlist: the cards' bookmarks. */
    watchlist: Set<String>,
    baseUrl: String,
    streamToken: String,
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
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell?,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account?,
    onFilterChange: (BrowseFilter) -> Unit,
    onHeroFocusChanged: (Boolean) -> Unit = {},
    homeEntryNonce: Int = 0,
    onRailHome: () -> Unit = {},
    onZap: () -> Unit,
    onRemoveFromContinueWatching: (String) -> Unit = {},
    // Card-action watched toggle. `markWatched` is the NEXT desired state.
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    /** A card menu's play or resume ([playCardAction]). */
    onPlayCard: (itemId: String, resumeSec: Int) -> Unit = { id, _ -> onHeroPlay(id) },
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
    // Home opens with the focus on the hero's play, in the shared focus
    // style, on every entry - sign-in, BACK from the player, rail Home (the
    // nonce). The carousel holds while the hero has the focus, so the
    // focused button is never swapped away. Without a hero (a filter emptied
    // the rows), the search field.
    // BACK from what a card opened - a title's page, the player from its
    // menu - gives the focus back to that card instead.
    val back = rememberReturnFocus()
    LaunchedEffect(homeEntryNonce) {
        if (back.restore()) return@LaunchedEffect
        repeat(3) {
            if (runCatching { heroPlayFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            androidx.compose.runtime.withFrameNanos { }
        }
        runCatching { searchFocus.requestFocus() }
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
                noticesBell = noticesBell,
                activeAccount = activeAccount,
                onAccountClick = onAccountClick,
                searchFocusRequester = searchFocus,
                searchDownTarget = heroPlayFocus,
            )
            // Outer column scrolls so DPAD_DOWN can walk past the visible
            // portion. The rail + TopBar stay fixed; only the
            // hero / chips / shelves move under the camera - out to the
            // screen's end and bottom edges, the shelves running to the
            // screen's edge; what rests stays inside the safe area, and a
            // focused card is brought into view inside it.
            ScrollsInSafeArea(trailing = SafeArea.Vertical) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .scrollsToScreenEdges()
                    .verticalScroll(pageScroll)
                    .padding(end = SafeArea.Horizontal, bottom = SafeArea.Vertical),
            ) {
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
                    detailsFocus = back.requester(HERO_DETAILS),
                    onMoreInfo = { back.opens(HERO_DETAILS); onItemSelected(item.id) },
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
                ShelfHeading("continue watching")
                ContinueWatchingRow(
                    items = cwRows,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    saved = watchlist,
                    showProgress = true,
                    onItemSelected = onItemSelected,
                    // Only the in-progress shelf gets the dismiss action — Next
                    // Up rows are server-substituted and have nothing to remove.
                    onRemove = onRemoveFromContinueWatching,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                    onPlay = onPlayCard,
                    returnFocus = back,
                    shelf = "continue",
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (nextUpRows.isNotEmpty()) {
                ShelfHeading("next up")
                ContinueWatchingRow(
                    items = nextUpRows,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    saved = watchlist,
                    showProgress = false,
                    onItemSelected = onItemSelected,
                    // Next Up rows carry no dismiss, but they DO get the watched
                    // toggle so the menu is present on every card surface.
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                    onPlay = onPlayCard,
                    returnFocus = back,
                    shelf = "next",
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
                ShelfHeading("recently added movies")
                ItemGrid(
                    items = movies,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    saved = watchlist,
                    onItemSelected = onItemSelected,
                    onSeeAll = onMoviesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                    onPlay = { id -> onPlayCard(id, 0) },
                    returnFocus = back,
                    shelf = "movies",
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (series.isNotEmpty()) {
                ShelfHeading("recently added shows")
                ItemGrid(
                    items = series,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    saved = watchlist,
                    onItemSelected = onItemSelected,
                    onSeeAll = onSeriesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                    returnFocus = back,
                    shelf = "shows",
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(20.dp))
            }
            if (topRated.isNotEmpty()) {
                ShelfHeading("top rated")
                ItemGrid(
                    items = topRated,
                    baseUrl = baseUrl,
                    streamToken = streamToken,
                    saved = watchlist,
                    onItemSelected = onItemSelected,
                    // Top Rated is a movies-only shelf — See All goes to Movies.
                    onSeeAll = onMoviesNav,
                    onToggleWatched = onToggleWatched,
                    onShowMenu = showCardMenu,
                    onPlay = { id -> onPlayCard(id, 0) },
                    returnFocus = back,
                    shelf = "top",
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
        }
        // Card-actions overlay — a single instance hosted above the whole page
        // so its scrim dims the rail + topbar + shelves. Opened by hold-OK on
        // any card (Home rails or CW shelf); BACK / scrim tap closes it, the
        // focus back on the card - or, where a pick took the card off its
        // shelf, the one beside it, else the hero's play.
        CardMenuOverlay(
            request = activeCardMenu,
            onClose = { activeCardMenu = null },
            fallback = heroPlayFocus,
        )
    }
}

/**
 * What a card hands its screen to open the shared actions menu: its [title]
 * and [actions], and where the focus goes back to as the menu closes - the
 * card that opened it ([opener]), or, where a pick took that card off the
 * screen (marked as watched off an unwatched shelf, removed from continue
 * watching), the first of [besides] still there: the card after it, then
 * the one before.
 */
internal data class CardMenuRequest(
    val title: String,
    val actions: List<CardAction>,
    val opener: FocusRequester? = null,
    val besides: List<FocusRequester> = emptyList(),
)

/** The cards beside the one at [index] of [count], the one after it first:
 *  where the focus goes where a card's menu took that card away. */
internal fun <T> besidesOf(index: Int, count: Int, requester: (Int) -> T): List<T> =
    listOf(index + 1, index - 1).filter { it in 0 until count }.map(requester)

/**
 * The card menu [request] opens, while there is one, and the focus given back
 * as it closes - a pick, BACK, a tap on the scrim: to the card that opened it,
 * else to the first of the cards beside it still on the screen, else to
 * [fallback] - never to the first item on the screen, the rail's Home, where
 * the next OK would leave the screen.
 */
@Composable
internal fun CardMenuOverlay(
    request: CardMenuRequest?,
    onClose: () -> Unit,
    fallback: FocusRequester? = null,
) {
    // Each close once, the menu that closed by its identity: two menus of
    // one card are two closes.
    var closed by remember { mutableStateOf<CardMenuRequest?>(null, androidx.compose.runtime.referentialEqualityPolicy()) }
    request?.let { req ->
        CardActionsMenu(
            title = req.title,
            actions = req.actions,
            onDismiss = { closed = req; onClose() },
        )
    }
    LaunchedEffect(closed) {
        val req = closed ?: return@LaunchedEffect
        // A frame for the menu to go and for what the pick changed - a card
        // off its shelf - to be laid out.
        androidx.compose.runtime.withFrameNanos { }
        focusFirstOf(listOfNotNull(req.opener) + req.besides + listOfNotNull(fallback))
    }
}

/** The hero's "see details", as [ReturnFocus] knows it. */
private const val HERO_DETAILS = "hero/details"

/* ────────────────────────────  SideRail + TopBar  ────────────────────────── */

/** Vertical nav rail — `>c` brand mark up top, House / Film / Tv stacked
 *  in the middle, Settings pinned at bottom. Mirrors chino-mobile's
 *  SideRail composable so the two clients have visual parity on the same
 *  form factor. Rail items use tv-material3 Button so DPAD focus picks up
 *  the scale + outline focused state for free. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun TvSideRail(
    activeType: String?,
    onHome: () -> Unit,
    onMovies: () -> Unit,
    onSeries: () -> Unit,
    onSettings: () -> Unit,
    settingsActive: Boolean = false,
    /** Every screen's rail goes to Zap and to the Watchlist: no default, so
     *  a screen cannot leave an item there that does nothing. */
    onZap: () -> Unit,
    zapActive: Boolean = false,
    onWatchlist: () -> Unit,
    watchlistActive: Boolean = false,
    /** On the item of the screen you are on: where focus coming into the
     *  rail lands - LEFT from the content - and what a screen opened from the
     *  rail gives the focus to. */
    activeFocus: FocusRequester = remember { FocusRequester() },
) {
    // Home is where you are only where no other item is: not in Settings,
    // Zap or the Watchlist, which pass no type either.
    val homeActive = activeType == null && !settingsActive && !zapActive && !watchlistActive
    val anyActive = homeActive || activeType == "movie" || activeType == "series" || settingsActive || zapActive || watchlistActive
    // Flat canvas rail (#0D1117) with a 1dp #30363D right-edge divider and a
    // logo-cell bottom divider — the chino-web / mobile SideRail (NOT an
    // elevated #161B22 surface). 80dp wide incl. the divider. One group for
    // the remote: coming into it, the focus lands on the screen's own item,
    // never on the one nearest where it came from.
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(ChinoBg)
            .focusProperties { enter = { if (anyActive) activeFocus else FocusRequester.Default } }
            .focusGroup(),
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            // Logo cell: 64dp tall (= topbar height) with a bottom divider so
            // it meets the topbar's bottom divider at the L corner.
            androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    LogoMark(sizeDp = 36)
                }
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChinoBorder))
            }
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp),
                // Room between the items for a focused one's growth and ring.
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TvRailButton(icon = Lucide.House, label = "Home", isActive = homeActive, onClick = onHome, focusRequester = activeFocus.takeIf { homeActive })
                TvRailButton(icon = Lucide.Film, label = "Movies", isActive = activeType == "movie", onClick = onMovies, focusRequester = activeFocus.takeIf { activeType == "movie" })
                TvRailButton(icon = Lucide.Tv, label = "Series", isActive = activeType == "series", onClick = onSeries, focusRequester = activeFocus.takeIf { activeType == "series" })
                // Watchlist hub — its entry (web/mobile parity). The top-bar
                // bell is the notices', as on chino-web's header.
                TvRailButton(icon = Lucide.Bookmark, label = "Watchlist", isActive = watchlistActive, onClick = onWatchlist, focusRequester = activeFocus.takeIf { watchlistActive })
                TvRailButton(icon = Lucide.Zap, label = "Zap", isActive = zapActive, onClick = onZap, focusRequester = activeFocus.takeIf { zapActive })
            }
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                TvRailButton(icon = Lucide.Settings, label = "Settings", isActive = settingsActive, onClick = onSettings, focusRequester = activeFocus.takeIf { settingsActive })
            }
        }
        // Right-edge divider — full height, forms the L with the topbar divider.
        Box(modifier = Modifier.fillMaxHeight().width(1.dp).background(ChinoBorder))
    }
}

@Composable
internal fun TvRailButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    focus: ItemFocus = rememberItemFocus(),
    focusRequester: FocusRequester? = null,
) {
    // 48dp square (matches mobile RailButton): the active slot on a surface
    // fill so it stands out against the rail, its icon in the accent. A nav
    // item is a plain button in the focus style.
    Box(
        modifier = Modifier
            .size(48.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusStyle(focus, FocusKind.Control, surface = if (isActive) ChinoSurface else Color.Transparent)
            // Where you are, said to a screen reader as the marker shows it.
            .semantics { selected = isActive }
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
            tint = if (isActive) ChinoAccent else ChinoFocus.title(ChinoMuted, focus.focused),
            modifier = Modifier.size(24.dp),
        )
    }
}

/** Top bar with inline search field + notices bell + account avatar.
 *  Visual parity with chino-web's Header (the bar above the catalogue):
 *  wide cosmetic search input on the left taking all remaining width, then
 *  the Lucide.Bell of the signed-in person's notices with its unread count
 *  (none at all while the server's portal-api does not answer — see
 *  NoticesBellCell), then the user's avatar on the far right. CENTER on the
 *  search field pushes SearchScreen rather than opening an inline
 *  dropdown — DPAD typing through a soft keyboard is impractical inside a
 *  56dp pill, and the dedicated screen gives the keyboard the room it needs.
 */
@Composable
internal fun TvTopBar(
    onSearch: () -> Unit = {},
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
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
        modifier = Modifier.fillMaxWidth().background(ChinoBg),
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
            // the control gap apart - room for a focused one's grown ring,
            // as between every two buttons on the TV.
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
                horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
            ) {
                if (noticesBell != null && activeAccount != null) {
                    cloud.nalet.chino.tv.ui.notices.NoticesBellCell(bell = noticesBell, account = activeAccount)
                }
                if (activeAccount != null) {
                    AccountAvatarButton(account = activeAccount, onClick = onAccountClick)
                } else {
                    TvIconCell(icon = Lucide.User, contentDescription = "Account", onClick = onAccountClick)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(ChinoBorder))
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
    // A field to look at, as the live one on the search screen: a row in the
    // focus style.
    val focus = rememberItemFocus()
    androidx.compose.foundation.layout.Row(
        modifier = modifier
            // Web/mobile search field: 42dp tall, square, surface fill, a 1dp
            // border, the search glyph in fg-2. (CDP-verified via the mobile
            // SearchField.)
            .height(42.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            // DOWN from the search field goes to the hero Play (not a rail icon).
            .then(
                if (downTarget != null) {
                    Modifier.focusProperties { down = downTarget }
                } else Modifier,
            )
            .focusStyle(focus, FocusKind.Row, surface = ChinoSurface)
            .border(1.dp, ChinoBorder, RectangleShape)
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
            tint = ChinoText,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = "Search movies, shows…",
            color = ChinoFocus.text(ChinoMuted, focus.focused),
            fontSize = 16.sp,
        )
    }
}

/** Circular icon cell matching chino-mobile's IconCell — focusable, DPAD-
 *  selectable, brand-blue focus ring. Used for the notices bell in TvTopBar,
 *  a notice's Delete and the Watchlist MORE-view header actions; can host
 *  any single Lucide glyph. */
@Composable
internal fun TvIconCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    tint: Color = ChinoFg,
) {
    // Web/mobile header icon button: 36×36, square, transparent at rest (just
    // the white glyph); a plain button in the focus style. (CDP-verified via
    // the mobile IconCell.)
    val focus = rememberItemFocus()
    Box(
        modifier = Modifier
            .size(36.dp)
            .focusStyle(focus, FocusKind.Control, surface = Color.Transparent)
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

/** A shelf's heading on Home: in the mono face, in fg, as every section's
 *  heading. */
@Composable
private fun ShelfHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
        color = ChinoFg,
        modifier = Modifier.padding(bottom = 12.dp),
    )
}

/** A filter chip: filled in the accent while it is on, plain while it is off. */
@Composable
internal fun FilterChip(
    label: String,
    active: Boolean,
    focus: ItemFocus = rememberItemFocus(),
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    ChinoButton(
        onClick = onClick,
        modifier = modifier,
        // Off, a plain chip: surface-2, its label fg-2.
        container = if (active) MaterialTheme.colorScheme.primary else ChinoSurfaceHi,
        content = if (active) MaterialTheme.colorScheme.onPrimary else ChinoText,
        filled = active,
        focus = focus,
    ) {
        Text(text = label)
    }
}

@Composable
private fun ContinueWatchingRow(
    items: List<cloud.nalet.chino.tv.data.api.ContinueWatchingItem>,
    baseUrl: String,
    streamToken: String,
    /** The ids in the Watchlist: the cards' bookmarks. */
    saved: Set<String>,
    showProgress: Boolean,
    onItemSelected: (String) -> Unit,
    // Non-null only on the in-progress shelf. When set, the card's actions menu
    // gains a "Remove from Continue Watching" row and a LONG-press of CENTER
    // opens the menu.
    onRemove: ((String) -> Unit)? = null,
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    onShowMenu: (CardMenuRequest) -> Unit = {},
    /** The card's play: from its position on the in-progress shelf. */
    onPlay: (itemId: String, resumeSec: Int) -> Unit = { _, _ -> },
    /** Where the focus comes back to: the card that opened what Home comes
     *  back from, by [shelf] and its id. */
    returnFocus: ReturnFocus? = null,
    shelf: String = "",
) {
    ShelfToScreenEdge {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
        contentPadding = shelfPadding(end = 32.dp + ShelfRun, top = 12.dp, bottom = 12.dp),
        modifier = Modifier.runToScreenEnd(ShelfRun).shelfRoom().fillMaxWidth(),
    ) {
        tvRowItems(
            items = items,
            key = { it.id },
        ) { item ->
            val isEpisode = item.type == "episode"
            val cardTitle =
                if (isEpisode && item.seriesTitle != null) item.seriesTitle!! else item.title
            // Hold-OK opens the shared menu, chino-web's card menu: resume
            // where the card's bar shows a position, else play; the watched
            // toggle; and remove from continue watching on the in-progress
            // shelf.
            val key = "$shelf/${item.id}"
            val openMenu: () -> Unit = {
                val watched = item.watchedAt != null
                val resumeAt = item.positionSec.takeIf { showProgress && it > 0 } ?: 0
                val actions = buildList {
                    add(playCardAction(resumes = resumeAt > 0) { returnFocus?.opens(key); onPlay(item.id, resumeAt) })
                    add(watchedCardAction(watched) { next -> onToggleWatched(item.id, next) })
                    if (onRemove != null) {
                        add(
                            CardAction(
                                icon = Lucide.X,
                                label = REMOVE_FROM_CONTINUE_WATCHING,
                                destructive = true,
                                onClick = { onRemove(item.id) },
                            ),
                        )
                    }
                }
                onShowMenu(
                    CardMenuRequest(
                        title = cardTitle,
                        actions = actions,
                        opener = returnFocus?.requester(key),
                        besides = returnFocus?.let { r ->
                            besidesOf(items.indexOf(item), items.size) { i -> r.requester("$shelf/${items[i].id}") }
                        }.orEmpty(),
                    ),
                )
            }
            ContinueWatchingCard(
                item = item,
                baseUrl = baseUrl,
                streamToken = streamToken,
                saved = cardInWatchlist(item, saved),
                showProgress = showProgress,
                onClick = { returnFocus?.opens(key); onItemSelected(item.id) },
                onLongPress = openMenu,
                modifier = returnFocus?.let { Modifier.focusRequester(it.requester(key)) } ?: Modifier,
            )
        }
    }
    }
}

/**
 * Whether a continue-watching card shows the Watchlist's bookmark: an
 * episode's card is its series' - its title and its poster are the series' -
 * so it is marked when the series is in the Watchlist; a movie's when the
 * movie is.
 */
internal fun cardInWatchlist(
    item: cloud.nalet.chino.tv.data.api.ContinueWatchingItem,
    watchlist: Set<String>,
): Boolean {
    val series = item.parentId?.takeIf { item.type == "episode" && it.isNotBlank() }
    return (series ?: item.id) in watchlist
}

@Composable
private fun ContinueWatchingCard(
    item: cloud.nalet.chino.tv.data.api.ContinueWatchingItem,
    baseUrl: String,
    streamToken: String,
    /** In the Watchlist, an episode by its series ([cardInWatchlist]): its
     *  bookmark, top-left. */
    saved: Boolean,
    showProgress: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Portrait poster matching the mobile MediaCard: the SERIES title (for
    // episodes), an "SxxExx · episode-title" badge, and a #58A6FF progress
    // bar over the poster bottom — shown only for Continue Watching, hidden
    // for Next Up (showProgress=false). Was a landscape backdrop tile.
    val isEpisode = item.type == "episode"
    val displayTitle = if (isEpisode && item.seriesTitle != null) item.seriesTitle!! else item.title
    val progress = if (showProgress && item.durationSec > 0)
        (item.positionSec.toFloat() / item.durationSec.toFloat()).coerceIn(0f, 1f) else null
    val focus = rememberItemFocus()
    val focused = focus.focused
    ChinoCard(
        onClick = onClick,
        // LONG-press of CENTER on a focused card opens the actions menu (web
        // parity: the MediaCard overflow menu) — "Mark as watched/unwatched"
        // on every card, plus "Remove from Continue Watching" on the in-
        // progress shelf. Short press still opens detail.
        onLongClick = onLongPress,
        modifier = modifier.width(180.dp),
        focus = focus,
    ) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                PosterImage(
                    model = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                    title = displayTitle,
                    contentDescription = displayTitle,
                    modifier = Modifier.fillMaxSize(),
                )
                // The Watchlist's bookmark top-left, the watched check
                // top-right (shown once the user finishes a CW/Next-Up item,
                // e.g. just un-marked then re-marked): the same marks as the
                // PosterCard's and Browse's.
                if (saved) WatchlistMark(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (item.watchedAt != null) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
                if (progress != null && progress > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(ChinoBorder),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .background(ChinoAccent),
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
                val meta = ChinoFocus.text(MaterialTheme.colorScheme.onSurfaceVariant, focused)
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = ChinoFocus.title(MaterialTheme.colorScheme.onSurface, focused),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (isEpisode && item.seasonNumber != null && item.episodeNumber != null) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = episodeCode(item.seasonNumber, item.episodeNumber, item.episodeEnd),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        item.title.takeIf { it.isNotBlank() }?.let { ep ->
                            Text("·", style = MaterialTheme.typography.bodySmall, color = meta)
                            Text(
                                text = ep,
                                style = MaterialTheme.typography.bodySmall,
                                color = meta,
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
                                color = meta,
                            )
                        }
                        item.rating?.let { r ->
                            if (item.year != null) {
                                Text("•", style = MaterialTheme.typography.bodySmall, color = meta)
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
                        // Its menu holds play and the watched toggle at least.
                        text = CARD_OPTIONS_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = ChinoFocus.text(ChinoMuted, focused),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** The hero's rating: filled in the accent, its number in the page's colour. */
@Composable
internal fun RatingBadge(rating: Double) {
    Box(
        modifier = Modifier
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = String.format("%.1f", rating),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
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
    /** On "see details": where BACK from the title's page gives the focus. */
    detailsFocus: FocusRequester? = null,
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
            .clip(RectangleShape)
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
                color = ChinoFg,
                maxLines = 2,
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item.year?.let { y -> Text(y.toString(), color = ChinoFg, fontSize = 16.sp) }
                item.rating?.let { r ->
                    if (item.year != null) Text("•", color = ChinoFg)
                    RatingBadge(r)
                }
            }
            item.overview?.let { o ->
                Text(
                    text = o,
                    color = ChinoText,
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
            // 12 dp between a focused button's grown ring and the other.
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
        ) {
            ChinoButton(
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
                // Filled: its fill stays on focus, the ring around it.
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                filled = true,
            ) {
                Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(text = "play", fontSize = 16.sp)
            }
            // A plain button: surface-2, its label fg-2.
            ChinoButton(
                onClick = onMoreInfo,
                modifier = Modifier
                    .then(if (detailsFocus != null) Modifier.focusRequester(detailsFocus) else Modifier)
                    .then(if (upTarget != null) Modifier.focusProperties { up = upTarget } else Modifier),
            ) {
                Icon(Lucide.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                Text(text = "see details", fontSize = 16.sp)
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
                            .clip(RectangleShape)
                            .background(if (i == heroIndex) Color.White else Color.White.copy(alpha = 0.3f)),
                    )
                }
            }
        }
    }
}

@Composable
internal fun ItemGrid(
    items: List<Item>,
    baseUrl: String,
    streamToken: String,
    onItemSelected: (String) -> Unit,
    /** The ids in the Watchlist: the cards' bookmarks. */
    saved: Set<String> = emptySet(),
    // Navigates to the full Movies/Series overview. The home shelf is capped
    // (~20 items, VM-side) and terminates in a "See All" tile instead of
    // paging the whole catalogue inline — that endless inline paging is what
    // made the rail look like it looped forever (#150).
    onSeeAll: (() -> Unit)? = null,
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    onShowMenu: (CardMenuRequest) -> Unit = {},
    /** A movie card's play. A show's card has none: what it plays is worked
     *  out on its page. */
    onPlay: ((String) -> Unit)? = null,
    /** Where the focus comes back to: the card that opened what Home comes
     *  back from, by [shelf] and its id. */
    returnFocus: ReturnFocus? = null,
    shelf: String = "",
) {
    // Web's home uses finite MediaRow shelves — a single horizontal scroll of
    // fixed-width PosterCards, capped, with a See All escape hatch. DPAD_DOWN
    // lands cleanly on the next row and the outer page glides up via
    // bringIntoView.
    ShelfToScreenEdge {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
        contentPadding = shelfPadding(end = 32.dp + ShelfRun, top = 12.dp, bottom = 12.dp),
        modifier = Modifier.runToScreenEnd(ShelfRun).shelfRoom().fillMaxWidth(),
    ) {
        tvRowItems(items, key = { it.id }) { item ->
            val key = "$shelf/${item.id}"
            PosterCard(
                item = item,
                posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                onItemSelected = { id -> returnFocus?.opens(key); onItemSelected(id) },
                saved = item.id in saved,
                modifier = returnFocus?.let { Modifier.focusRequester(it.requester(key)) } ?: Modifier,
                // Hold-OK opens the shared menu: a movie's play, the watched
                // toggle. Marking watched drops the card from this rail
                // (rails are unwatched=true).
                onLongPress = {
                    val watched = item.watchedAt != null
                    onShowMenu(
                        CardMenuRequest(
                            title = item.title,
                            actions = cardActions(item, onPlay?.let { play -> { id: String -> returnFocus?.opens(key); play(id) } }, watched) { next ->
                                onToggleWatched(item.id, next)
                            },
                            opener = returnFocus?.requester(key),
                            besides = returnFocus?.let { r ->
                                besidesOf(items.indexOf(item), items.size) { i -> r.requester("$shelf/${items[i].id}") }
                            }.orEmpty(),
                        ),
                    )
                },
            )
        }
        // End-of-row "See All" tile — same footprint as a PosterCard so the
        // row keeps a flush right edge. Mirrors web's MediaRow See All tile.
        if (onSeeAll != null && items.isNotEmpty()) {
            tvRowItems(listOf("__see_all__"), key = { it }) {
                val key = "$shelf/see-all"
                SeeAllCard(
                    onClick = { returnFocus?.opens(key); onSeeAll() },
                    modifier = returnFocus?.let { Modifier.focusRequester(it.requester(key)) } ?: Modifier,
                )
            }
        }
    }
    }
}

/** How far Home's shelves run past their column's end to the screen's
 *  edge: the column's own padding there and the safe area's. */
private val ShelfRun = 16.dp + SafeArea.Horizontal

/** A Home shelf brings a focused card into view inside the safe area -
 *  [ShelfRun]'s safe part and the room a ring needs clear of its end. */
@Composable
private fun ShelfToScreenEdge(content: @Composable () -> Unit) =
    ScrollsInSafeArea(trailing = SafeArea.Horizontal + ChinoFocus.Room, content = content)

@Composable
internal fun SeeAllCard(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ChinoCard(
        onClick = onClick,
        modifier = modifier.width(180.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .background(ChinoSurface)
                .border(1.dp, ChinoBorder, RectangleShape),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Lucide.ChevronRight,
                contentDescription = null,
                tint = ChinoAccent,
                modifier = Modifier.size(32.dp),
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "see all",
                style = MaterialTheme.typography.titleSmall,
                color = ChinoAccent,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
internal fun PosterCard(
    item: Item,
    posterUrl: String,
    onItemSelected: (String) -> Unit,
    onLongPress: () -> Unit = {},
    /** In the Watchlist: its bookmark, top-left (docs/chino, title actions:
     *  one icon and one colour on buttons and on cards alike). */
    saved: Boolean = false,
    focus: ItemFocus = rememberItemFocus(),
    modifier: Modifier = Modifier,
) {
    // Card body: poster on top in 2:3, meta strip below with title + year.
    // Matches chino-web MediaCard layout — title outside the poster, not
    // overlaid, so the artwork reads cleanly without a gradient scrim.
    // Fixed 180dp width because the parent is a TvLazyRow (a horizontal
    // shelf); the previous 6-col grid measured cards via the grid's
    // column-width.
    val focused = focus.focused
    ChinoCard(
        onClick = { onItemSelected(item.id) },
        // Hold-OK opens the card actions menu (web MediaCard overflow parity).
        onLongClick = onLongPress,
        modifier = modifier.width(180.dp),
        focus = focus,
    ) {
        Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                PosterImage(
                    model = posterUrl,
                    title = item.title,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // The Watchlist's bookmark top-left, dark on blue; the watched
                // check top-right. Server stamps watched_at when the user has
                // finished an item; non-null means "show the check", dark on
                // green as everywhere.
                if (saved) WatchlistMark(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (item.watchedAt != null) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
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
                    color = ChinoFocus.title(MaterialTheme.colorScheme.onSurface, focused),
                    maxLines = 1,
                )
                // Year · rating, both in muted style — matches chino-web
                // MediaCard's small bodySm meta row.
                val meta = ChinoFocus.text(MaterialTheme.colorScheme.onSurfaceVariant, focused)
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.year?.let { y ->
                        Text(
                            text = y.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = meta,
                        )
                    }
                    item.rating?.let { r ->
                        if (item.year != null) {
                            Text(
                                text = "•",
                                style = MaterialTheme.typography.bodySmall,
                                color = meta,
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
                        text = cardMenuHint(item),
                        style = MaterialTheme.typography.bodySmall,
                        color = ChinoFocus.text(ChinoMuted, focused),
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

/** What a focused card says of its menu: its options where it holds more
 *  than the watched toggle (a movie's play), else that toggle. */
internal const val CARD_OPTIONS_HINT = "Hold OK for options"

internal fun cardMenuHint(item: Item): String = when {
    item.kind == "movie" -> CARD_OPTIONS_HINT
    item.watchedAt != null -> "Hold OK to mark unwatched"
    else -> "Hold OK to mark watched"
}

/** A card menu's way off Continue watching (chino-web's card menu). */
internal const val REMOVE_FROM_CONTINUE_WATCHING = "remove from continue watching"

/** A card menu's play: "resume" where the card has a position to go on
 *  from - its bar shows it, so the label names none - else "play". */
internal fun playCardAction(resumes: Boolean, onPlay: () -> Unit): CardAction =
    CardAction(icon = Lucide.Play, label = if (resumes) "resume" else "play", onClick = onPlay)

/** The watched toggle of a card's menu, in the title page's words: "mark as
 *  watched", and "watched" while it is (docs/chino, title actions:
 *  [watchedName]), with watched's one icon, the check. `watched` is the
 *  item's CURRENT state (watchedAt != null); `onToggle` receives the NEXT
 *  desired state (true = mark watched). Reused on every card surface so
 *  wording/behaviour stay identical. */
internal fun watchedCardAction(watched: Boolean, onToggle: (Boolean) -> Unit): CardAction =
    CardAction(
        icon = Lucide.Check,
        label = cloud.nalet.chino.tv.ui.detail.watchedName(watched),
        onClick = { onToggle(!watched) },
    )

/** A poster card's menu: play where [onPlay] is given and the card is a
 *  movie's - a show's page works out its episode - then the watched toggle. */
internal fun cardActions(item: Item, onPlay: ((String) -> Unit)?, watched: Boolean, onToggleWatched: (Boolean) -> Unit): List<CardAction> =
    buildList {
        if (onPlay != null && item.kind == "movie") add(playCardAction(resumes = false) { onPlay(item.id) })
        add(watchedCardAction(watched, onToggleWatched))
    }

/** How narrow a card menu gets, for a short title and short labels. */
internal val CARD_MENU_MIN_WIDTH = 320.dp

/** A card menu's widest: past it, a label wraps rather than being cut. */
internal val CARD_MENU_MAX_WIDTH = 560.dp

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
            // A tap on it outside the menu closes it; it is no stop of the
            // focus - a clickable would be one, the parent of the rows, which
            // the remote's BACK leaves the rows for before it closes the menu.
            .scrim()
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        // Its rows set in from its sides, so a focused row's growth stays
        // inside it, as in every menu (ChoiceMenu). As wide as its longest
        // label, so none is cut ("remove from continue watching"); past its
        // widest a label wraps. The focus stays inside while it is open.
        Column(
            modifier = Modifier
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .widthIn(min = CARD_MENU_MIN_WIDTH, max = CARD_MENU_MAX_WIDTH)
                .width(IntrinsicSize.Max)
                .background(ChinoSurface)
                .border(1.dp, ChinoBorder, RectangleShape)
                // Swallow scrim taps that land on the menu itself.
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = 4.dp),
        ) {
            // Title row — the item being acted on, muted like the player menu
            // header so it reads as a label not an action.
            Text(
                text = title,
                color = ChinoMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
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
    val focus = rememberItemFocus()
    val fg = when {
        action.destructive -> ChinoError
        else -> ChinoText
    }
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            // Set in from the menu's sides by more than a focused row grows
            // (1 % each side), its text where the title's is.
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onSelected(); true
                } else false
            }
            .clickable(onClick = onSelected)
            .padding(horizontal = 10.dp, vertical = 12.dp),
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
            // At rest fg-2, fg on focus; a destructive one's red label is fg
            // while focused, its icon red. A menu item: the mono face,
            // lowercase, as every menu's.
            color = if (action.destructive) ChinoFocus.text(fg, focus.focused) else ChinoFocus.title(fg, focus.focused),
            fontFamily = cloud.nalet.chino.tv.ui.theme.ChinoMonoFamily,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            // Never cut: the menu is as wide as its longest label, and one
            // past the menu's widest wraps.
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

@Composable
private fun CenterText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    // A button: the avatar is its face. One focus target, the clickable — a
    // focusable() before it was a second one that took the first OK.
    val focus = rememberItemFocus()
    Box(
        modifier = Modifier
            .size(36.dp)
            .focusStyle(focus, FocusKind.Control)
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
