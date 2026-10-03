package cloud.nalet.chino.tv.ui.person

import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.ui.browse.PosterGridCard
import cloud.nalet.chino.tv.ui.detail.formatRoles
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import kotlinx.coroutines.launch

/**
 * Person / Filmography surface, chino-web's PersonPage on a TV. Rail +
 * top-bar shell (parity with Browse / Search); a header with the portrait
 * (initials without one), the name, the credit count, what the catalog knows
 * — known for, born (with the age) and where, died — and the biography, cut
 * to a few lines with a "Read more" that opens it whole; then a focusable
 * poster grid of the person's titles, rendered with the SHARED
 * [PosterGridCard], each naming the person's roles on it. OK on a card opens
 * that title's Detail; BACK returns to the previous surface (search results
 * or the originating Detail page).
 */
@Composable
fun PersonScreen(
    viewModel: PersonViewModel,
    onItemSelected: (String) -> Unit,
    onHomeNav: () -> Unit = {},
    onMoviesNav: () -> Unit = {},
    onSeriesNav: () -> Unit = {},
    onSearch: () -> Unit = {},
    onSettings: () -> Unit = {},
    onWatchlist: () -> Unit = {},
    onAccountClick: () -> Unit = {},
    activeAccount: Account? = null,
) {
    val state by viewModel.state.collectAsState()
    // True while the whole biography is up over the page; BACK closes it.
    var readingBiography by remember { mutableStateOf(false) }
    val readMoreFocus = remember { FocusRequester() }
    Surface(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                TvSideRail(
                    activeType = "person", // non-matching → no rail item highlighted
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
                        PersonUiState.Loading -> Centered("Loading…")
                        is PersonUiState.Error -> Centered("Couldn't load person: ${s.message}", isError = true)
                        is PersonUiState.Ready -> Filmography(
                            s = s,
                            onItemSelected = onItemSelected,
                            readMoreFocus = readMoreFocus,
                            onReadBiography = { readingBiography = true },
                        )
                    }
                }
            }
            val ready = state as? PersonUiState.Ready
            val biography = ready?.biography
            if (readingBiography && biography != null) {
                BiographyPanel(
                    name = ready.name,
                    text = biography,
                    onDismiss = {
                        readingBiography = false
                        // Back to the control that opened it, not the rail.
                        runCatching { readMoreFocus.requestFocus() }
                    },
                )
            }
        }
    }
}

@Composable
private fun Filmography(
    s: PersonUiState.Ready,
    onItemSelected: (String) -> Unit,
    readMoreFocus: FocusRequester,
    onReadBiography: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(6),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PersonHeader(s = s, readMoreFocus = readMoreFocus, onReadBiography = onReadBiography)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Filmography",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (s.items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "No titles for ${s.name}.",
                    color = ChinoMuted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        items(s.items, key = { it.id }) { item ->
            PosterGridCard(
                item = item,
                posterUrl = "${s.baseUrl}/v1/items/${item.id}/poster?stream=${s.streamToken}",
                onClick = { onItemSelected(item.id) },
                // The person's roles on this title: "Director · Writer".
                credit = formatRoles(item.roles).ifEmpty { null },
            )
        }
    }
}

/**
 * The header: the portrait — 2:3 like the posters below, or the initials in
 * a square for someone the catalog has no portrait of — beside the name, the
 * credit count, the facts and the biography.
 */
@Composable
private fun PersonHeader(
    s: PersonUiState.Ready,
    readMoreFocus: FocusRequester,
    onReadBiography: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PersonPortrait(
            name = s.name,
            url = s.portraitUrl,
            initialsSize = 34.sp,
            modifier = if (s.portraitUrl != null) {
                Modifier.width(176.dp).aspectRatio(2f / 3f)
            } else {
                Modifier.size(120.dp)
            },
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = s.name,
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = creditLabel(s.credits), color = ChinoMuted, fontSize = 16.sp)
            }
            if (s.facts.isNotEmpty()) FactsRow(s.facts)
            s.biography?.let { text ->
                Biography(text = text, readMoreFocus = readMoreFocus, onReadMore = onReadBiography)
            }
        }
    }
}

/** The facts side by side, wrapping onto a second line rather than running
 *  past the header when a birthplace is long. Display-only: no focus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FactsRow(facts: List<PersonFact>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        facts.forEach { fact -> FactColumn(fact) }
    }
}

/** One fact: its label over its lines ("Born" over the date and the place). */
@Composable
private fun FactColumn(fact: PersonFact) {
    Column(
        modifier = Modifier.widthIn(max = 320.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = fact.label, color = ChinoMuted, fontSize = 14.sp)
        fact.lines.forEach { line ->
            Text(text = line, color = ChinoText, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The biography, cut to [BIOGRAPHY_LINES] lines so the filmography stays in
 * view. When it is in fact cut, a focusable "Read more" opens the whole text
 * over the page — a long biography can't scroll inline on a TV, where focus,
 * not a pointer, moves the page.
 */
@Composable
private fun Biography(text: String, readMoreFocus: FocusRequester, onReadMore: () -> Unit) {
    var cut by remember(text) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = text,
            color = ChinoText,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            maxLines = BIOGRAPHY_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { cut = it.hasVisualOverflow },
        )
        if (cut) {
            Button(
                onClick = onReadMore,
                shape = ButtonDefaults.shape(shape = RectangleShape),
                modifier = Modifier.focusRequester(readMoreFocus),
            ) {
                Text(text = "Read more", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The whole biography over a dim scrim. The text box takes focus and DPAD
 * UP/DOWN scroll it (LEFT/RIGHT are held so focus can't leave the panel);
 * BACK closes it.
 */
@Composable
private fun BiographyPanel(name: String, text: String, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(760.dp)
                .heightIn(max = 470.dp)
                .background(ChinoSurface)
                .border(1.dp, ChinoBorder, RectangleShape)
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .focusRequester(focus)
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(BIOGRAPHY_SCROLL_PX) }; true }
                            Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-BIOGRAPHY_SCROLL_PX) }; true }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            else -> false
                        }
                    }
                    .focusable()
                    .verticalScroll(scroll),
            ) {
                Text(text = text, color = ChinoText, fontSize = 17.sp, lineHeight = 26.sp)
            }
            Text(
                text = if (scroll.maxValue > 0) "▲▼ to scroll · BACK to close" else "BACK to close",
                color = ChinoAccent,
                fontSize = 12.sp,
            )
        }
    }
}

/** How many lines of the biography the header shows. */
private const val BIOGRAPHY_LINES = 5

/** How far one DPAD press scrolls the open biography. */
private const val BIOGRAPHY_SCROLL_PX = 260f

/** "N titles" copy, singularising at 1. Matches the Search people-row label. */
internal fun creditLabel(credits: Int): String =
    if (credits == 1) "1 title" else "$credits titles"

/** Up to two initials from a person's name, e.g. "Greta Gerwig" -> "GG". */
internal fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { "?" }
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error else ChinoMuted,
            fontSize = 18.sp,
        )
    }
}
