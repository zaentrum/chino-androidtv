package cloud.nalet.chino.tv.ui.browse

import cloud.nalet.chino.tv.ui.theme.ChinoFg
import cloud.nalet.chino.tv.ui.theme.ChinoPage
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.PosterImage
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.ChinoCard
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.ItemFocus
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.shelfPadding
import cloud.nalet.chino.tv.ui.theme.shelfRoom
import cloud.nalet.chino.tv.ui.theme.LoadError
import cloud.nalet.chino.tv.ui.theme.ReturnFocus
import cloud.nalet.chino.tv.ui.theme.rememberReturnFocus
import cloud.nalet.chino.tv.ui.theme.requestSoon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.library.CardAction
import cloud.nalet.chino.tv.ui.library.CardMenuOverlay
import cloud.nalet.chino.tv.ui.library.CardMenuRequest
import cloud.nalet.chino.tv.ui.library.besidesOf
import cloud.nalet.chino.tv.ui.library.FilterChip
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.library.cardActions
import cloud.nalet.chino.tv.ui.theme.WatchedMark
import cloud.nalet.chino.tv.ui.theme.WatchlistMark

@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    pageTitle: String,
    type: String,
    onItemSelected: (String) -> Unit,
    /** A movie card menu's play. */
    onPlay: (String) -> Unit = {},
    onHomeNav: () -> Unit,
    onMoviesNav: () -> Unit,
    onSeriesNav: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onWatchlist: () -> Unit,
    onZapNav: () -> Unit,
    onAccountClick: () -> Unit,
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    // Hold-OK on any grid card opens the shared card-actions menu (the same
    // scrim + #161B22 popover the Home rails use). Hosted here above the whole
    // page so its scrim dims the rail + topbar + grid; null = closed.
    var activeCardMenu by remember { mutableStateOf<CardMenuRequest?>(null) }
    // The focus: on the rail's item of this page as it opens from the rail,
    // back on the card that opened a title's page as it comes back from it,
    // and on the first chip once what a try again loaded takes its place -
    // never where the platform would put it when the try again goes, the
    // rail.
    val railFocus = remember { FocusRequester() }
    val firstChip = remember { FocusRequester() }
    val back = rememberReturnFocus()
    var retried by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!back.restore()) requestSoon(railFocus)
    }
    LaunchedEffect(state) {
        if (retried && state is BrowseUiState.Ready) {
            retried = false
            requestSoon(firstChip)
        }
    }
    ChinoPage {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                TvSideRail(
                    activeType = type,
                    onHome = onHomeNav,
                    onMovies = onMoviesNav,
                    onSeries = onSeriesNav,
                    onWatchlist = onWatchlist,
                    onZap = onZapNav,
                    onSettings = onSettings,
                    activeFocus = railFocus,
                )
                Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    TvTopBar(
                        onSearch = onSearch,
                        noticesBell = noticesBell,
                        activeAccount = activeAccount,
                        onAccountClick = onAccountClick,
                    )
                    when (val s = state) {
                        BrowseUiState.Loading -> Centered(pageTitle, loading = true)
                        is BrowseUiState.Error -> LoadError(
                            title = browseLoadErrorTitle(pageTitle),
                            failure = s.failure,
                            onRetry = { retried = true; viewModel.retry() },
                        )
                        is BrowseUiState.Ready -> BrowseGrid(
                            s = s,
                            pageTitle = pageTitle,
                            type = type,
                            savedItems = savedItems,
                            onItemSelected = onItemSelected,
                            onFilters = viewModel::setFilters,
                            onLoadMore = viewModel::loadMore,
                            onShowMenu = { activeCardMenu = it },
                            onToggleWatched = viewModel::toggleWatched,
                            onPlay = { id -> back.opens(id); onPlay(id) },
                            returnFocus = back,
                            firstChip = firstChip,
                        )
                    }
                }
            }
            // Closing it gives the focus back to the card that opened it, or
            // the one beside it where a pick took it away, else the first
            // chip - never the rail's Home.
            CardMenuOverlay(
                request = activeCardMenu,
                onClose = { activeCardMenu = null },
                fallback = firstChip,
            )
        }
    }
}

