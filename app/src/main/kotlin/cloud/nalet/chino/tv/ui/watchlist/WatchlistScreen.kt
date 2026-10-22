package cloud.nalet.chino.tv.ui.watchlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.episodeCode
import cloud.nalet.chino.tv.ui.browse.PosterGridCard
import cloud.nalet.chino.tv.ui.library.SeeAllCard
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.LoadError
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2

/**
 * The watchlist HUB — the cross-client lists surface (web WatchlistSection /
 * mobile WatchlistScreen parity). Hub = one horizontal shelf per list
 * (default first), each capped at [HUB_SHELF_CAP] posters + a See-all card;
 * OK on a shelf header or the See-all card opens the MORE view (the list's
 * full grid with rename/delete). BACK in MORE returns to the hub.
 */
@Composable
fun WatchlistScreen(
    viewModel: WatchlistViewModel,
    onItemSelected: (String) -> Unit,
    onHomeNav: () -> Unit,
    onMoviesNav: () -> Unit,
    onSeriesNav: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onWatchlist: () -> Unit,
    onAccountClick: () -> Unit,
    onZapNav: () -> Unit = {},
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: Account? = null,
) {
    val state by viewModel.state.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    val openListId by viewModel.openListId.collectAsState()
    val openItems by viewModel.openItems.collectAsState()
    val openItemsLoading by viewModel.openItemsLoading.collectAsState()
    // Modal overlay: the create / rename name field. BACK dismisses.
    var overlay by remember { mutableStateOf<ListOverlay?>(null) }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                TvSideRail(
                    activeType = "watchlist",
                    onHome = onHomeNav,
                    onMovies = onMoviesNav,
                    onSeries = onSeriesNav,
                    onSettings = onSettings,
                    onZap = onZapNav,
                    onWatchlist = onWatchlist,
                    watchlistActive = true,
                )
                Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                    TvTopBar(
                        onSearch = onSearch,
                        noticesBell = noticesBell,
                        activeAccount = activeAccount,
                        onAccountClick = onAccountClick,
                    )
                    when (val s = state) {
                        WatchlistUiState.Loading -> Centered("Loading your lists…")
                        is WatchlistUiState.Error -> LoadError(
                            title = "couldn't load your watchlists",
                            failure = s.failure,
                            onRetry = { viewModel.refresh() },
                        )
                        is WatchlistUiState.Ready -> {
                            val openList = s.lists.firstOrNull { it.id == openListId }
                            if (openList == null) {
                                HubContent(
                                    s = s,
                                    savedItems = savedItems,
                                    onItemSelected = onItemSelected,
                                    onOpenList = viewModel::openList,
                                    onNewList = { overlay = ListOverlay.Create },
                                )
                            } else {
                                MoreContent(
                                    list = openList,
                                    items = openItems,
                                    loading = openItemsLoading,
                                    baseUrl = s.baseUrl,
                                    streamToken = s.streamToken,
                                    savedItems = savedItems,
                                    onItemSelected = onItemSelected,
                                    onBack = viewModel::closeList,
                                    onRename = { overlay = ListOverlay.Rename(openList) },
                                    onDelete = { viewModel.deleteList(openList.id) },
                                )
                            }
                        }
                    }
                }
            }

            when (val ov = overlay) {
                null -> Unit
                ListOverlay.Create -> NameDialog(
                    title = "New list",
                    initial = "",
                    onSubmit = { name -> if (name.isNotBlank()) viewModel.createList(name); overlay = null },
                    onDismiss = { overlay = null },
                )
                is ListOverlay.Rename -> NameDialog(
                    title = "Rename list",
                    initial = ov.list.name,
                    onSubmit = { name -> if (name.isNotBlank()) viewModel.renameList(ov.list.id, name); overlay = null },
                    onDismiss = { overlay = null },
                )
            }
        }
    }
}

/** "SxxEyy" (2-digit padded) for EPISODE items saved to a list — the same
 *  composition the Library CW card uses. Null for movies/series or when the
 *  numbers are missing, which hides the card's episode line. */
private fun episodeBadgeFor(item: Item): String? =
    if (item.kind == "episode" && item.seasonNumber != null && item.episodeNumber != null) {
        episodeCode(item.seasonNumber, item.episodeNumber, item.episodeEnd)
    } else null

/** The modal shown over the lists surface. */
private sealed interface ListOverlay {
    data object Create : ListOverlay
    data class Rename(val list: Watchlist) : ListOverlay
}

/** Hub: title + New-list chip, then one shelf per list. The whole column
 *  scrolls with focus (the Settings screen idiom). */
