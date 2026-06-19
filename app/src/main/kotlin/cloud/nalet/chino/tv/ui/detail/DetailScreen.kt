package cloud.nalet.chino.tv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.Trailer
import coil.compose.AsyncImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Youtube
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onPlay: (itemId: String, resume: Boolean) -> Unit,
    onPlayEpisode: (String) -> Unit = { id -> onPlay(id, false) },
    onSimilarSelected: (String) -> Unit = {},
    onPersonSelected: (String) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val savedItems by viewModel.savedItems.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val memberships by viewModel.memberships.collectAsState()
    val likes by viewModel.likes.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // When true, the add-to-list picker overlays the page. Opened by the caret
    // next to the "+" icon; BACK closes it.
    var showListPicker by remember { mutableStateOf(false) }
    // resolving=true while resolvePlayTarget is in flight. Drives the Play
    // CTA's "Loading…" label and gates re-clicks so the user can't queue
    // multiple navigations during the ~50-500ms /next-episode round-trip.
    var resolving by remember { mutableStateOf(false) }
    val playResolved: (Boolean) -> Unit = { resume ->
        if (!resolving) {
            resolving = true
            scope.launch {
                try {
                    val target = viewModel.resolvePlayTarget()
                    onPlay(target, resume)
                } finally {
                    resolving = false
                }
            }
        }
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            DetailUiState.Loading -> Centered("Loading…")
            is DetailUiState.Error -> Centered("Could not load item: ${s.message}", isError = true)
            is DetailUiState.Ready -> DetailContent(
                s = s,
                inWatchlist = viewModel.itemId in savedItems,
                liked = viewModel.itemId in likes,
                isResolvingPlay = resolving,
                onPlay = playResolved,
                onToggleWatchlist = { viewModel.toggleWatchlist(it) },
                onOpenListPicker = { showListPicker = true },
                onToggleLike = { viewModel.toggleLike(it) },
                onToggleWatched = { viewModel.toggleWatched() },
                onToggleEpisodeWatched = { id, watched -> viewModel.toggleEpisodeWatched(id, watched) },
                onTrailerLaunch = { viewModel.reportTrailerLaunch() },
                onPlayEpisode = onPlayEpisode,
                onSimilarSelected = onSimilarSelected,
                onPersonSelected = onPersonSelected,
            )
        }
        // Add-to-list picker — floats over the page (player MenuPopover idiom).
        // BACK closes it; toggling a row calls PUT/DELETE items optimistically.
        if (showListPicker && state is DetailUiState.Ready) {
            AddToListPanel(
                lists = lists,
                memberItemId = viewModel.itemId,
                memberships = memberships,
                onToggle = { listId, present -> viewModel.setItemInList(listId, present) },
                onCreate = { name -> scope.launch { viewModel.createListAndAdd(name) } },
                onDismiss = { showListPicker = false },
            )
        }
    }
}

