package cloud.nalet.chino.tv.ui.browse

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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.composables.icons.lucide.Bookmark
import com.composables.icons.lucide.Lucide
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.library.CardAction
import cloud.nalet.chino.tv.ui.library.CardActionsMenu
import cloud.nalet.chino.tv.ui.library.FilterChip
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.library.watchedCardAction
import coil.compose.AsyncImage

@Composable
fun BrowseScreen(
    viewModel: BrowseViewModel,
    pageTitle: String,
    type: String,
    onItemSelected: (String) -> Unit,
    onHomeNav: () -> Unit,
    onMoviesNav: () -> Unit,
    onSeriesNav: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onWatchlist: () -> Unit,
    onAccountClick: () -> Unit,
    activeAccount: Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    // Hold-OK on any grid card opens the shared card-actions menu (the same
    // scrim + #161B22 popover the Home rails use). Hosted here above the whole
    // page so its scrim dims the rail + topbar + grid; null = closed.
    var activeCardMenu by remember { mutableStateOf<CardMenuRequest?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                TvSideRail(
                    activeType = type,
                    onHome = onHomeNav,
                    onMovies = onMoviesNav,
                    onSeries = onSeriesNav,
                    onWatchlist = onWatchlist,
                    onSettings = onSettings,
                )
                Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    TvTopBar(
                        onSearch = onSearch,
                        onWatchlist = onWatchlist,
                        activeAccount = activeAccount,
                        onAccountClick = onAccountClick,
                    )
                    when (val s = state) {
                        BrowseUiState.Loading -> Centered(pageTitle, loading = true)
                        is BrowseUiState.Error -> Centered("Couldn't load $pageTitle: ${s.message}", isError = true)
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
                        )
                    }
                }
            }
            activeCardMenu?.let { req ->
                CardActionsMenu(
                    title = req.title,
                    actions = req.actions,
                    onDismiss = { activeCardMenu = null },
                )
            }
        }
    }
}

/** What a grid card hands [BrowseScreen] to open the shared actions menu. */
private data class CardMenuRequest(val title: String, val actions: List<CardAction>)

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
                text = pageTitle,
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FilterBar(filters = s.filters, genres = s.genres, onChange = onFilters)
        }
        if (s.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No ${if (type == "movie") "movies" else "shows"} match the current filters.",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(s.items, key = { it.id }) { item ->
            PosterGridCard(
                item = item,
                posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                onClick = { onItemSelected(item.id) },
                saved = item.id in savedItems,
                // Hold-OK opens the shared actions menu with the watched toggle.
                // Browse is not unwatched-filtered, so marking watched flips the
                // card's green ✓ badge in place (handled in the VM).
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
        item(span = { GridItemSpan(maxLineSpan) }) {
            val plural = if (type == "movie") "movies" else "shows"
            Text(
                text = if (s.hasMore) "Loading more…" else "You've reached the end — ${s.items.size} $plural.",
                color = Color(0xFF8B949E),
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
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // Genre row — "All" clears the genre; tapping the active genre clears it.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item("g-all") {
                FilterChip("All genres", filters.genre == null) { onChange(filters.copy(genre = null)) }
            }
            items(genres, key = { "g:$it" }) { g ->
                FilterChip(g, filters.genre == g) {
                    onChange(filters.copy(genre = if (filters.genre == g) null else g))
                }
            }
        }
        // Decade + rating row.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BROWSE_SORTS, key = { "s:${it.second}" }) { (label, value) ->
                FilterChip(label, filters.sort == value) { onChange(filters.copy(sort = value)) }
            }
            // "Clear filters" — chino-web BrowseFilters parity: one action
            // resets genre / decade / rating / sort back to defaults instead
            // of toggling each chip off individually. Rendered at the END of
            // the filter rows ONLY while at least one axis deviates from the
            // defaults (data-class equality covers all four), styled as an
            // inactive chip to match web's muted-text affordance.
            if (filters != BrowseFilters()) {
                item("clear-filters") {
                    FilterChip("Clear filters", active = false) { onChange(BrowseFilters()) }
                }
            }
        }
    }
}

/** Poster grid card shared by Browse and Watchlist so the two grids stay
 *  pixel-identical: 2:3 poster on #0D1117, watched ✓ badge, a "saved" bookmark
 *  badge when the item is in >=1 list, title + year•rating on a #161B22 footer,
 *  1.05× focus scale via the native TV Card. */
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
) {
    var focused by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        onLongClick = onLongPress,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused },
    ) {
        Column(modifier = Modifier.background(Color(0xFF161B22))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .background(Color(0xFF0D1117)),
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // "Saved" bookmark badge — top-LEFT so it never collides with the
                // watched ✓ badge (top-right). Shown when the item is in any list.
                if (saved) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF1F6FEB))
                            .padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Lucide.Bookmark,
                            contentDescription = "Saved",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
                if (item.watchedAt != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFF2EA043))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(text = "✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    color = Color(0xFFC9D1D9),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ratingText = item.rating?.let { String.format("%.1f", it) }
                if (item.year != null || ratingText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        item.year?.let { Text(it.toString(), color = Color(0xFF8B949E), fontSize = 14.sp) }
                        ratingText?.let {
                            if (item.year != null) Text("•", color = Color(0xFF8B949E), fontSize = 14.sp)
                            Text(it, color = Color(0xFF58A6FF), fontSize = 14.sp)
                        }
                    }
                }
                // Long-press discoverability hint — only on the focused card and
                // only where the menu is wired (Browse), so the hold-OK actions
                // menu is findable at 10ft (web MediaCard hover overflow).
                if (focused && onLongPress != null) {
                    Text(
                        text = if (item.watchedAt != null) "Hold OK to mark unwatched" else "Hold OK to mark watched",
                        color = Color(0xFF8B949E),
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
private fun Centered(text: String, loading: Boolean = false, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (loading) "Loading $text…" else text,
            color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF8B949E),
            fontSize = 18.sp,
        )
    }
}
