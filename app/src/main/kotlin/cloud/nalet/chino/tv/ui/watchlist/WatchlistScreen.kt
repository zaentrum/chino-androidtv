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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
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
import cloud.nalet.chino.tv.ui.browse.PosterGridCard
import cloud.nalet.chino.tv.ui.library.SeeAllCard
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
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
                        onWatchlist = onWatchlist,
                        activeAccount = activeAccount,
                        onAccountClick = onAccountClick,
                    )
                    when (val s = state) {
                        WatchlistUiState.Loading -> Centered("Loading your lists…")
                        is WatchlistUiState.Error -> Centered("Couldn't load watchlists: ${s.message}", isError = true)
                        is WatchlistUiState.Ready -> {
                            val openList = s.lists.firstOrNull { it.id == openListId }
                            if (openList == null) {
                                HubContent(
                                    s = s,
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
                shelf.isEmpty() -> ShelfHint("Save titles with the + button on any movie or show.")
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
                            saved = true, // everything on a watchlist shelf is saved by definition
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
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (focused) Color(0xFF21262D) else Color.Transparent)
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(8.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
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
            color = Color(0xFF8B949E),
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun ShelfHint(text: String) {
    Text(
        text = text,
        color = Color(0xFF8B949E),
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
                color = Color(0xFF8B949E),
                fontSize = 18.sp,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (!list.isDefault) {
                HeaderIconButton(icon = Lucide.Pencil, label = "Rename", onClick = onRename)
                HeaderIconButton(icon = Lucide.Trash2, label = "Delete", tint = Color(0xFFE5534B), onClick = onDelete)
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
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    PosterGridCard(
                        item = item,
                        posterUrl = "$baseUrl/v1/items/${item.id}/poster?stream=$streamToken",
                        onClick = { onItemSelected(item.id) },
                        saved = item.id in savedItems,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = "${items.size} saved",
                        color = Color(0xFF8B949E),
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
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) Color(0xFF21262D) else Color(0xFF161B22))
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(999.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
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
        Text(text = label, color = tint, fontSize = 14.sp)
    }
}

@Composable
private fun NewListChip(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .height(40.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) Color(0xFF21262D) else Color(0xFF161B22))
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(999.dp))
                else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
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
        Icon(Lucide.Plus, contentDescription = "New list", tint = Color(0xFF58A6FF), modifier = Modifier.size(16.dp))
        Text(text = "New list", color = Color(0xFFC9D1D9), fontSize = 14.sp)
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
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0D1117))
                    .border(
                        width = if (focused) 2.dp else 1.dp,
                        color = if (focused) Color(0xFF58A6FF) else Color(0xFF30363D),
                        shape = RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    BasicTextField(
                        value = value,
                        onValueChange = { if (it.length <= 60) value = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                        cursorBrush = SolidColor(Color(0xFF58A6FF)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onSubmit(value.trim()) }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focused = it.isFocused },
                    )
                    if (value.isEmpty()) {
                        Text(text = "List name…", color = Color(0xFF8B949E), fontSize = 16.sp)
                    }
                }
            }
            Text(
                text = "Press the on-screen Done key to save, BACK to cancel.",
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else Color(0xFF8B949E),
            fontSize = 18.sp,
        )
    }
}