@Composable
private fun DetailContent(
    s: DetailUiState.Ready,
    inWatchlist: Boolean,
    liked: Boolean,
    isResolvingPlay: Boolean,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatchlist: (Boolean) -> Unit,
    onOpenListPicker: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
    onTrailerLaunch: () -> Unit,
    onPlayEpisode: (String) -> Unit,
    onSimilarSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit,
) {
    val backdropUrl = "${s.baseUrl}/v1/items/${s.item.id}/backdrop?stream=${s.streamToken}"
    val canResume = s.resumeSec > 30
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1117))) {
        // Backdrop: top 60% of the screen with a gradient that fades into the
        // page background. Matches chino-web DetailPage's aspect-[21/9] hero +
        // bg-gradient-to-t treatment.
        Box(modifier = Modifier.fillMaxWidth().height(560.dp)) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = s.item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0x33000000),
                                Color(0xCC0D1117),
                                Color(0xFF0D1117),
                            ),
                            startY = 200f,
                        ),
                    ),
            )
        }
        // Content overlays the bottom half of the backdrop so the title + CTAs
        // sit on top of the gradient. For series, the page is taller (episodes
        // strip below the fold) so we let it scroll instead of fixing to the
        // viewport.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                // 24dp horizontal = the mobile detail content inset, so the
                // poster + title + episodes line up with the browse grid you
                // came from (was 64dp — far more indented than the rest).
                .padding(horizontal = 24.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            // Poster (left) + content (right), overlapping the backdrop —
            // matches chino-web/tablet's detail layout.
            Row(
                modifier = Modifier.fillMaxWidth(0.82f),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                AsyncImage(
                    model = "${s.baseUrl}/v1/items/${s.item.id}/poster?stream=${s.streamToken}",
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(200.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF161B22)),
                )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = s.item.title,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                s.item.tagline?.takeIf { it.isNotBlank() }?.let { tagline ->
                    Text(
                        text = tagline,
                        color = Color(0xFF8B949E),
                        fontStyle = FontStyle.Italic,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                MetaRow(s.item.year, s.item.durationMs, s.item.rating, s.item.kind)
                if (s.item.genres.isNotEmpty()) {
                    GenreChips(s.item.genres)
                }
                // Action row sits ABOVE the overview — web order is
                // title → meta → genres → actions → overview → cast.
                DetailActions(
                    item = s.item,
                    canResume = canResume,
                    resumeSec = s.resumeSec,
                    isResolvingPlay = isResolvingPlay,
                    inWatchlist = inWatchlist,
                    liked = liked,
                    onPlay = onPlay,
                    onToggleWatchlist = onToggleWatchlist,
                    onOpenListPicker = onOpenListPicker,
                    onToggleLike = onToggleLike,
                    onToggleWatched = onToggleWatched,
                    onTrailerLaunch = onTrailerLaunch,
                )
                s.item.overview?.let { o ->
                    Text(
                        text = o,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 4,
                    )
                }
                FooterGrid(s.item)
            }
            }
            // Cast & crew — directors first, then actors. A horizontal,
            // DPAD-focusable shelf of avatar+name+role cards so the credits are
            // remote-navigable (web + mobile show the same people as a static
            // text line; on a 10-ft TV a focusable row reads better and matches
            // the rest of the app's shelf interaction model). Hidden when
            // chino-api hasn't populated cast for the item.
            CastCrewSection(cast = s.item.cast, onPersonSelected = onPersonSelected)
            // Episodes block — only rendered for series with at least one
            // season. Sits below the action row inside the scrollable column
            // so the user can DPAD_DOWN past the buttons into the list.
            if (s.seasons.isNotEmpty()) {
                EpisodesBlock(
                    seasons = s.seasons,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
                    onPlayEpisode = onPlayEpisode,
                    onToggleEpisodeWatched = onToggleEpisodeWatched,
                )
            }
            // "More like this" — katalog recommendations. Focusable poster
            // shelf below the episodes; selecting one pushes a new Detail.
            if (s.similar.isNotEmpty()) {
                MoreLikeThisSection(
                    items = s.similar,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
                    onSelect = onSimilarSelected,
                )
            }
        }
    }
}

/** Detail action row — web parity: a labeled primary (Play/Resume) + optional
 *  Trailer, then the watchlist / watched / like toggles as ICON CIRCLES
 *  (Plus/Eye/Heart, tinted when active) rather than wide labeled buttons. */
