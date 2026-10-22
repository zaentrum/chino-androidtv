package cloud.nalet.chino.tv.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.model.CastMember
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.episodeCode
import cloud.nalet.chino.tv.data.model.fileEntries
import cloud.nalet.chino.tv.ui.player.languageOrLabel
import cloud.nalet.chino.tv.ui.theme.ChinoAccent
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import cloud.nalet.chino.tv.ui.theme.ChinoBorder
import cloud.nalet.chino.tv.ui.theme.ChinoBorderHi
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoCard
import cloud.nalet.chino.tv.ui.theme.ChinoError
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.ChinoMuted
import cloud.nalet.chino.tv.ui.theme.ChinoSurface
import cloud.nalet.chino.tv.ui.theme.ChinoText
import cloud.nalet.chino.tv.ui.theme.FocusKind
import cloud.nalet.chino.tv.ui.theme.ItemFocus
import cloud.nalet.chino.tv.ui.theme.PosterImage
import cloud.nalet.chino.tv.ui.theme.focusStyle
import cloud.nalet.chino.tv.ui.theme.rememberItemFocus
import cloud.nalet.chino.tv.ui.theme.shelfPadding
import cloud.nalet.chino.tv.ui.theme.shelfRoom
import cloud.nalet.chino.tv.ui.theme.LoadError
import cloud.nalet.chino.tv.ui.trailer.TrailerChoice
import cloud.nalet.chino.tv.ui.trailer.launchTrailerLink
import cloud.nalet.chino.tv.ui.trailer.trailerChoice
import coil.compose.AsyncImage
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Clapperboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Play
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Star
import com.composables.icons.lucide.Youtube
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    /** Plays itemId from resumeSec, the second Resume names, or from the
     *  start where it is 0. */
    onPlay: (itemId: String, resumeSec: Int) -> Unit,
    onPlayEpisode: (String) -> Unit = { id -> onPlay(id, 0) },
    /** Plays the extra extraId of the title itemId: its trailer from this server. */
    onPlayTrailer: (itemId: String, extraId: String) -> Unit = { _, _ -> },
    onSimilarSelected: (String) -> Unit = {},
    onPersonSelected: (String) -> Unit = {},
    /** Where the title is gone: back to where it was opened from. */
    onBack: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    // The Watchlist alone: what the bookmark of add to list shows and toggles.
    val watchlist by viewModel.watchlist.collectAsState()
    val lists by viewModel.lists.collectAsState()
    val memberships by viewModel.memberships.collectAsState()
    val likes by viewModel.likes.collectAsState()
    // Refresh the per-episode resume map whenever this screen comes back to
    // the foreground — covers "user played an episode and pressed BACK"
    // (LocalLifecycleOwner here is the NavBackStackEntry, which returns to
    // RESUMED on pop) and app background→foreground. Same nav-entry-scoped
    // idiom as LibraryScreen: the skip-the-redundant-first-resume decision
    // lives in the VIEWMODEL (onScreenResumed), because this destination
    // leaves composition while the Player sits on top, so this
    // DisposableEffect is recreated on every return — a composition-local
    // flag would swallow the catch-up ON_RESUME delivered on observer
    // re-registration (the very event that means "user came back").
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
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Non-null while the add-to-list picker overlays the page; the value is
    // the item id the panel edits — the rendered SERIES/MOVIE (displayItemId)
    // when opened from the action-row caret, or one EPISODE id when opened
    // from an episode row's "+" affordance. BACK closes it.
    var listPickerTarget by remember { mutableStateOf<String?>(null) }
    // The play target is worked out on load (a series' episode included), so
    // Play navigates at once; launchSingleTop on the route absorbs a doubled
    // DPAD_CENTER. Resume goes on at the second its label names, as chino-web's
    // Resume does: the player looks up nothing.
    val play: (Boolean) -> Unit = { resume ->
        onPlay(viewModel.playTarget(), if (resume) viewModel.resumeSecond() else 0)
    }
    Surface(modifier = Modifier.fillMaxSize()) {
        when (val s = state) {
            DetailUiState.Loading -> Centered("Loading…")
            // The screen's way on: try again, focused first; back where the
            // title is gone or not the viewer's to see, which no try mends.
            is DetailUiState.Error -> LoadError(
                title = "couldn't load this title",
                failure = s.failure,
                onRetry = viewModel::load,
                what = "this title",
                onBack = onBack,
            )
            is DetailUiState.Ready -> DetailContent(
                s = s,
                inWatchlist = viewModel.displayItemId in watchlist,
                watchlist = watchlist,
                liked = viewModel.displayItemId in likes,
                onPlay = play,
                onToggleWatchlist = { viewModel.toggleWatchlist(it) },
                onOpenListPicker = { listPickerTarget = viewModel.displayItemId },
                onToggleLike = { viewModel.toggleLike(it) },
                onToggleWatched = { viewModel.toggleWatched() },
                onToggleEpisodeWatched = { id, watched -> viewModel.toggleEpisodeWatched(id, watched) },
                onAddEpisodeToList = { episodeId -> listPickerTarget = episodeId },
                onToggleEpisodeWatchlist = { episodeId, present -> viewModel.setInWatchlist(episodeId, present) },
                onTrailerLaunch = { viewModel.reportTrailerLaunch() },
                // The rendered title's: the series' for an episode entry.
                onPlayTrailer = { extraId -> onPlayTrailer(s.item.id, extraId) },
                onPlayEpisode = onPlayEpisode,
                onSimilarSelected = onSimilarSelected,
                onPersonSelected = onPersonSelected,
            )
        }
        // Add-to-list picker — floats over the page (player MenuPopover idiom).
        // BACK closes it; toggling a row calls PUT/DELETE items optimistically.
        // Targets pickerTarget: the series/movie OR a single episode.
        val pickerTarget = listPickerTarget
        if (pickerTarget != null && state is DetailUiState.Ready) {
            AddToListPanel(
                lists = lists,
                memberItemId = pickerTarget,
                memberships = memberships,
                onToggle = { listId, present -> viewModel.setItemInList(listId, present, pickerTarget) },
                onCreate = { name -> scope.launch { viewModel.createListAndAdd(name, pickerTarget) } },
                onDismiss = { listPickerTarget = null },
            )
        }
    }
}

