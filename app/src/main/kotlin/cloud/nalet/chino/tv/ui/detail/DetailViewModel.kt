package cloud.nalet.chino.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.PlayCaps
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
        /** What Play starts on a series opened as a series: the episode the
         *  viewer is in the middle of, else the next one (see SeriesPlay.kt).
         *  Null for movies, for an episode entry (Play plays that episode)
         *  and when no episode could be found. */
        val seriesPlay: SeriesPlayTarget? = null,
    ) : DetailUiState
    data class Error(val message: String) : DetailUiState
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
) : ViewModel() {
    private val _state = MutableStateFlow<DetailUiState>(DetailUiState.Loading)
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    /** Item ids in >=1 list — drives the filled/empty "+" icon. */
    val savedItems: StateFlow<Set<String>> = userFlags.savedItems
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
                    runCatching { api.getProgress(itemId).positionSec }.getOrElse { 0 }
                }
                // "More like this" — independent of the item fetch, so kick it
                // off in parallel. Empty on failure (the shelf just hides).
                val similarDef = async {
                    runCatching { api.similar(itemId).items }.getOrDefault(emptyList())
                }
                val requested = itemDef.await()
                val resumeSec = progressDef.await()
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
                val seriesPlay = if (wantsSeriesPlay) {
                    seriesPlayFor(seasonsId, seasons, continueWatching.orEmpty(), nextEpisodeDef.await())
                } else null
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
                _state.value = DetailUiState.Error(e.message ?: e::class.java.simpleName)
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
            LastTouched(id, runCatching { api.getProgress(id).positionSec }.getOrDefault(0))
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
     *  very first resume after construction (init's load covers it), then
     *  re-fetches the continue-watching feed and the episode list on every
     *  later resume so the episode progress bars, the watched checks and the
     *  series' Play episode aren't stale after play → BACK (web self-heals
     *  via its query gen; TV mirrors its own Library shelf pattern).
     *  Best-effort: on failure we keep whatever's already on screen. */
    fun onScreenResumed() {
        if (!firstResumeConsumed) {
            firstResumeConsumed = true
            return
        }
        val ready = _state.value as? DetailUiState.Ready ?: return
        if (ready.item.kind != "series") return
        val seriesId = ready.item.id
        val wantsSeriesPlay = ready.focusEpisodeId == null
        viewModelScope.launch {
            val (continueWatching, seasons, next) = coroutineScope {
                val cw = async { runCatching { api.continueWatching().items }.getOrNull() }
                val eps = async { runCatching { api.seriesEpisodes(seriesId).seasons }.getOrNull() }
                val nx = async {
                    if (wantsSeriesPlay) runCatching { api.nextEpisode(seriesId) }.getOrNull() else null
                }
                Triple(cw.await(), eps.await(), nx.await())
            }
            val freshSeasons = seasons?.takeIf { it.isNotEmpty() }
            val seriesPlay = if (wantsSeriesPlay && continueWatching != null) {
                seriesPlayFor(seriesId, freshSeasons ?: ready.seasons, continueWatching, next)
            } else null
            val current = _state.value as? DetailUiState.Ready ?: return@launch
            _state.value = current.copy(
                seasons = freshSeasons ?: current.seasons,
                episodeResume = continueWatching?.let(::episodeResumeOf) ?: current.episodeResume,
                seriesPlay = seriesPlay ?: current.seriesPlay,
            )
        }
    }

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
     *  - An episode entry → that episode.
     *  - Series → [DetailUiState.Ready.seriesPlay], worked out on load: the
     *    episode the viewer is in the middle of, else the next one. Without
     *    one, the series id — it 404s in chino-stream, which surfaces the
     *    "no playable content" problem cleanly instead of doing nothing.
     *
     * Reason: series root ids don't have a master.m3u8; only episode ids do.
     * Calling Play on a series id used to 404 chino-stream and stall the
     * player at black.
     */
    fun playTarget(): String {
        val ready = _state.value as? DetailUiState.Ready ?: return itemId
        // Episode entry (we redirected to the parent series): Play the episode
        // the user navigated to, not the series' next-up.
        ready.focusEpisodeId?.let { return it }
        if (ready.item.kind != "series") return itemId
        return ready.seriesPlay?.episodeId ?: itemId
    }

    /** Plain "+"-press: add the item to the DEFAULT list when it's in no list,
     *  or (when already saved) remove it from every list. The specific-list
     *  picker is reached separately via [setItemInList]. */
    fun toggleWatchlist(present: Boolean) {
        val id = displayItemId
        telemetry.event(
            "watchlist_toggle",
            itemId = id,
            extra = mapOf("present" to present.toString()),
        )
        if (present) {
            userFlags.setWatchlist(id, true)
        } else {
            // The item is in >=1 list and the user pressed the filled icon —
            // clear it from all of them so the icon empties (web parity: the
            // plain toggle removes the "saved" state).
            val current = memberships.value[id].orEmpty()
            if (current.isEmpty()) userFlags.setWatchlist(id, false)
            else current.forEach { listId -> userFlags.setItemInList(listId, id, false) }
        }
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
     *  EpisodesList row). Flips the loaded episode's watchedAt optimistically
     *  inside the Ready.seasons list so the green check updates instantly, then
     *  POSTs/DELETEs. `watched` is the NEXT desired state (true = mark). */
    fun toggleEpisodeWatched(episodeId: String, watched: Boolean) {
        val ready = _state.value as? DetailUiState.Ready ?: return
        telemetry.event(
            "episode_watched_toggle",
            itemId = episodeId,
            extra = mapOf("watched" to watched.toString()),
        )
        val newSeasons = ready.seasons.map { season ->
            if (season.episodes.none { it.id == episodeId }) season
            else season.copy(
                episodes = season.episodes.map { ep ->
                    if (ep.id == episodeId) ep.copy(watchedAt = if (watched) "now" else null) else ep
                },
            )
        }
        _state.value = ready.copy(seasons = newSeasons)
        viewModelScope.launch {
            runCatching { if (watched) api.postWatched(episodeId) else api.deleteWatched(episodeId) }
        }
    }

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
                ) as T
        }
    }
}