@Composable
private fun DetailActions(
    item: Item,
    canResume: Boolean,
    resumeSec: Int,
    isResolvingPlay: Boolean,
    inWatchlist: Boolean,
    liked: Boolean,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatchlist: (Boolean) -> Unit,
    onOpenListPicker: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onTrailerLaunch: () -> Unit,
) {
    val watched = item.watchedAt != null
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val primaryLabel = when {
            isResolvingPlay -> "Loading…"
            canResume -> "Resume ${formatHM(resumeSec)}"
            else -> "Play"
        }
        Button(
            onClick = { onPlay(canResume) },
            colors = ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                focusedContainerColor = MaterialTheme.colorScheme.primary,
                focusedContentColor = Color.White,
            ),
        ) {
            Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(text = primaryLabel, fontWeight = FontWeight.SemiBold)
        }
        // "Start over" — secondary CTA shown only when there's a saved position
        // (so the primary reads "Resume"). Plays from the beginning via
        // onPlay(false); the player distinguishes resume vs fromStart. Sits
        // second in the D-pad order (Resume first, Start over next).
        if (canResume) {
            Button(onClick = { onPlay(false) }) {
                Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = "Start over")
            }
        }
        // Trailer — labeled secondary CTA next to Play (web shows it too).
        pickTrailer(item.trailers)?.let { trailer ->
            val context = androidx.compose.ui.platform.LocalContext.current
            Button(onClick = { onTrailerLaunch(); launchTrailer(context, trailer) }) {
                Icon(Lucide.Youtube, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = "Trailer")
            }
        }
        // Add-to-list control (web parity). The "+" circle is the casual path:
        // a plain CENTER adds to the default list when the item is in no list
        // (icon → Check), or clears it from every list when already saved. The
        // adjacent caret opens the DPAD-navigable AddToList picker to choose a
        // specific list. Tinted emerald when the item is in >=1 list.
        DetailActionCircle(
            icon = if (inWatchlist) Lucide.Check else Lucide.Plus,
            contentDescription = if (inWatchlist) "Saved — press to remove" else "Add to watchlist",
            active = inWatchlist,
            onClick = { onToggleWatchlist(!inWatchlist) },
        )
        DetailActionCircle(
            icon = Lucide.ChevronDown,
            contentDescription = "Add to a list",
            active = false,
            onClick = onOpenListPicker,
        )
        // Watched TOGGLE (web parity): filled green Check when watched, outline
        // Eye when not. CENTER flips it — POST to mark, DELETE to un-mark.
        DetailActionCircle(
            icon = if (watched) Lucide.Check else Lucide.Eye,
            contentDescription = if (watched) "Mark as unwatched" else "Mark as watched",
            active = watched,
            onClick = onToggleWatched,
        )
        DetailActionCircle(
            icon = Lucide.Heart,
            contentDescription = if (liked) "Liked" else "Like",
            active = liked,
            activeColor = Color(0xFFE11D48),
            onClick = { onToggleLike(!liked) },
        )
    }
}

@Composable
private fun DetailActionCircle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    active: Boolean = false,
    activeColor: Color = Color(0xFF2EA043),
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = ButtonDefaults.shape(shape = CircleShape),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.colors(
            containerColor = if (active) activeColor else Color.White.copy(alpha = 0.1f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

/* ─────────────────────────  add-to-list picker  ─────────────────────────
 * A floating, DPAD-navigable panel listing the user's watchlists, each with a
 * check when the current item is a member; CENTER toggles membership (PUT /
 * DELETE items). A top "+ New list" row swaps into an inline TextField (the
 * on-screen IME) to name + create a list, then adds the item to it. Reuses the
 * player MenuPopover visual idiom (#161B22 card, blue focus tint, first-row
 * auto-focus); a dim scrim sits behind it and BACK closes the panel. */
@Composable
private fun AddToListPanel(
    lists: List<cloud.nalet.chino.tv.data.api.Watchlist>,
    memberItemId: String,
    memberships: Map<String, Set<String>>,
    onToggle: (listId: String, present: Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // creating=true swaps the "+ New list" row for the inline name field.
    var creating by remember { mutableStateOf(false) }
    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(creating) { if (!creating) runCatching { firstRowFocus.requestFocus() } }
    val memberLists = memberships[memberItemId].orEmpty()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF161B22))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = "ADD TO LIST",
                color = Color(0xFF8B949E),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            // "+ New list" affordance — first focus target so the keyboard path
            // is reachable immediately; when active it becomes an inline field.
            if (creating) {
                NewListField(
                    onSubmit = { name ->
                        if (name.isNotBlank()) onCreate(name)
                        onDismiss()
                    },
                    onCancel = { creating = false },
                )
            } else {
                AddToListRow(
                    label = "New list…",
                    checked = false,
                    leadingPlus = true,
                    focusRequester = firstRowFocus,
                    onClick = { creating = true },
                )
            }
            lists.forEach { list ->
                AddToListRow(
                    label = list.name,
                    secondary = "${list.itemCount} item${if (list.itemCount == 1) "" else "s"}",
                    checked = list.id in memberLists,
                    onClick = { onToggle(list.id, list.id !in memberLists) },
                )
            }
        }
    }
}