@Composable
private fun BrowseGrid(
    s: BrowseUiState.Ready,
    pageTitle: String,
    type: String,
    savedItems: Set<String>,
    onItemSelected: (String) -> Unit,
    onFilters: (BrowseFilters) -> Unit,
    onLoadMore: () -> Unit,
    onShowMenu: (CardMenuRequest) -> Unit = {},
    onToggleWatched: (itemId: String, markWatched: Boolean) -> Unit = { _, _ -> },
    onPlay: (String) -> Unit = {},
    returnFocus: ReturnFocus? = null,
    firstChip: FocusRequester? = null,
) {
    val gridState = rememberLazyGridState()
    LaunchedEffect(s.hasMore, s.items.size) {
        snapshotFlow {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= (gridState.layoutInfo.totalItemsCount - 12)
        }.collect { nearEnd -> if (nearEnd && s.hasMore && !s.loadingMore) onLoadMore() }
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                // The page's title: chrome, lowercase, in the mono face.
                text = pageTitle.lowercase(),
                style = MaterialTheme.typography.headlineMedium,
                color = ChinoFg,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FilterBar(filters = s.filters, genres = s.genres, onChange = onFilters, firstChip = firstChip)
        }
        if (s.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No ${if (type == "movie") "movies" else "shows"} match the current filters.",
                    color = ChinoMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(s.items, key = { it.id }) { item ->
            PosterGridCard(
                item = item,
                posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                onClick = { returnFocus?.opens(item.id); onItemSelected(item.id) },
                modifier = returnFocus?.let { Modifier.focusRequester(it.requester(item.id)) } ?: Modifier,
                saved = item.id in savedItems,
                // Hold-OK opens the shared actions menu: a movie's play, the
                // watched toggle. Browse is not unwatched-filtered, so marking
                // watched flips the card's green ✓ badge in place (handled in
                // the VM).
                onLongPress = {
                    val watched = item.watchedAt != null
                    onShowMenu(
                        CardMenuRequest(
                            title = item.title,
                            actions = cardActions(item, onPlay, watched) { next -> onToggleWatched(item.id, next) },
                            opener = returnFocus?.requester(item.id),
                            besides = returnFocus?.let { r ->
                                besidesOf(s.items.indexOf(item), s.items.size) { i -> r.requester(s.items[i].id) }
                            }.orEmpty(),
                        ),
                    )
                },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            val plural = if (type == "movie") "movies" else "shows"
            Text(
                text = if (s.hasMore) "Loading more…" else "You've reached the end — ${s.items.size} $plural.",
                color = ChinoMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun FilterBar(
    filters: BrowseFilters,
    genres: List<String>,
    onChange: (BrowseFilters) -> Unit,
    /** On "all genres", the page's first control. */
    firstChip: FocusRequester? = null,
) {
    // Chip rows: their chips far enough apart for a focused one's growth and
    // ring, and room at the rows' ends (shelfRoom).
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Genre row — "all genres" clears the genre; tapping the active genre
        // clears it. The chips are in the mono face: their own words
        // lowercase, the server's genre names in their case.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
            contentPadding = shelfPadding(),
            modifier = Modifier.shelfRoom(),
        ) {
            item("g-all") {
                FilterChip(
                    "all genres",
                    filters.genre == null,
                    modifier = if (firstChip != null) Modifier.focusRequester(firstChip) else Modifier,
                ) { onChange(filters.copy(genre = null)) }
            }
            items(genres, key = { "g:$it" }) { g ->
                FilterChip(g, filters.genre == g) {
                    onChange(filters.copy(genre = if (filters.genre == g) null else g))
                }
            }
        }
        // Decade + rating row.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
            contentPadding = shelfPadding(),
            modifier = Modifier.shelfRoom(),
        ) {
            items(BROWSE_DECADES, key = { "d:${it.label}" }) { d ->
                FilterChip(d.label, filters.decade == d) {
                    onChange(filters.copy(decade = if (filters.decade == d) null else d))
                }
            }
            items(BROWSE_RATINGS, key = { "r:$it" }) { r ->
                FilterChip(String.format("%.1f+", r), filters.ratingMin == r) {
                    onChange(filters.copy(ratingMin = if (filters.ratingMin == r) null else r))
                }
            }
        }
        // Sort row.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.ControlGap),
            contentPadding = shelfPadding(),
            modifier = Modifier.shelfRoom(),
        ) {
            items(BROWSE_SORTS, key = { "s:${it.second}" }) { (label, value) ->
                FilterChip(label, filters.sort == value) { onChange(filters.copy(sort = value)) }
            }
            // "clear filters" — chino-web BrowseFilters parity: one action
            // resets genre / decade / rating / sort back to defaults instead
            // of toggling each chip off individually. Rendered at the END of
            // the filter rows ONLY while at least one axis deviates from the
            // defaults (data-class equality covers all four), styled as an
            // inactive chip to match web's muted-text affordance.
            if (filters != BrowseFilters()) {
                item("clear-filters") {
                    FilterChip("clear filters", active = false) { onChange(BrowseFilters()) }
                }
            }
        }
    }
}

/** Poster grid card shared by Browse and Watchlist so the two grids stay
 *  pixel-identical: 2:3 poster on #0D1117, the watched check, the Watchlist's
 *  bookmark when the item is in the Watchlist, title + year•rating on a
 *  #161B22 footer, a card in the focus style. */
@Composable
internal fun PosterGridCard(
    item: Item,
    posterUrl: String,
    onClick: () -> Unit,
    saved: Boolean = false,
    // Default Modifier = the card fills its grid cell (LazyVerticalGrid sizes
    // it). Horizontal shelves (the watchlist hub LazyRow) have NO width bound,
    // so they MUST pass a fixed width — otherwise fillMaxWidth below expands the
    // card to the whole viewport.
    modifier: Modifier = Modifier,
    // Hold-OK (long-press CENTER) opens the card actions menu. No-op default so
    // surfaces that don't host the menu (the Watchlist grid) keep plain cards.
    onLongPress: (() -> Unit)? = null,
    // "SxxEyy" overline for EPISODE entries saved to a list (watchlist hub +
    // MORE grid) — mirrors the Library CW card's episode composition so a
    // saved episode doesn't read as a bare series poster. Null (the default,
    // and every non-episode) hides the line.
    episodeBadge: String? = null,
    // A muted line under the year/rating naming someone's credit on the
    // title ("Director · Writer") — the Person filmography (web MediaCard's
    // `credit`). Null hides it.
    credit: String? = null,
    focus: ItemFocus = rememberItemFocus(),
) {
    val focused = focus.focused
    ChinoCard(
        onClick = onClick,
        onLongClick = onLongPress,
        modifier = modifier,
        focus = focus,
    ) {
        Column(modifier = Modifier.background(ChinoSurface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(ChinoBg),
            ) {
                PosterImage(
                    model = posterUrl,
                    title = item.title,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
                // The Watchlist's bookmark, dark on blue — top-LEFT so it never
                // collides with the watched check (top-right).
                if (saved) WatchlistMark(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (item.watchedAt != null) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val meta = ChinoFocus.text(ChinoMuted, focused)
                Text(
                    text = item.title,
                    color = ChinoFocus.title(ChinoText, focused),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Episode marker line — accent "SxxEyy" under the title, the
                // same composition the Library CW card uses for episodes.
                episodeBadge?.let {
                    Text(
                        text = it,
                        color = ChinoAccent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val ratingText = item.rating?.let { String.format("%.1f", it) }
                if (item.year != null || ratingText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.year?.let { Text(it.toString(), color = meta, fontSize = 14.sp) }
                        ratingText?.let {
                            if (item.year != null) Text("•", color = meta, fontSize = 14.sp)
                            Text(it, color = ChinoAccent, fontSize = 14.sp)
                        }
                    }
                }
                credit?.let {
                    Text(
                        text = it,
                        color = meta,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // Long-press discoverability hint — only on the focused card and
                // only where the menu is wired (Browse), so the hold-OK actions
                // menu is findable at 10ft (web MediaCard hover overflow).
                if (focused && onLongPress != null) {
                    Text(
                        text = cloud.nalet.chino.tv.ui.library.cardMenuHint(item),
                        color = meta,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Centered(text: String, loading: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (loading) "Loading $text…" else text,
            color = ChinoMuted,
            fontSize = 18.sp,
        )
    }
}

/** What a browse page that did not load is titled, in its own word
 *  (docs/chino, playback: when a page couldn't load): "couldn't load the
 *  movies". */
internal fun browseLoadErrorTitle(pageTitle: String): String = "couldn't load the ${pageTitle.lowercase()}"