@Composable
private fun HubContent(
    s: WatchlistUiState.Ready,
    /** The Watchlist's ids: a card's bookmark. */
    savedItems: Set<String>,
    onItemSelected: (String) -> Unit,
    onOpenList: (String) -> Unit,
    onNewList: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Your lists",
                color = Color.White,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.weight(1f))
            NewListChip(onClick = onNewList)
        }
        s.lists.forEach { list ->
            ShelfHeader(list = list, onOpen = { onOpenList(list.id) })
            val shelf = s.shelves[list.id]
            when {
                shelf == null -> ShelfHint("Loading…")
                shelf.isEmpty() -> ShelfHint(
                    if (list.isDefault) "Add titles with the bookmark on a movie's or a show's page."
                    else "Add titles with the arrow beside the bookmark on a movie's or a show's page.",
                )
                else -> LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    items(shelf, key = { it.id }) { item ->
                        PosterGridCard(
                            item = item,
                            posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                            onClick = { onItemSelected(item.id) },
                            // The bookmark is the Watchlist's, on any list's shelf.
                            saved = item.id in savedItems,
                            // Saved EPISODES get the SxxEyy line under the title
                            // (Library CW card parity) so they don't read as the
                            // whole series.
                            episodeBadge = episodeBadgeFor(item),
                            // Fixed shelf-card width (matches the home-rail PosterCard);
                            // a LazyRow gives no width bound, so the grid card's
                            // fillMaxWidth would otherwise blow up to full screen.
                            modifier = Modifier.width(180.dp),
                        )
                    }
                    item(key = "__see_all__") {
                        SeeAllCard(onClick = { onOpenList(list.id) })
                    }
                }
            }
        }
    }
}

/** Focusable shelf header — "Name · count". OK opens the list's MORE view. */
@Composable
private fun ShelfHeader(list: Watchlist, onOpen: () -> Unit) {
    // A plain button that names the list.
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
            .focusStyle(focus, FocusKind.Control, surface = Color.Transparent)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onOpen(); true
                } else false
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = list.name,
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "· ${list.itemCount}",
            color = ChinoFocus.text(ChinoMuted, focus.focused),
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ShelfHint(text: String) {
    Text(
        text = text,
        color = ChinoMuted,
        fontSize = 14.sp,
        modifier = Modifier.padding(start = 34.dp, bottom = 16.dp),
    )
}

/** MORE view — the full grid for one list, with rename/delete (non-default)
 *  and BACK returning to the hub. */
@Composable
private fun MoreContent(
    list: Watchlist,
    items: List<Item>,
    loading: Boolean,
    baseUrl: String,
    streamToken: String,
    savedItems: Set<String>,
    onItemSelected: (String) -> Unit,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeaderIconButton(icon = Lucide.ArrowLeft, label = "Back", onClick = onBack)
            Text(
                text = list.name,
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "· ${list.itemCount}",
                color = ChinoMuted,
                fontSize = 18.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!list.isDefault) {
                HeaderIconButton(icon = Lucide.Pencil, label = "Rename", onClick = onRename)
                HeaderIconButton(icon = Lucide.Trash2, label = "Delete", tint = ChinoError, onClick = onDelete)
            } else {
                // The default list can be renamed but never deleted.
                HeaderIconButton(icon = Lucide.Pencil, label = "Rename", onClick = onRename)
            }
        }
        when {
            loading -> Centered("Loading…")
            items.isEmpty() -> Centered("“${list.name}” is empty. Save titles with the + button on any movie or show.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.fillMaxSize(),
                // Room above the first row for a focused card's growth and ring.
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = ChinoFocus.Room, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    PosterGridCard(
                        item = item,
                        posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                        onClick = { onItemSelected(item.id) },
                        saved = item.id in savedItems,
                        // Saved EPISODES get the SxxEyy line under the title
                        // (Library CW card parity) so they don't read as the
                        // whole series.
                        episodeBadge = episodeBadgeFor(item),
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "${items.size} saved",
                        color = ChinoMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        }
    }
}

/** Small focusable header action — back / rename / delete in the MORE view. */
@Composable
private fun HeaderIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit,
) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .height(40.dp)
            .focusStyle(focus, FocusKind.Control, surface = ChinoSurface)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        // Delete's red label is fg while focused, its icon red.
        Text(text = label, color = ChinoFocus.text(tint, focus.focused), fontSize = 14.sp)
    }
}

@Composable
private fun NewListChip(onClick: () -> Unit) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .height(40.dp)
            .focusStyle(focus, FocusKind.Control, surface = ChinoSurface)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Lucide.Plus, contentDescription = "New list", tint = ChinoAccent, modifier = Modifier.size(16.dp))
        Text(text = "New list", color = ChinoFocus.title(ChinoText, focus.focused), fontSize = 14.sp)
    }
}

/** Modal name field for create / rename — the on-screen IME handles D-pad text
 *  entry; the IME "Done" key submits, BACK cancels. Mirrors the SearchScreen /
 *  ServerSetup input style. */
@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var value by remember { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // The field takes the focus style as a row does.
    val focus = rememberItemFocus()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RectangleShape)
                .background(ChinoSurface)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .focusStyle(focus, FocusKind.Row, surface = ChinoBg)
                    .border(1.dp, ChinoBorder, RectangleShape)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= 60) value = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(ChinoAccent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmit(value.trim()) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                    if (value.isEmpty()) {
                        Text(text = "List name…", color = ChinoFocus.text(ChinoMuted, focus.focused), fontSize = 16.sp)
                    }
                }
            }
            Text(
                text = "Press the on-screen Done key to save, BACK to cancel.",
                color = ChinoMuted,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Centered(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = ChinoMuted,
            fontSize = 18.sp,
        )
    }
}
