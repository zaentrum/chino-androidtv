package cloud.nalet.chino.tv.ui.search

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
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
import cloud.nalet.chino.tv.data.model.Item
import coil.compose.AsyncImage
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Search

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
    activeAccount: cloud.nalet.chino.tv.data.auth.Account? = null,
) {
    val q by viewModel.query.collectAsState()
    val state by viewModel.state.collectAsState()
    // DOWN from the search field jumps straight to the first result card
    // (not the rail). Wired via focusProperties on the input + a requester on
    // the first card — only while results are showing.
    val firstResultFocus = remember { FocusRequester() }
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
                    onWatchlist = onWatchlist,
                    activeAccount = activeAccount,
                    onAccountClick = onAccountClick,
                    searchSlot = {
                        SearchInput(
                            query = q,
                            onChange = viewModel::onQueryChange,
                            downTarget = firstResultFocus.takeIf { state is SearchUiState.Results },
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
                        SearchUiState.NoMatches -> SearchMessage(headline = "No results for “$q”")
                        is SearchUiState.Error -> SearchMessage(
                            headline = "Search failed",
                            hint = s.message,
                            isError = true,
                        )
                        is SearchUiState.Results -> ResultsGrid(
                            s = s,
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
    var focused by remember { mutableStateOf(false) }
    // Sized to match the top-bar search field (42dp / 8dp radius / 16sp) so the
    // live input reads as the header search, like web.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161B22))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF58A6FF) else Color(0xFF30363D),
                shape = RoundedCornerShape(8.dp),
            )
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
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = query,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                // The IME's "search" key jumps straight to the first result
                // (closes the keyboard). On a remote the soft keyboard captures
                // DPAD-down, so this is the reliable "done typing → browse" path;
                // DOWN from the field (keyboard closed) also lands there.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runCatching { downTarget?.requestFocus() } }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (downTarget != null) Modifier.focusProperties { down = downTarget }
                        else Modifier,
                    )
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (query.isEmpty()) {
                Text(
                    text = "Search movies, shows…",
                    color = Color(0xFF8B949E),
                    fontSize = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun ResultsGrid(
    s: SearchUiState.Results,
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
                    color = Color(0xFF8B949E),
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
                // First title card only takes the DOWN-from-input focus when
                // there are no people above it to receive it first.
                focusRequester = firstCardFocus.takeIf { idx == 0 && !hasPeople },
            )
        }
    }
}

/** Search "Cast & crew" people row — server-ranked matching people above the
 *  title grid. Each entry is a focusable chip: an initials-avatar placeholder
 *  (no person photos exist), the name, and "· N titles". OK opens the Person
 *  surface. Fixed-width cards in a LazyRow so DPAD focus stays sane. */
@Composable
private fun PeopleSection(
    people: List<cloud.nalet.chino.tv.data.api.Person>,
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
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 32.dp, top = 2.dp, bottom = 2.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            lazyItemsIndexed(
                people,
                key = { _, p -> p.id },
            ) { idx, person ->
                PersonChip(
                    person = person,
                    onClick = { onPersonSelected(person.id) },
                    focusRequester = firstChipFocus.takeIf { idx == 0 },
                )
            }
        }
    }
}

/** One focusable person chip: initials avatar + name + "· N titles". */
@Composable
private fun PersonChip(
    person: cloud.nalet.chino.tv.data.api.Person,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .width(220.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (focused) Color(0xFF21262D) else Color(0xFF161B22))
        .then(
            if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), RoundedCornerShape(10.dp))
            else Modifier,
        )
        .padding(12.dp)
    val m = if (focusRequester != null) base.focusRequester(focusRequester) else base
    Row(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                ) {
                    onClick(); true
                } else false
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D1117)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = peopleInitialsOf(person.name),
                color = Color(0xFFC9D1D9),
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
            )
        }
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
                color = Color(0xFF8B949E),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Up to two initials from a person's name, e.g. "Greta Gerwig" -> "GG". */
private fun peopleInitialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { "?" }
}

@Composable
private fun SearchCard(item: Item, posterUrl: String, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
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
                // Watched ✓ badge — emerald, same as PosterCard (tv-ahead of
                // mobile, at web parity).
                if (item.watchedAt != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .clip(CircleShape)
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
            }
        }
    }
}

@Composable
private fun SearchMessage(headline: String, hint: String? = null, isError: Boolean = false) {
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
                Text(text = it, color = Color(0xFF8B949E), fontSize = 16.sp)
            }
        }
    }
}