@Composable
private fun AddToListRow(
    label: String,
    checked: Boolean,
    secondary: String? = null,
    leadingPlus: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val base = Modifier
        .fillMaxWidth()
        .background(if (focused) Color(0xFF58A6FF).copy(alpha = 0.22f) else Color.Transparent)
        .padding(horizontal = 16.dp, vertical = 12.dp)
    val m = if (focusRequester != null) base.focusRequester(focusRequester) else base
    Row(
        modifier = m
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    onClick(); true
                } else false
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingPlus) {
            Icon(Lucide.Plus, contentDescription = null, tint = Color(0xFF58A6FF), modifier = Modifier.size(18.dp))
        } else {
            ListCheckbox(checked = checked)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(text = secondary, color = Color(0xFF8B949E), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (checked) Color(0xFF2EA043) else Color.Transparent)
            .border(
                1.dp,
                if (checked) Color(0xFF2EA043) else Color.White.copy(alpha = 0.3f),
                RoundedCornerShape(4.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Lucide.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
    }
}

/** Inline new-list name field shown in place of the "+ New list" row. The
 *  on-screen IME handles D-pad text entry; the IME "Done" key submits, BACK
 *  cancels back to the row list. Mirrors the SearchScreen / ServerSetup input. */
@Composable
private fun NewListField(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1117))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) Color(0xFF58A6FF) else Color(0xFF30363D),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 60) value = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(Color(0xFF58A6FF)),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(value.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focused = it.isFocused },
            )
            if (value.isEmpty()) {
                Text(text = "List name…", color = Color(0xFF8B949E), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun EpisodesBlock(
    seasons: List<Season>,
    baseUrl: String,
    streamToken: String,
    onPlayEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
) {
    // Vertical accordion matching chino-web's EpisodesList: an "Episodes"
    // heading, then one collapsible season card per season expanding into a
    // stacked list of full-width episode rows (16:9 thumb + label + title +
    // runtime + 2-line synopsis, separated by 1dp dividers).
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Episodes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        seasons.forEachIndexed { index, season ->
            SeasonSection(
                season = season,
                baseUrl = baseUrl,
                streamToken = streamToken,
                initiallyExpanded = index == 0,
                onPlayEpisode = onPlayEpisode,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
            )
        }
    }
}

@Composable
private fun SeasonSection(
    season: Season,
    baseUrl: String,
    streamToken: String,
    initiallyExpanded: Boolean,
    onPlayEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var headerFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF161B22)),
    ) {
        // Season header — focusable; CENTER toggles expand/collapse.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .onFocusChanged { headerFocused = it.isFocused }
                .focusable()
                .background(if (headerFocused) Color(0xFF21262D) else Color.Transparent)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Season ${season.season}",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 16.dp),
            )
            Text(
                text = "${season.episodes.size} episodes",
                color = Color(0xFF8B949E),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            season.episodes.forEachIndexed { i, ep ->
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color(0xFF21262D)),
                    )
                }
                EpisodeRow(
                    episode = ep,
                    backdropUrl = "$baseUrl/v1/items/${ep.id}/backdrop?stream=$streamToken",
                    onClick = { onPlayEpisode(ep.id) },
                    onToggleWatched = { onToggleEpisodeWatched(ep.id, ep.watchedAt == null) },
                )
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    backdropUrl: String,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val watched = episode.watchedAt != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .background(if (focused) Color(0xFF21262D) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(160.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF0D1117)),
        ) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val label = episode.episodeNumber?.let { ep ->
                    "S%02dE%02d".format(episode.seasonNumber ?: 0, ep)
                }
                if (label != null) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                episode.durationMs?.let { (it / 60_000L).toInt() }?.takeIf { it > 0 }?.let {
                    Text(text = "${it}m", color = Color(0xFF8B949E), style = MaterialTheme.typography.bodySmall)
                }
            }
            episode.overview?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = Color(0xFFC9D1D9),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Per-episode watched TOGGLE — its own DPAD focus target on the row's
        // trailing edge (RIGHT from the play row reaches it). Green filled
        // Check when watched, subtle grey Eye when not; CENTER flips it
        // (POST/DELETE) with optimistic UI. Web shows the same control as a
        // hover button over the still; on TV a focusable trailing circle reads
        // better at 10ft and stays in the remote's focus order.
        EpisodeWatchedToggle(watched = watched, onToggle = onToggleWatched)
    }
}

