package cloud.nalet.chino.tv.ui.search

import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.PosterImage
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.ChinoCard
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.shelfPadding
import cloud.nalet.chino.tv.ui.theme.shelfRoom
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed as lazyItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.streamArtworkUrl
import cloud.nalet.chino.tv.ui.person.PersonPortrait
import cloud.nalet.chino.tv.ui.slots.SlotButtons
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cloud.nalet.chino.tv.ui.theme.WatchedMark
import cloud.nalet.chino.tv.ui.theme.WatchlistMark

@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onItemSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit = {},
    onHomeNav: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val q by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    val watchlist by viewModel.watchlist.collectAsState()
    val emptySlot by viewModel.emptySlot.collectAsState()
    val slotActionStates by viewModel.slotActionStates.collectAsState()
    SearchContent(
        query = q,
        state = state,
        watchlist = watchlist,
        emptySlot = emptySlot,
        slotActionStates = slotActionStates,
        onQueryChange = viewModel::onQueryChange,
        onSlotAction = viewModel::onSlotAction,
        onItemSelected = onItemSelected,
        onPersonSelected = onPersonSelected,
        onHomeNav = onHomeNav,
        onMoviesNav = onMoviesNav,
        onSeriesNav = onSeriesNav,
        onSettings = onSettings,
        onWatchlist = onWatchlist,
        onAccountClick = onAccountClick,
        noticesBell = noticesBell,
        activeAccount = activeAccount,
    )
}

/** The search screen as its [state] has it: the rail, the top bar with the
 *  live field, and what the search found. */