@Composable
private fun DetailContent(
    s: DetailUiState.Ready,
    inWatchlist: Boolean,
    /** The ids in the Watchlist: the episode rows' bookmarks. */
    watchlist: Set<String>,
    liked: Boolean,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatchlist: (Boolean) -> Unit,
    onOpenListPicker: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
    onAddEpisodeToList: (String) -> Unit,
    onToggleEpisodeWatchlist: (String, Boolean) -> Unit,
    onTrailerLaunch: () -> Unit,
    onPlayTrailer: (extraId: String) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onSimilarSelected: (String) -> Unit,
    onPersonSelected: (String) -> Unit,
) {
    val backdropUrl = "${s.baseUrl}/v1/items/${s.item.id}/backdrop?stream=${s.streamToken}"
    // A series opened as a series plays the episode the viewer is in (Resume
    // S02E03) or the next one (Play S02E04); a movie or an episode entry
    // resumes its own saved position.
    val seriesPlay = s.seriesPlay?.takeIf { s.item.kind == "series" && s.focusEpisodeId == null }
    val canResume = seriesPlay?.resumes ?: (s.resumeSec > 30)
    val primaryLabel = when {
        seriesPlay != null ->
            listOfNotNull(if (seriesPlay.resumes) "Resume" else "Play", seriesPlay.code).joinToString(" ")
        canResume -> "Resume ${formatHM(s.resumeSec)}"
        else -> "Play"
    }
    Box(modifier = Modifier.fillMaxSize().background(ChinoBg)) {
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
                                ChinoBg,
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
                PosterImage(
                    model = "${s.baseUrl}/v1/items/${s.item.id}/poster?stream=${s.streamToken}",
                    title = s.item.title,
                    contentDescription = null,
                    modifier = Modifier
                        .width(200.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RectangleShape)
                        .background(ChinoSurface),
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
                        color = ChinoMuted,
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
                    primaryLabel = primaryLabel,
                    canResume = canResume,
                    inWatchlist = inWatchlist,
                    liked = liked,
                    onPlay = onPlay,
                    onToggleWatchlist = onToggleWatchlist,
                    onOpenListPicker = onOpenListPicker,
                    onToggleLike = onToggleLike,
                    onToggleWatched = onToggleWatched,
                    onTrailerLaunch = onTrailerLaunch,
                    onPlayTrailer = onPlayTrailer,
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
            // Credits — "Starring" (the actors in billing order, with their
            // characters), then the crew role by role (Created by, Director,
            // Writers, Music, …), as chino-web's detail page lists them. Every
            // name is a DPAD focus target so the credits are remote-navigable;
            // hidden when chino-api hasn't populated cast for the item.
            CreditsSection(cast = s.item.cast, onPersonSelected = onPersonSelected)
            // Episodes block — only rendered for series with at least one
            // season. Sits below the action row inside the scrollable column
            // so the user can DPAD_DOWN past the buttons into the list.
            if (s.seasons.isNotEmpty()) {
                EpisodesBlock(
                    seasons = s.seasons,
                    baseUrl = s.baseUrl,
                    streamToken = s.streamToken,
                    focusEpisodeId = s.focusEpisodeId,
                    episodeResume = s.episodeResume,
                    onPlayEpisode = onPlayEpisode,
                    onToggleEpisodeWatched = onToggleEpisodeWatched,
                    onAddEpisodeToList = onAddEpisodeToList,
                    watchlist = watchlist,
                    onToggleEpisodeWatchlist = onToggleEpisodeWatchlist,
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

/** Detail action row, the order and states every app has (docs/chino, title
 *  actions): Play (or Resume) · Start over · Trailer, then add to list - the
 *  Watchlist's bookmark with its "⌄" - · watched · liked (TitleActions.kt).
 *  Their names say the state. */
@Composable
internal fun DetailActions(
    item: Item,
    /** "Play", "Resume 12:34", "Resume S02E03", "Play S02E04". */
    primaryLabel: String,
    /** The primary resumes a saved position — "Start over" is offered too. */
    canResume: Boolean,
    /** In the Watchlist: what the bookmark shows and its press toggles. */
    inWatchlist: Boolean,
    liked: Boolean,
    onPlay: (resume: Boolean) -> Unit,
    onToggleWatchlist: (Boolean) -> Unit,
    onOpenListPicker: () -> Unit,
    onToggleLike: (Boolean) -> Unit,
    onToggleWatched: () -> Unit,
    onTrailerLaunch: () -> Unit,
    onPlayTrailer: (extraId: String) -> Unit,
) {
    val watched = item.watchedAt != null
    Row(
        modifier = Modifier.padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Without a position to resume (a series' next episode included) the
        // player starts at 0:00 rather than at a finished episode's last
        // saved second.
        PlayButton(label = primaryLabel, onClick = { onPlay(canResume) })
        // "Start over" — secondary CTA shown only when there's a saved position
        // (so the primary reads "Resume"; for a series, the episode it
        // resumes). Plays from the beginning via onPlay(false); the player
        // distinguishes resume vs fromStart. Sits second in the D-pad order
        // (Resume first, Start over next).
        if (canResume) {
            ChinoButton(onClick = { onPlay(false) }) {
                Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = "Start over")
            }
        }
        // Trailer — labeled secondary CTA next to Play (web shows it too): a
        // trailer this server plays opens in the player, else the
        // title's link opens in the YouTube app as before (trailerChoice).
        trailerChoice(item)?.let { choice ->
            val context = androidx.compose.ui.platform.LocalContext.current
            ChinoButton(
                onClick = {
                    when (choice) {
                        is TrailerChoice.Local -> onPlayTrailer(choice.extra.id)
                        is TrailerChoice.Link -> {
                            onTrailerLaunch()
                            launchTrailerLink(context, choice.trailer)
                        }
                    }
                },
            ) {
                Icon(
                    if (choice is TrailerChoice.Local) Lucide.Clapperboard else Lucide.Youtube,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "Trailer")
            }
        }
        // Add to list: the bookmark toggles the Watchlist alone - never
        // another list, which the "⌄" picker is for - then watched and liked.
        AddToListControl(
            inWatchlist = inWatchlist,
            onToggleWatchlist = { onToggleWatchlist(!inWatchlist) },
            onChooseLists = onOpenListPicker,
        )
        WatchedToggle(watched = watched, onToggle = onToggleWatched)
        LikedToggle(liked = liked, onToggle = { onToggleLike(!liked) })
    }
}

/** The detail's emphasized action, Play or Resume: a filled button, its fill
 *  kept on focus, its label in the page's colour. */
@Composable
internal fun PlayButton(label: String, onClick: () -> Unit, focus: ItemFocus = rememberItemFocus()) {
    ChinoButton(
        onClick = onClick,
        container = MaterialTheme.colorScheme.primary,
        content = MaterialTheme.colorScheme.onPrimary,
        filled = true,
        focus = focus,
    ) {
        Icon(Lucide.Play, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(text = label, fontWeight = FontWeight.SemiBold)
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
        // Not clipped: a focused row grows past the panel's edge.
        Column(
            modifier = Modifier
                .width(360.dp)
                .heightIn(max = 520.dp)
                .background(ChinoSurface)
                .border(1.dp, Color.White.copy(alpha = 0.1f), RectangleShape)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 6.dp),
        ) {
            Text(
                text = "ADD TO LIST",
                color = ChinoMuted,
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
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusStyle(focus, FocusKind.Row, surface = Color.Transparent)
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingPlus) {
            Icon(Lucide.Plus, contentDescription = null, tint = ChinoAccent, modifier = Modifier.size(18.dp))
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
                Text(text = secondary, color = ChinoFocus.text(ChinoMuted, focus.focused), fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ListCheckbox(checked: Boolean) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RectangleShape)
            // Blue, as a menu's pick is: green is watched.
            .background(if (checked) ChinoAccent else Color.Transparent)
            .border(
                1.dp,
                if (checked) ChinoAccent else Color.White.copy(alpha = 0.3f),
                RectangleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Lucide.Check, contentDescription = null, tint = ChinoBg, modifier = Modifier.size(13.dp))
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
    // The field takes the focus style as a row does: its frame follows the
    // text field's focus.
    val focus = rememberItemFocus()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(44.dp)
            .focusStyle(focus, FocusKind.Row, surface = ChinoBg)
            .border(1.dp, ChinoBorder, RectangleShape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = { if (it.length <= 60) value = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(ChinoAccent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(value.trim()) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            if (value.isEmpty()) {
                Text(text = "List name…", color = ChinoFocus.text(ChinoMuted, focus.focused), fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun EpisodesBlock(
    seasons: List<Season>,
    baseUrl: String,
    streamToken: String,
    focusEpisodeId: String?,
    episodeResume: Map<String, EpisodeResume>,
    onPlayEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
    onAddEpisodeToList: (String) -> Unit,
    watchlist: Set<String>,
    onToggleEpisodeWatchlist: (String, Boolean) -> Unit,
) {
    // Vertical accordion matching chino-web's EpisodesList: an "Episodes"
    // heading, then one collapsible season card per season expanding into a
    // stacked list of full-width episode rows (16:9 thumb + label + title +
    // runtime + 2-line synopsis, separated by 1dp dividers).
    //
    // When we arrived from an episode entry (focusEpisodeId set), find the
    // season containing that episode so it — not season 0 — starts expanded and
    // its row can grab DPAD focus below.
    val focusSeasonIndex = focusEpisodeId?.let { id ->
        seasons.indexOfFirst { season -> season.episodes.any { it.id == id } }
    }?.takeIf { it >= 0 }
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
                // Expand the target season on an episode entry, else season 0.
                initiallyExpanded = if (focusSeasonIndex != null) index == focusSeasonIndex else index == 0,
                focusEpisodeId = focusEpisodeId?.takeIf { index == focusSeasonIndex },
                episodeResume = episodeResume,
                onPlayEpisode = onPlayEpisode,
                onToggleEpisodeWatched = onToggleEpisodeWatched,
                onAddEpisodeToList = onAddEpisodeToList,
                watchlist = watchlist,
                onToggleEpisodeWatchlist = onToggleEpisodeWatchlist,
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
    focusEpisodeId: String?,
    episodeResume: Map<String, EpisodeResume>,
    onPlayEpisode: (String) -> Unit,
    onToggleEpisodeWatched: (String, Boolean) -> Unit,
    onAddEpisodeToList: (String) -> Unit,
    watchlist: Set<String>,
    onToggleEpisodeWatchlist: (String, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val headerFocus = rememberItemFocus()
    // On an episode entry, land DPAD focus on the targeted row once the season
    // is expanded (the parent already expanded it via initiallyExpanded).
    val targetRowFocus = remember(focusEpisodeId) { FocusRequester() }
    LaunchedEffect(focusEpisodeId, expanded) {
        if (focusEpisodeId != null && expanded) {
            runCatching { targetRowFocus.requestFocus() }
        }
    }
    // Not clipped: a focused row grows past the season's edge.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ChinoSurface),
    ) {
        // Season header — one focus target; CENTER toggles expand/collapse.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusStyle(headerFocus, FocusKind.Row, surface = Color.Transparent)
                .clickable { expanded = !expanded }
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
                color = ChinoFocus.text(ChinoMuted, headerFocus.focused),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Lucide.ChevronDown else Lucide.ChevronRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = ChinoFocus.text(ChinoMuted, headerFocus.focused),
                modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) {
            // One entry per file: a file that holds several episodes is one
            // thing to play, a box around them (EpisodeRow's held).
            fileEntries(season.episodes).forEachIndexed { i, entry ->
                val ep = entry.episode
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(ChinoBorderHi),
                    )
                }
                val isTarget = focusEpisodeId != null && focusEpisodeId in entry.ids
                EpisodeRow(
                    episode = ep,
                    held = entry.held,
                    lastEpisodeNumber = entry.last,
                    backdropUrl = "$baseUrl/v1/items/${ep.id}/backdrop?stream=$streamToken",
                    // Resume is the file's: chino-api keeps one position for it.
                    resume = entry.ids.firstNotNullOfOrNull { episodeResume[it] },
                    selected = isTarget,
                    focusRequester = targetRowFocus.takeIf { isTarget },
                    onClick = { onPlayEpisode(ep.id) },
                    // Watched is the file's: any of its episodes marked means the file is.
                    onToggleWatched = { onToggleEpisodeWatched(ep.id, entry.held.none { it.watchedAt != null }) },
                    onAddToList = { onAddEpisodeToList(ep.id) },
                    inWatchlist = ep.id in watchlist,
                    onToggleWatchlist = { onToggleEpisodeWatchlist(ep.id, ep.id !in watchlist) },
                )
            }
        }
    }
}

/**
 * One entry of a season: the episode that plays - its own focus target, a
 * row in the focus style - and beside it, in the row's actions line and at
 * the title page's size, each a focus target of its own: its watched toggle
 * and its add to list, the Watchlist's bookmark with its "⌄" (RIGHT from the
 * row reaches them). Nothing is drawn over the still.
 */
@Composable
internal fun EpisodeRow(
    episode: Episode,
    /** The episodes the row's file holds: [episode], the holder, first. More
     *  than one makes the row a box around them - the file is the truth, the
     *  one thing that plays, named for them (S03E18–21) and as long as the
     *  file is; the episodes in it are listed by number and title, and none
     *  of them plays on its own. */
    held: List<Episode> = listOf(episode),
    /** The last episode number the file holds (the range's end). */
    lastEpisodeNumber: Int? = null,
    backdropUrl: String,
    // Non-null when the continue-watching feed says this episode is mid-watch:
    // draws the thin accent progress bar on the thumbnail + the "Resume ·
    // Xm left" hint. Click behaviour is unchanged — the player already
    // fetches the saved position and auto-resumes.
    resume: EpisodeResume?,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
    /** The "⌄" of its add to list: the list picker, for this episode. */
    onAddToList: () -> Unit,
    /** The episode is in the Watchlist: its bookmark's state. */
    inWatchlist: Boolean = false,
    onToggleWatchlist: () -> Unit = {},
    // Set when this row is the episode the detail was navigated to (episode
    // entry): where you came from - a tint and a bar along its left edge in
    // the accent mark it, never the focus ring. focusRequester lands DPAD
    // focus here on entry.
    selected: Boolean = false,
    focusRequester: FocusRequester? = null,
    focus: ItemFocus = rememberItemFocus(),
) {
    val focused = focus.focused
    val several = held.size > 1
    // Watched is the file's: chino-api marks every episode it holds together.
    val watched = held.any { it.watchedAt != null }
    // The entry's length is the file's: for a file of several, its measured
    // length where chino-api says it, else what its episodes' runtimes come to.
    val fileDurationMs: Long = if (several) {
        episode.fileDurationMs ?: held.sumOf { it.durationMs ?: 0L }
    } else {
        episode.durationMs ?: 0L
    }
    // Effective duration for the resume bar + remaining label: the CW row's
    // durationSec when known, else the episode's catalogue runtime
    // (canonical durationSec<=0 fallback). 0 when both are unknown — the
    // fill + remaining label then stay hidden.
    val resumeDurSec = if (resume != null && resume.durationSec > 0) resume.durationSec
        else (fileDurationMs / 1000L).toInt()
    val rowBg = when {
        selected -> ChinoAccent.copy(alpha = 0.18f)
        several -> ChinoBg.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A file of several episodes: a box, with room around it so its
            // border reads as a box and not as a row's divider.
            .then(if (several) Modifier.padding(8.dp) else Modifier)
            .background(rowBg)
            .then(if (several) Modifier.border(1.dp, ChinoAccent.copy(alpha = 0.5f), RectangleShape) else Modifier)
            .padding(end = if (several) 8.dp else 16.dp),
        // Room for the row's growth beside the toggles, and for theirs.
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The episode that plays: one focus target. The toggles beside it are
        // its siblings, not its children, so RIGHT reaches them.
        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .focusStyle(focus, FocusKind.Row, surface = Color.Transparent, cameFrom = selected)
                .clickable(onClick = onClick)
                .padding(start = if (several) 8.dp else 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RectangleShape)
                    .background(ChinoBg),
            ) {
                AsyncImage(
                    model = backdropUrl,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // In-progress marker: accent bar along the thumbnail's bottom edge
                // over a full-width track. Canonical cross-client spec: height
                // 4dp, ChinoBorder track, ChinoAccent fill, fraction coerced 0..1.
                if (resume != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(ChinoBorder),
                    )
                    if (resumeDurSec > 0) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(
                                    (resume.positionSec.toFloat() / resumeDurSec).coerceIn(0f, 1f),
                                )
                                .height(4.dp)
                                .background(ChinoAccent),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val label = episode.episodeNumber?.let { ep ->
                        episodeCode(episode.seasonNumber ?: 0, ep, lastEpisodeNumber.takeIf { several })
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
                        // A box says what it is where a row has its title: its
                        // episodes are listed under this line.
                        text = if (several) "${held.size} episodes · one file" else episode.title,
                        style = if (several) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (several) FontWeight.Normal else FontWeight.Medium,
                        color = if (several) ChinoFocus.text(ChinoMuted, focused) else Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    ((fileDurationMs + 30_000L) / 60_000L).toInt().takeIf { it > 0 }?.let {
                        Text(text = "${it}m", color = ChinoFocus.text(ChinoMuted, focused), style = MaterialTheme.typography.bodySmall)
                    }
                    if (resume != null) {
                        // Canonical remaining label: CEIL((dur - pos) / 60),
                        // floored at 1m. resumeDurSec already folds in the
                        // catalogue-runtime fallback; when even that's unknown
                        // there is no remaining to compute — plain "Resume".
                        val remainingMin = if (resumeDurSec > 0) {
                            ((resumeDurSec - resume.positionSec + 59) / 60).coerceAtLeast(1)
                        } else null
                        Text(
                            text = remainingMin?.let { "Resume · ${it}m left" } ?: "Resume",
                            color = ChinoAccent,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                if (several) {
                    // The episodes the file holds, each by number and title:
                    // text, not controls - the box is what plays.
                    held.forEach { e ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = e.episodeNumber?.toString().orEmpty(),
                                color = ChinoFocus.text(ChinoMuted, focused),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(24.dp).alignByBaseline(),
                            )
                            Text(
                                text = e.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = if (watched) ChinoFocus.text(ChinoMuted, focused) else Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).alignByBaseline(),
                            )
                        }
                    }
                }
                // A box lists its episodes; one episode's overview is not the file's.
                episode.overview?.takeIf { it.isNotBlank() && !several }?.let {
                    Text(
                        text = it,
                        color = ChinoText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // The row's actions line: watched, then add to list - the same
        // controls as the title page's, at its size.
        WatchedToggle(watched = watched, onToggle = onToggleWatched)
        AddToListControl(inWatchlist = inWatchlist, onToggleWatchlist = onToggleWatchlist, onChooseLists = onAddToList)
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
            Text(text = it.toString(), color = ChinoText, fontSize = 14.sp)
        }
        runtimeText?.let {
            if (year != null) Bullet()
            Text(text = it, color = ChinoText, fontSize = 14.sp)
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
                    tint = ChinoAccent,
                    modifier = Modifier.size(16.dp),
                )
                Text(text = String.format("%.1f", r), color = ChinoText, fontSize = 14.sp)
            }
        }
        kind?.takeIf { it.isNotBlank() }?.let { k ->
            Box(
                modifier = Modifier
                    .clip(RectangleShape)
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
    Text("•", color = ChinoMuted)
}

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
                    .clip(RectangleShape)
                    .background(ChinoBorderHi)
                    .border(BorderStroke(1.dp, ChinoBorder), RectangleShape)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(text = genre, color = ChinoText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FooterGrid(item: Item) {
    // The subtitle languages by name ("German", "No dialogue"), each once; a
    // track in no language by its label.
    val subtitleLabel = item.subtitles
        .mapNotNull { languageOrLabel(it.lang, it.label) }
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
        Text(text = header, color = ChinoMuted, fontSize = 14.sp)
        Text(
            text = value,
            color = ChinoText,
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
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
            contentPadding = shelfPadding(end = 32.dp, top = 12.dp, bottom = 12.dp),
            modifier = Modifier.shelfRoom().fillMaxWidth(),
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
    val focus = rememberItemFocus()
    ChinoCard(
        onClick = onClick,
        modifier = Modifier.width(180.dp),
        focus = focus,
    ) {
        Column(modifier = Modifier.background(ChinoSurface)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(270.dp)
                    .background(ChinoBg),
            ) {
                PosterImage(
                    model = posterUrl,
                    title = item.title,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    color = ChinoFocus.title(ChinoText, focus.focused),
                    fontSize = 13.sp,
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
                        item.year?.let { Text(it.toString(), color = meta, fontSize = 12.sp) }
                        ratingText?.let {
                            if (item.year != null) Text("•", color = meta, fontSize = 12.sp)
                            Text(it, color = ChinoAccent, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The title's credits, as chino-web's detail page lists them (groupCredits):
 * "Starring" — the actors in billing order, each card with the character
 * beneath the name — then the crew, one line per role ("Created by",
 * "Director", "Writers", "Music", …), the label beside a row of names. On a
 * 10-ft TV every name is its own DPAD focus target: DOWN walks from the
 * Starring row through the crew lines, LEFT/RIGHT along one. A credit with a
 * person id opens the Person / Filmography surface on OK; one without stays
 * display-only but still holds focus, so a row stays browsable with the
 * remote. The cast carries no portrait flag (only a person does), so the
 * cards show initials, as the web shows names only.
 */
@Composable
private fun CreditsSection(
    cast: List<CastMember>,
    onPersonSelected: (String) -> Unit,
) {
    val credits = remember(cast) { groupCredits(cast) }
    if (credits.actors.isEmpty() && credits.crew.isEmpty()) return
    Column(
        modifier = Modifier.padding(top = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (credits.actors.isNotEmpty()) {
            Text(
                text = "Starring",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
                contentPadding = shelfPadding(end = 32.dp, top = 4.dp, bottom = 4.dp),
                modifier = Modifier.shelfRoom().fillMaxWidth(),
            ) {
                // groupCredits lists a person once per role, so role + person
                // id (else name) keys a card uniquely; a name alone does not
                // (two actors can share one, and a duplicate key crashes the row).
                items(credits.actors, key = ::creditKey) { actor ->
                    ActorCard(
                        actor = actor,
                        onClick = actor.personId?.let { id -> { onPersonSelected(id) } },
                    )
                }
            }
        }
        if (credits.crew.isNotEmpty()) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                credits.crew.forEach { group -> CrewLine(group, onPersonSelected) }
            }
        }
    }
}

/** An actor: initials, name, and the character beneath it ("Old Thom /
 *  Narrator" when the catalog credits two). */
@Composable
private fun ActorCard(actor: CastMember, onClick: (() -> Unit)?) {
    // A card: a person, the initials for their artwork.
    val focus = rememberItemFocus()
    Column(
        modifier = Modifier
            .width(128.dp)
            .focusStyle(focus, FocusKind.Card)
            .creditTarget(onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(ChinoSurface),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initialsOf(actor.name),
                color = ChinoText,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
            )
        }
        Text(
            text = actor.name,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        actor.character?.takeIf { it.isNotBlank() }?.let { character ->
            Text(
                text = character,
                color = ChinoFocus.text(ChinoMuted, focus.focused),
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/** One crew role: its label ("Writers") beside a scrollable row of names. */
@Composable
private fun CrewLine(group: CreditGroup, onPersonSelected: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = group.label,
            color = ChinoMuted,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(160.dp),
        )
        // Chips far enough apart for a focused one's growth and ring.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ChinoFocus.Room),
            contentPadding = shelfPadding(end = 32.dp, top = 2.dp, bottom = 2.dp),
            modifier = Modifier.weight(1f).shelfRoom(),
        ) {
            items(group.people, key = ::creditKey) { person ->
                CrewName(
                    name = person.name,
                    onClick = person.personId?.let { id -> { onPersonSelected(id) } },
                )
            }
        }
    }
}

/** A crew name: a chip. */
@Composable
private fun CrewName(name: String, onClick: (() -> Unit)?) {
    val focus = rememberItemFocus()
    Box(
        modifier = Modifier
            .focusStyle(focus, FocusKind.Control, surface = ChinoSurface)
            .border(1.dp, ChinoBorder, RectangleShape)
            .creditTarget(onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = name,
            color = ChinoFocus.title(ChinoText, focus.focused),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A credit's single focus target: clickable (OK fires once, on key-up) when
 *  it opens a person, else merely focusable, so a name without a person id
 *  holds focus without reacting to OK. */
private fun Modifier.creditTarget(onClick: (() -> Unit)?): Modifier =
    if (onClick != null) clickable(onClick = onClick) else focusable()

/** Up to two initials from a person's name, e.g. "Greta Gerwig" -> "GG". */
private fun initialsOf(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (parts.isEmpty()) return "?"
    val first = parts.first().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
    val last = if (parts.size > 1) parts.last().firstOrNull()?.uppercaseChar()?.toString().orEmpty() else ""
    return (first + last).ifBlank { "?" }
}

@Composable
private fun Centered(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatHM(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