@Composable
private fun EpisodeWatchedToggle(watched: Boolean, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                when {
                    focused -> Color.White
                    watched -> Color(0xFF2EA043)
                    else -> Color.White.copy(alpha = 0.1f)
                },
            )
            .then(
                if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), CircleShape) else Modifier,
            )
            .onFocusChanged { focused = it.isFocused }
            // clickable already maps DPAD_CENTER/ENTER to a click on key-UP, so
            // a manual onKeyEvent(KeyDown) handler here would double-fire (mark
            // then immediately un-mark = no-op on a real remote). Use clickable
            // alone, matching every other focusable control in this screen.
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (watched) Lucide.Check else Lucide.Eye,
            contentDescription = if (watched) "Mark episode as unwatched" else "Mark episode as watched",
            tint = if (focused) Color.Black else if (watched) Color.White else Color(0xFF8B949E),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun MetaRow(year: Int?, durationMs: Long?, rating: Double?, kind: String?) {
    // year • runtime • ★rating • TYPE pill — matches mobile/web DetailPage.
    val runtimeMin = durationMs?.let { (it / 60_000L).toInt() } ?: 0
    val runtimeText = when {
        runtimeMin <= 0 -> null
        runtimeMin >= 60 -> "${runtimeMin / 60}h ${runtimeMin % 60}m"
        else -> "${runtimeMin}m"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        year?.let {
            Text(text = it.toString(), color = Color(0xFFC9D1D9), fontSize = 14.sp)
        }
        runtimeText?.let {
            if (year != null) Bullet()
            Text(text = it, color = Color(0xFFC9D1D9), fontSize = 14.sp)
        }
        rating?.let { r ->
            if (year != null || runtimeText != null) Bullet()
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Lucide.Star,
                    contentDescription = null,
                    tint = Color(0xFF58A6FF),
                    modifier = Modifier.size(16.dp),
                )
                Text(text = String.format("%.1f", r), color = Color(0xFFC9D1D9), fontSize = 14.sp)
            }
        }
        kind?.takeIf { it.isNotBlank() }?.let { k ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = k.uppercase(),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun Bullet() {
    Text("•", color = Color(0xFF8B949E))
}

@Composable
private fun greenButtonColors() = ButtonDefaults.colors(
    containerColor = Color(0xFF2EA043),
    contentColor = Color.White,
    focusedContainerColor = Color(0xFF2EA043),
    focusedContentColor = Color.White,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChips(genres: List<String>) {
    // Display-only pills (NOT focusable — must not steal DPAD focus from the
    // action row). #21262D fill + 1dp #30363D border, matching mobile.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        genres.forEach { genre ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFF21262D))
                    .border(BorderStroke(1.dp, Color(0xFF30363D)), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = genre, color = Color(0xFFC9D1D9), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FooterGrid(item: Item) {
    val subtitleLabel = item.subtitles
        .mapNotNull { it.label?.takeIf { l -> l.isNotBlank() } ?: it.lang.takeIf { l -> l.isNotBlank() } }
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }
    val analyzedLabel = item.segments?.takeIf { it.count > 0 }?.let { seg ->
        listOfNotNull(
            "Intro".takeIf { seg.hasIntro },
            "Credits".takeIf { seg.hasCredits },
            "Recap".takeIf { seg.hasRecap },
        ).joinToString(" · ").ifEmpty { "Segments available" }
    }
    if (subtitleLabel == null && analyzedLabel == null) return
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        subtitleLabel?.let { FooterColumn("Subtitles", it) }
        analyzedLabel?.let { FooterColumn("Analyzed", it) }
    }
}

@Composable
private fun FooterColumn(header: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = header, color = Color(0xFF8B949E), fontSize = 14.sp)
        Text(
            text = value,
            color = Color(0xFFC9D1D9),
            fontSize = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MoreLikeThisSection(
    items: List<Item>,
    baseUrl: String,
    streamToken: String,
    onSelect: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "More like this",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 32.dp, top = 12.dp, bottom = 12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(items, key = { it.id }) { entry ->
                SimilarCard(
                    item = entry,
                    posterUrl = "$baseUrl/v1/items/${entry.id}/poster?stream=$streamToken",
                    onClick = { onSelect(entry.id) },
                )
            }
        }
    }
}

@Composable
private fun SimilarCard(item: Item, posterUrl: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        scale = CardDefaults.scale(focusedScale = 1.05f),
        modifier = Modifier
            .width(180.dp)
            .clip(RoundedCornerShape(8.dp)),
    ) {
        Column(modifier = Modifier.background(Color(0xFF161B22))) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .background(Color(0xFF0D1117)),
            ) {
                AsyncImage(
                    model = posterUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    color = Color(0xFFC9D1D9),
                    fontSize = 13.sp,
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
                        item.year?.let { Text(it.toString(), color = Color(0xFF8B949E), fontSize = 12.sp) }
                        ratingText?.let {
                            if (item.year != null) Text("•", color = Color(0xFF8B949E), fontSize = 12.sp)
                            Text(it, color = Color(0xFF58A6FF), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Cast & crew shelf — directors first (web shows directors above actors),
 *  then actors. Each entry is a focusable card: an initials avatar circle
 *  (chino-api carries no profile photo URL, web/mobile show none either), the
 *  name, and the role. When chino-api carries a person_id for the entry the
 *  card becomes a TAP TARGET — OK opens the Person / Filmography surface;
 *  entries without a person_id stay display-only but still hold focus so the
 *  row stays browsable with the remote. */
@Composable
private fun CastCrewSection(
    cast: List<cloud.nalet.chino.tv.data.model.CastMember>,
    onPersonSelected: (String) -> Unit,
) {
    val directors = cast.filter { it.role.equals("director", ignoreCase = true) }
    val actors = cast.filter { it.role == null || it.role.equals("actor", ignoreCase = true) }
    // Directors first so the head of the row is the credited director(s); then
    // actors. Preserves chino-api's ordering within each group.
    val ordered = directors + actors
    if (ordered.isEmpty()) return
    Column(
        modifier = Modifier.padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Cast & crew",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 32.dp, top = 4.dp, bottom = 4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(ordered, key = { "${it.role}:${it.name}" }) { member ->
                CastCard(
                    member = member,
                    onClick = member.personId?.let { id -> { onPersonSelected(id) } },
                )
            }
        }
    }
}

@Composable
private fun CastCard(
    member: cloud.nalet.chino.tv.data.model.CastMember,
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val roleLabel = when {
        member.role.equals("director", ignoreCase = true) -> "Director"
        member.role.equals("actor", ignoreCase = true) -> "Actor"
        member.role.isNullOrBlank() -> "Cast"
        else -> member.role.replaceFirstChar { it.uppercase() }
    }
    Column(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            // OK opens the Person surface, but only when chino-api gave us a
            // person_id (onClick non-null). onKeyEvent (not clickable) so a
            // display-only chip with no target simply doesn't react to CENTER.
            .then(
                if (onClick != null) {
                    Modifier.onKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown &&
                            (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)
                        ) {
                            onClick(); true
                        } else false
                    }
                } else Modifier,
            )
            .background(if (focused) Color(0xFF21262D) else Color.Transparent)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color(0xFF161B22))
                .then(
                    if (focused) Modifier.border(2.dp, Color(0xFF58A6FF), CircleShape) else Modifier,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(member.name),
                color = Color(0xFFC9D1D9),
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            )
        }
        Text(
            text = member.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Text(
            text = roleLabel,
            color = Color(0xFF8B949E),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Up to two initials from a person's name, e.g. "Greta Gerwig" -> "GG". */
private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { "?" }
}

@Composable
private fun Centered(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (isError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Prefer the most-likely "Official Trailer" YouTube entry; fall back to the first. */
private fun pickTrailer(trailers: List<Trailer>): Trailer? {
    if (trailers.isEmpty()) return null
    val yt = trailers.filter { (it.site ?: "").contains("youtube", ignoreCase = true) }
    val pool = if (yt.isNotEmpty()) yt else trailers
    return pool.firstOrNull {
        val t = it.title.orEmpty()
        t.contains("official", ignoreCase = true) && t.contains("trailer", ignoreCase = true)
    } ?: pool.firstOrNull { it.title.orEmpty().contains("trailer", ignoreCase = true) }
        ?: pool.first()
}

private fun launchTrailer(context: android.content.Context, trailer: Trailer) {
    // Pull the YouTube video id out of common URL shapes (?v=…, /embed/…, /shorts/…).
    val ytId = Regex("""(?:v=|/embed/|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")
        .find(trailer.url)?.groupValues?.getOrNull(1)
    val intent = if (ytId != null) {
        // vnd.youtube: deep-link is the most-reliable on Android TV — the TV
        // YouTube app picks it up and goes straight to the video.
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("vnd.youtube:$ytId"))
    } else {
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(trailer.url))
    }
    intent.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
    runCatching { context.startActivity(intent) }
}

private fun formatHM(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
