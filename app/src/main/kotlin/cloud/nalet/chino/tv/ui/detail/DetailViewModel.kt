package cloud.nalet.chino.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.PlayCaps
import cloud.nalet.chino.tv.data.ProgressSaves
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.NextEpisodeResponse
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.data.model.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.api.loadFailureOf

/** In-progress playback state for one episode row, extracted from the
 *  continue-watching feed. Only built for entries that are genuinely
 *  mid-watch (>30s in, not finished, not a substituted up-next row).
 *  durationSec can be 0 (unknown) — the episode row then falls back to the
 *  catalogue runtime (durationMs/1000) for the bar + remaining label. */
data class EpisodeResume(val positionSec: Int, val durationSec: Int)

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(
        val item: Item,
        val resumeSec: Int,
        val baseUrl: String,
        val streamToken: String,
        val seasons: List<Season>,
        /** episode id → in-progress position for the series episode rows —
         *  drives the thumbnail progress bar + "Resume · Xm left" hint.
         *  Best-effort (empty on fetch failure) and empty for movies. */
        val episodeResume: Map<String, EpisodeResume> = emptyMap(),
        /** "More like this" recommendations; empty when none scored. */
        val similar: List<Item> = emptyList(),
        /** When this detail was opened on an EPISODE id, we transparently load
         *  the parent SERIES here and set this to the originating episode id so
         *  DetailScreen expands its season and lands DPAD focus on that row —
         *  instead of a standalone episode page (chino-web parity). Null for a
         *  plain series / movie / person entry. */
        val focusEpisodeId: String? = null,
        /** What Play starts on a series, the episode its first button names:
         *  opened as a series, the episode the viewer is in the middle of,
         *  else the next one; opened on one of its episodes - a
         *  continue-watching card's - that episode, from its own position
         *  ([entryTarget]), until the viewer plays on. Null for movies and
         *  when no episode could be found (see SeriesPlay.kt). */
        val seriesPlay: SeriesPlayTarget? = null,
    ) : DetailUiState
    /** What failed, for the error screen: what it says, and the reason. */
    data class Error(val failure: LoadFailure) : DetailUiState
}

class DetailViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val userFlags: UserFlagsRepository,
    private val telemetry: Telemetry,
    private val baseUrl: String,
    val itemId: String,
    /** The caps a play sends, as the audio output is routed now: the
     *  pre-warm asks with the player's. */
    private val playCaps: () -> PlayCaps,
    /** The player's progress saves on their way: back from the player the
     *  page reads the position once they are answered. */
    private val progressSaves: ProgressSaves = ProgressSaves(),
) : ViewModel() {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Item ids in the Watchlist - what add to list's bookmark shows. */
    val watchlist: StateFlow<Set<String>> = userFlags.watchlist
    /** item id → list ids containing it — drives the add-to-list checkmarks. */
    val memberships: StateFlow<Map<String, Set<String>>> = userFlags.memberships
    /** The user's named lists (default first) for the add-to-list picker. */
    val lists: StateFlow<List<cloud.nalet.chino.tv.data.api.Watchlist>> = userFlags.lists
    val likes: StateFlow<Set<String>> = userFlags.likes

    /** The id of the item currently RENDERED — the parent SERIES after an
     *  episode→series redirect (see [load]), otherwise the entry [itemId].
     *  Item-level controls (watchlist / like / watched / add-to-list) must
     *  target THIS so an episode entry acts on the series, not the originating
     *  episode. Per-EPISODE controls (toggleEpisodeWatched) keep their own ids. */
    val displayItemId: String
        get() = (_state.value as? DetailUiState.Ready)?.item?.id ?: itemId

    /** The episode this page was opened on, where it shows the episode's
     *  series: what its first button plays - until the viewer plays on
     *  ([entrySeen]); from then null, and the series' state picks the
     *  episode, as on a series opened as a series. */
    private var entryEpisode: Item? = null

    /** What the page last read of what playing changes, while it plays
     *  [entryEpisode]: a resume that reads otherwise means the viewer played
     *  since. */
    private var entrySeen: SeriesSeen? = null

    init {
        telemetry.event("screen_view", itemId = itemId, extra = mapOf("screen" to "detail"))
        load()
        viewModelScope.launch { userFlags.warm() }
    }

    fun load() {
        _state.value = DetailUiState.Loading
        viewModelScope.launch {
            try {
                // Fetch item + progress in parallel. Once we know the kind we
                // *also* fetch episodes if it's a series — held off until after
                // getItem so we don't burn a request on movies that don't have
                // an episodes endpoint server-side.
                val itemDef = async { api.getItem(itemId) }
                val progressDef = async {
                    runCatching { api.getProgress(itemId).positionSec }.getOrNull()
                }
                // "More like this" — independent of the item fetch, so kick it
                // off in parallel. Empty on failure (the shelf just hides).
                val similarDef = async {
                    runCatching { api.similar(itemId).items }.getOrDefault(emptyList())
                }
                val requested = itemDef.await()
                val savedSec = progressDef.await()
                val resumeSec = savedSec ?: 0
                // Episode entry: an episode has no season overview of its own, so
                // transparently load its PARENT SERIES (item + season list) and
                // remember the originating episode id — DetailScreen then expands
                // that season and focus-highlights the row (chino-web parity),
                // rather than showing a lonely episode page. Falls back to the
                // episode item itself if the parent can't be resolved.
                val focusEpisodeId = requested.takeIf { it.kind == "episode" && it.parentId != null }?.id
                val parentId = requested.parentId
                val item = if (focusEpisodeId != null && parentId != null) {
                    runCatching { api.getItem(parentId) }.getOrDefault(requested)
                } else requested
                // The id whose season list we render — the parent series for an
                // episode entry, otherwise the item itself.
                val seasonsId = item.id
                val isSeries = item.kind == "series"
                // Play on a series opened as a series picks its episode from
                // the continue-watching feed, else from next-episode; an
                // episode entry plays that episode and needs neither.
                val wantsSeriesPlay = isSeries && focusEpisodeId == null
                // Seasons, the continue-watching feed and next-episode are
                // independent reads — fetched concurrently once the kind is
                // known so none of them serially delays Ready.
                val seasonsDef = async {
                    if (isSeries) {
                        runCatching { api.seriesEpisodes(seasonsId).seasons }.getOrDefault(emptyList())
                    } else emptyList()
                }
                val continueWatchingDef = async {
                    if (isSeries) runCatching { api.continueWatching().items }.getOrNull() else null
                }
                val nextEpisodeDef = async {
                    if (wantsSeriesPlay) runCatching { api.nextEpisode(seasonsId) }.getOrNull() else null
                }
                val seasons = seasonsDef.await()
                val continueWatching = continueWatchingDef.await()
                val episodeResume = continueWatching?.let(::episodeResumeOf).orEmpty()
                // Opened on an episode, the series' first button names that
                // episode: "resume S01E03", as from the series' own card -
                // until the viewer plays on (onScreenResumed).
                entryEpisode = requested.takeIf { focusEpisodeId != null && isSeries }
                entrySeen = entryEpisode?.let { SeriesSeen.of(seasonsId, savedSec, continueWatching, seasons) }
                val seriesPlay = when {
                    wantsSeriesPlay -> seriesPlayFor(seasonsId, seasons, continueWatching.orEmpty(), nextEpisodeDef.await())
                    else -> entryEpisode?.let { entryTarget(it, seasons, resumeSec) }
                }
                _state.value = DetailUiState.Ready(
                    item = item,
                    resumeSec = resumeSec,
                    baseUrl = baseUrl,
                    streamToken = streamTokens.valid(),
                    seasons = seasons,
                    episodeResume = episodeResume,
                    similar = similarDef.await(),
                    // Only keep the focus target if we actually landed on the
                    // series overview (found the parent + it is a series with
                    // seasons); otherwise there's no row to focus.
                    focusEpisodeId = focusEpisodeId
                        ?.takeIf { item.kind == "series" && seasons.isNotEmpty() },
                    seriesPlay = seriesPlay,
                )
                // Speculative pre-warm of chino-stream's transcode pipeline.
                // Hitting /play/info on the resolved play target now means the
                // pipeline decision + first-segment ffmpeg invocation can race
                // ahead of the user pressing Play. Worst case: user never
                // plays, we burned one cheap GET. Best case: when they hit
                // Play, master.m3u8 + the first .m4s are already cached and
                // playback starts in <1s instead of waiting for cold ffmpeg.
                launch { prewarmPipeline() }
            } catch (e: Exception) {
                _state.value = DetailUiState.Error(loadFailureOf(e))
            }
        }
    }

    /** Continue-watching feed → per-episode resume map. Canonical cross-client
     *  predicate ([isMidWatch]) — a row is mid-watch iff it wasn't substituted
     *  as "up next", has >30s of progress (matches the detail hero's own
     *  canResume threshold), and isn't within 60s of the end (finished). Rows
     *  with an unknown duration (durationSec <= 0) are KEPT — the episode row
     *  falls back to the catalogue runtime for the bar + remaining label. */
    private fun episodeResumeOf(rows: List<ContinueWatchingItem>): Map<String, EpisodeResume> =
        rows.filter { !it.upNext && isMidWatch(it.positionSec, it.durationSec) }
            .associate { it.id to EpisodeResume(it.positionSec, it.durationSec) }

    /** Play's episode for series [seriesId]: its continue-watching row when it
     *  has one, else from [next] (next-episode without `after`) and the saved
     *  position of the episode that answer went from — fetched only here,
     *  when the feed does not have the series. */
    private suspend fun seriesPlayFor(
        seriesId: String,
        seasons: List<Season>,
        continueWatching: List<ContinueWatchingItem>,
        next: NextEpisodeResponse?,
    ): SeriesPlayTarget? {
        continueWatchingTarget(seriesId, continueWatching)?.let { return it }
        val lastTouched = lastTouchedEpisodeId(next, seasons)?.let { id ->
            val progress = runCatching { api.getProgress(id) }.getOrNull()
            LastTouched(id, progress?.positionSec ?: 0, finished = progress?.finished == true)
        }
        return nextTarget(seasons, lastTouched, next?.next)
    }

    // True once the first screen ON_RESUME after VM construction has been
    // consumed. init{} already runs a full load(), so that first (synthetic,
    // delivered on observer registration) resume must not double-fetch. The
    // flag lives HERE — not in the composition — because DetailScreen leaves
    // composition while the Player sits on top and is recreated on BACK,
    // while this VM survives on the nav entry; a composition-local flag would
    // re-arm and swallow the "user came back" resume (the same stale-shelf
    // bug LibraryViewModel.firstResumeConsumed documents).
    private var firstResumeConsumed = false

    /** Screen-level ON_RESUME hook (NavBackStackEntry lifecycle). Skips the
     *  very first resume after construction (init's load covers it), then on
     *  every later resume re-reads what playing changes, so nothing is stale
     *  after play → BACK (web self-heals via its query gen; TV mirrors its
     *  own Library shelf pattern): the title's own saved position - a
     *  movie's "resume 3:10", an episode entry's - and a movie's watched
     *  mark; on a series the continue-watching feed and the episode list, so
     *  the episode progress bars, the watched checks and the episode its
     *  first button plays are the new ones. Opened on an episode, the first
     *  button names that episode until the viewer plays on - finishes it,
     *  plays another, here or on another device ([SeriesSeen.playedSince]):
     *  from then on it goes by the series' state, "play S01E04" after
     *  S01E03 was finished, as from the series' own card.
     *  It reads once the player's saves are answered
     *  ([ProgressSaves.settled]): the page resumes as the player goes, a
     *  frame before the player's last save leaves, and read at once it would
     *  say the position before it.
     *  Best-effort: on failure we keep whatever's already on screen. */
    fun onScreenResumed() {
        if (!firstResumeConsumed) {
            firstResumeConsumed = true
            return
        }
        val ready = _state.value as? DetailUiState.Ready ?: return
        val isSeries = ready.item.kind == "series"
        val seriesId = ready.item.id
        viewModelScope.launch {
            progressSaves.settled()
            val entry = entryEpisode
            val wantsSeriesPlay = isSeries && entry == null
            val fresh = coroutineScope {
                // The entry's own position: the movie's, the episode's the
                // page was opened on.
                val progress = async { runCatching { api.getProgress(itemId).positionSec }.getOrNull() }
                // A movie's watched mark: chino-api marks one finished in
                // the player watched.
                val item = async { if (!isSeries) runCatching { api.getItem(itemId) }.getOrNull() else null }
                val cw = async { if (isSeries) runCatching { api.continueWatching().items }.getOrNull() else null }
                val eps = async { if (isSeries) runCatching { api.seriesEpisodes(seriesId).seasons }.getOrNull() else null }
                val nx = async {
                    if (wantsSeriesPlay) runCatching { api.nextEpisode(seriesId) }.getOrNull() else null
                }
                Fresh(progress.await(), item.await(), cw.await(), eps.await(), nx.await())
            }
            val freshSeasons = fresh.seasons?.takeIf { it.isNotEmpty() }
            val seasons = freshSeasons ?: ready.seasons
            val resumeSec = fresh.positionSec ?: ready.resumeSec
            val seriesPlay = when {
                wantsSeriesPlay && fresh.continueWatching != null ->
                    seriesPlayFor(seriesId, seasons, fresh.continueWatching, fresh.next)
                entry != null -> entryPlayOn(entry, seriesId, seasons, fresh)
                else -> null
            }
            val current = _state.value as? DetailUiState.Ready ?: return@launch
            _state.value = current.copy(
                item = fresh.item?.takeIf { it.id == current.item.id } ?: current.item,
                resumeSec = resumeSec,
                seasons = freshSeasons ?: current.seasons,
                episodeResume = fresh.continueWatching?.let(::episodeResumeOf) ?: current.episodeResume,
                seriesPlay = seriesPlay ?: current.seriesPlay,
            )
        }
    }

    /** What the first button of a page opened on [entry] plays after a
     *  resume read [fresh]: the entry from its position now while the
     *  viewer has not played since; else - the feed read, so the series'
     *  state is known - the series' episode, as on a series opened as a
     *  series ([seriesPlayFor]), and from then on ([entryEpisode] null).
     *  Null where nothing could be read: the button stays as it is. */
    private suspend fun entryPlayOn(entry: Item, seriesId: String, seasons: List<Season>, fresh: Fresh): SeriesPlayTarget? {
        val now = SeriesSeen.of(seriesId, fresh.positionSec, fresh.continueWatching, fresh.seasons)
        val seen = entrySeen ?: now
        val playedSince = seen.playedSince(now)
        if (playedSince && fresh.continueWatching != null) {
            entryEpisode = null
            entrySeen = null
            // next-episode only where the feed has no row of the series.
            val next = if (continueWatchingTarget(seriesId, fresh.continueWatching) == null) {
                runCatching { api.nextEpisode(seriesId) }.getOrNull()
            } else null
            return seriesPlayFor(seriesId, seasons, fresh.continueWatching, next)
        }
        // Without the feed a play since waits for the next resume that
        // reads it: compared with what was seen before it.
        if (!playedSince) entrySeen = seen.with(now)
        return fresh.positionSec?.let { entryTarget(entry, seasons, it) }
    }

    /** What a resume re-read: each null where its read failed or was not
     *  asked. */
    private class Fresh(
        val positionSec: Int?,
        val item: Item?,
        val continueWatching: List<ContinueWatchingItem>?,
        val seasons: List<Season>?,
        val next: NextEpisodeResponse?,
    )

    private suspend fun prewarmPipeline() {
        runCatching {
            val target = playTarget()
            val caps = withContext(Dispatchers.IO) { playCaps().query }.ifEmpty { null }
            api.playInfo(target, caps = caps)
        }
    }

    /**
     * The catalogue id the player opens on.
     *
     *  - Movies → the item itself (chino-stream has playable HLS for the id).
     *  - Series → [DetailUiState.Ready.seriesPlay], the episode the first
     *    button names: the episode the viewer is in the middle of, else the
     *    next one - on a page opened on an episode, that episode until the
     *    viewer plays on. Without one, the entry episode, else the series id
     *    — it 404s in chino-stream, which surfaces the "no playable content"
     *    problem cleanly instead of doing nothing.
     *  - An episode whose series could not be loaded → that episode.
     *
     * Reason: series root ids don't have a master.m3u8; only episode ids do.
     * Calling Play on a series id used to 404 chino-stream and stall the
     * player at black.
     */
    fun playTarget(): String {
        val ready = _state.value as? DetailUiState.Ready ?: return itemId
        if (ready.item.kind != "series") return itemId
        return ready.seriesPlay?.episodeId ?: ready.focusEpisodeId ?: itemId
    }

    /** The second Resume goes on at in [playTarget]: a series' episode's, as
     *  Play picked it - the entry episode's own where the page was opened on
     *  one - else the title's own saved position. */
    fun resumeSecond(): Int {
        val ready = _state.value as? DetailUiState.Ready ?: return 0
        if (ready.item.kind == "series") return ready.seriesPlay?.positionSec ?: 0
        return ready.resumeSec
    }

    /** The bookmark of add to list: the rendered title into the Watchlist or
     *  out of it - the Watchlist alone, never another list (the "⌄" picker,
     *  [setItemInList], is where a title leaves those). */
    fun toggleWatchlist(present: Boolean) = setInWatchlist(displayItemId, present)

    /** [id] - the rendered title, or an episode of it - into the Watchlist or
     *  out of it. */
    fun setInWatchlist(id: String, present: Boolean) {
        telemetry.event(
            "watchlist_toggle",
            itemId = id,
            extra = mapOf("present" to present.toString()),
        )
        userFlags.setWatchlist(id, present)
    }

    /** Toggle membership in a specific named list (picker checkbox).
     *  [targetItemId] defaults to the rendered item (series action-row caret)
     *  but the per-episode "+" affordance passes the EPISODE id instead. */
    fun setItemInList(listId: String, present: Boolean, targetItemId: String = displayItemId) {
        telemetry.event(
            "watchlist_list_toggle",
            itemId = targetItemId,
            extra = mapOf("list" to listId, "present" to present.toString()),
        )
        userFlags.setItemInList(listId, targetItemId, present)
    }

    /** Create a new named list, and on success add [targetItemId] to it
     *  (defaults to the rendered item; episodes pass their own id).
     *  Returns true when both succeeded. */
    suspend fun createListAndAdd(name: String, targetItemId: String = displayItemId): Boolean {
        val created = userFlags.createList(name) ?: return false
        userFlags.setItemInList(created.id, targetItemId, true)
        return true
    }
    fun toggleLike(present: Boolean) {
        val id = displayItemId
        telemetry.event(
            "like_toggle",
            itemId = id,
            extra = mapOf("present" to present.toString()),
        )
        userFlags.setLike(id, present)
    }
    fun reportTrailerLaunch() = telemetry.event("trailer_launch", itemId = itemId)

    /** Toggle the item's watched state (web parity: useWatchedToggle).
     *  Reads the current Ready.item.watchedAt: if unwatched → POST to mark +
     *  optimistically stamp watchedAt so the control fills green; if watched →
     *  DELETE to un-mark + optimistically clear watchedAt so it returns to the
     *  outline state. Fire-and-forget; network errors leave the optimistic flip
     *  (web swallows the same — a stale view resolves on the next load). */
    fun toggleWatched() {
        val ready = _state.value as? DetailUiState.Ready ?: return
        val id = ready.item.id
        val next = ready.item.watchedAt == null
        telemetry.event(
            "watched_toggle",
            itemId = id,
            extra = mapOf("watched" to next.toString()),
        )
        _state.value = ready.copy(item = ready.item.copy(watchedAt = if (next) "now" else null))
        viewModelScope.launch {
            runCatching { if (next) api.postWatched(id) else api.deleteWatched(id) }
        }
    }

    /** Per-episode watched toggle in the series episode list (web parity:
     *  EpisodesList row): the file's episodes [ids], its holder first. Flips
     *  their watchedAt optimistically inside the Ready.seasons list so the
     *  green check updates instantly, then POSTs/DELETEs the holder's.
     *  `watched` is the NEXT desired state (true = mark). */
    fun toggleEpisodeWatched(ids: List<String>, watched: Boolean) {
        val ready = _state.value as? DetailUiState.Ready ?: return
        val holder = ids.firstOrNull() ?: return
        telemetry.event(
            "episode_watched_toggle",
            itemId = holder,
            extra = mapOf("watched" to watched.toString()),
        )
        // chino-api marks and un-marks every episode of a file together:
        // the row shows them all as they will be.
        val newSeasons = ready.seasons.map { season ->
            if (season.episodes.none { it.id in ids }) season
            else season.copy(
                episodes = season.episodes.map { ep ->
                    if (ep.id in ids) ep.copy(watchedAt = if (watched) "now" else null) else ep
                },
            )
        }
        _state.value = ready.copy(seasons = newSeasons)
        viewModelScope.launch {
            runCatching { if (watched) api.postWatched(holder) else api.deleteWatched(holder) }
        }
    }

    /** An episode's bookmark: into the Watchlist or out of it, as it is now. */
    fun toggleEpisodeWatchlist(id: String) = setInWatchlist(id, id !in watchlist.value)

    companion object {
        fun factory(container: AppContainer, itemId: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                DetailViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    userFlags = container.userFlags,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                    itemId = itemId,
                    playCaps = container::playCaps,
                    progressSaves = container.progressSaves,
                ) as T
        }
    }
}