@Composable
internal fun SearchContent(
    query: String,
    state: SearchUiState,
    /** The Watchlist's ids: a card's bookmark. */
    watchlist: Set<String> = emptySet(),
    emptySlot: List<cloud.nalet.chino.tv.data.SlotButton>,
    slotActionStates: Map<String, cloud.nalet.chino.tv.ui.slots.SlotActionState>,
    onQueryChange: (String) -> Unit,
    onSlotAction: (cloud.nalet.chino.tv.data.SlotButton) -> Unit,
    onItemSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit = {},
    onHomeNav: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    noticesBell: cloud.nalet.chino.tv.ui.notices.NoticesBell? = null,
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val q = query
    // DOWN from the search field goes to the first row of what was found:
    // the first of the cast & crew where they show, else the first title;
    // with no match, the first of the addons' buttons under the message, if
    // any; else nowhere. Only LEFT leaves the field for the rail
    // (SearchInput). A requester on the first chip or card.
    val firstResultFocus = remember { FocusRequester() }
    val firstSlotFocus = remember { FocusRequester() }
    // Integrated into the app shell — rail + top bar — with the LIVE search
    // field hosted IN the top bar (web parity: the search input lives in the
    // header), instead of a standalone full-screen page.
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxSize()) {
            cloud.nalet.chino.tv.ui.library.TvSideRail(
                activeType = "search", // non-matching → no rail item highlighted
                onHome = onHomeNav,
                onMovies = onMoviesNav,
                onSeries = onSeriesNav,
                onWatchlist = onWatchlist,
                onSettings = onSettings,
            )
            Column(modifier = Modifier.fillMaxHeight().weight(1f)) {
                cloud.nalet.chino.tv.ui.library.TvTopBar(
                    noticesBell = noticesBell,
                    activeAccount = activeAccount,
                    onAccountClick = onAccountClick,
                    searchSlot = {
                        SearchInput(
                            query = q,
                            onChange = onQueryChange,
                            downTarget = when {
                                state is SearchUiState.Results && (state.people.isNotEmpty() || state.items.isNotEmpty()) ->
                                    firstResultFocus
                                state == SearchUiState.NoMatches && emptySlot.isNotEmpty() -> firstSlotFocus
                                else -> null
                            },
                        )
                    },
                )
                Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    when (val s = state) {
                        SearchUiState.Empty -> SearchMessage(
                            headline = "Search the library",
                            hint = "Type a movie or show title to look it up.",
                        )
                        SearchUiState.Searching -> SearchMessage(headline = "Searching for “$q”…")
                        SearchUiState.NoMatches -> SearchMessage(headline = "No results for “$q”") {
                            // The search.empty slot: what addons offer
                            // instead, as native buttons. An empty slot (a
                            // server with no addon) shows nothing.
                            if (emptySlot.isNotEmpty()) {
                                SlotButtons(
                                    buttons = emptySlot,
                                    actionStates = slotActionStates,
                                    onAction = onSlotAction,
                                    firstFocus = firstSlotFocus,
                                    modifier = Modifier.padding(top = 16.dp),
                                )
                            }
                        }
                        is SearchUiState.Error -> SearchMessage(
                            headline = "Search failed",
                            hint = s.message,
                            isError = true,
                        )
                        is SearchUiState.Results -> ResultsGrid(
                            s = s,
                            watchlist = watchlist,
                            query = q,
                            onItemSelected = onItemSelected,
                            onPersonSelected = onPersonSelected,
                            firstCardFocus = firstResultFocus,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchInput(query: String, onChange: (String) -> Unit, downTarget: FocusRequester? = null) {
    // BasicTextField + a styled wrapper Box gives us full control of the
    // focus border (Compose for TV's TextField is still alpha and doesn't
    // size cleanly on a 1920x1080 surface). Raw hex matches the sibling
    // LibraryScreen chrome; leading Lucide.Search mirrors mobile/web.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    // The field takes the focus style as a row does: its frame follows the
    // text field's focus.
    val focus = rememberItemFocus()
    val focusManager = LocalFocusManager.current
    // The text and where the cursor is: LEFT and RIGHT move the cursor, and
    // leave the field only from its start and its end.
    var field by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (field.text != query) field = TextFieldValue(query, TextRange(query.length))
    // Sized to match the top-bar search field (42dp, square, 16sp) so the
    // live input reads as the header search, like web.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .focusStyle(focus, FocusKind.Row, surface = ChinoSurface)
            .border(1.dp, ChinoBorder, RectangleShape)
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
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = field,
                onValueChange = {
                    field = it
                    if (it.text != query) onChange(it.text)
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(ChinoAccent),
                // The IME's "search" key jumps straight to the first result
                // (closes the keyboard). On a remote the soft keyboard captures
                // DPAD-down, so this is the reliable "done typing → browse" path;
                // DOWN from the field (keyboard closed) also lands there.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runCatching { downTarget?.requestFocus() } }),
                modifier = Modifier
                    .fillMaxWidth()
                    // The remote's keys before the text field takes them - it
                    // would move its cursor with every one: DOWN to the first
                    // row of what was found, or nowhere; UP nowhere; LEFT from
                    // the field's start to the rail, the only way there; RIGHT
                    // from its end on to the bell and the account.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val atStart = field.selection.collapsed && field.selection.start == 0
                        val atEnd = field.selection.collapsed && field.selection.end == field.text.length
                        when (e.key) {
                            Key.DirectionDown -> {
                                runCatching { downTarget?.requestFocus() }
                                true
                            }
                            Key.DirectionUp -> true
                            Key.DirectionLeft -> atStart && focusManager.moveFocus(FocusDirection.Left).let { true }
                            Key.DirectionRight -> atEnd && focusManager.moveFocus(FocusDirection.Right).let { true }
                            else -> false
                        }
                    }
                    .focusRequester(focusRequester),
            )
            if (query.isEmpty()) {
                Text(
                    text = "Search movies, shows…",
                    color = ChinoFocus.text(ChinoMuted, focus.focused),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    s: SearchUiState.Results,
    /** The Watchlist's ids: a card's bookmark. */
    watchlist: Set<String>,
    query: String,
    onItemSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit,
    firstCardFocus: FocusRequester? = null,
) {
    val count = s.items.size
    // DOWN from the search field lands on the first focusable element below it:
    // the first PEOPLE chip when there are people, otherwise the first title
    // card. The grid hosts the people row as a full-span header item so the
    // whole results view scrolls as one and stays in a single DPAD focus flow.
    val hasPeople = s.people.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        if (hasPeople) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PeopleSection(
                    people = s.people,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
                    onPersonSelected = onPersonSelected,
                    firstChipFocus = firstCardFocus,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = if (count == 1) "1 result for “$query”" else "$count results for “$query”",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (count == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No matching titles.",
                    color = ChinoMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        itemsIndexed(s.items, key = { _, it -> it.id }) { idx, item ->
            SearchCard(
                item = item,
                posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                onClick = { onItemSelected(item.id) },
                saved = item.id in watchlist,
                // First title card only takes the DOWN-from-input focus when
                // there are no people above it to receive it first.
                focusRequester = firstCardFocus.takeIf { idx == 0 && !hasPeople },
            )
        }
    }
}

/** Search "Cast & crew" people row — server-ranked matching people above the
 *  title grid. Each entry is a focusable chip: the person's portrait (their
 *  initials without one), the name, and "· N titles". OK opens the Person
 *  surface. Fixed-width cards in a LazyRow so DPAD focus stays sane. */
@Composable
private fun PeopleSection(
    people: List<cloud.nalet.chino.tv.data.api.Person>,
    baseUrl: String,
    streamToken: String,
    onPersonSelected: (String) -> Unit,
    firstChipFocus: FocusRequester? = null,
) {
    Column(
        modifier = Modifier.padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Cast & crew",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
            contentPadding = shelfPadding(end = 32.dp, top = 2.dp, bottom = 2.dp),
            modifier = Modifier.shelfRoom().fillMaxWidth(),
        ) {
            lazyItemsIndexed(
                people,
                key = { _, p -> p.id },
            ) { idx, person ->
                PersonChip(
                    person = person,
                    // The portrait route loads with the stream token, as posters do.
                    portraitUrl = person.profileUrl
                        ?.takeIf { person.hasProfile }
                        ?.let { streamArtworkUrl(baseUrl, it, streamToken) },
                    onClick = { onPersonSelected(person.id) },
                    focusRequester = firstChipFocus.takeIf { idx == 0 },
                )
            }
        }
    }
}

/** One focusable person chip: portrait (or initials) + name + "· N titles". */
@Composable
private fun PersonChip(
    person: cloud.nalet.chino.tv.data.api.Person,
    portraitUrl: String?,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .width(220.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
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
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PersonPortrait(
            name = person.name,
            url = portraitUrl,
            initialsSize = 16.sp,
            modifier = Modifier.size(48.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = person.name,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "· ${if (person.credits == 1) "1 title" else "${person.credits} titles"}",
                color = ChinoFocus.text(ChinoMuted, focus.focused),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchCard(item: Item, posterUrl: String, onClick: () -> Unit, saved: Boolean = false, focusRequester: FocusRequester? = null) {
    val focus = rememberItemFocus()
    ChinoCard(
        onClick = onClick,
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
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
                // The Watchlist's bookmark top-left and the watched check
                // top-right, the same marks as Browse's cards.
                if (saved) WatchlistMark(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (item.watchedAt != null) WatchedMark(Modifier.align(Alignment.TopEnd).padding(6.dp))
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    color = ChinoFocus.title(ChinoText, focus.focused),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val ratingText = item.rating?.let { String.format("%.1f", it) }
                if (item.year != null || ratingText != null) {
                    val meta = ChinoFocus.text(ChinoMuted, focus.focused)
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
            }
        }
    }
}

@Composable
private fun SearchMessage(
    headline: String,
    hint: String? = null,
    isError: Boolean = false,
    below: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = headline,
                color = if (isError) MaterialTheme.colorScheme.error else Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
            )
            hint?.let {
                Text(text = it, color = ChinoMuted, fontSize = 16.sp)
            }
            below()
        }
    }
}
