package cloud.nalet.chino.tv.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.CodecCaps
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.data.model.Item
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DetailUiState {
    data object Loading : DetailUiState
    data class Ready(
        val item: Item,
        val resumeSec: Int,
        val baseUrl: String,
        val streamToken: String,
        val seasons: List<Season>,
        /** "More like this" recommendations; empty when none scored. */
        val similar: List<Item> = emptyList(),
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
                val item = itemDef.await()
                val resumeSec = progressDef.await()
                val seasons = if (item.kind == "series") {
                    runCatching { api.seriesEpisodes(itemId).seasons }.getOrDefault(emptyList())
                } else emptyList()
                _state.value = DetailUiState.Ready(
                    item = item,
                    resumeSec = resumeSec,
                    baseUrl = baseUrl,
                    streamToken = streamTokens.valid(),
                    seasons = seasons,
                    similar = similarDef.await(),
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

    private suspend fun prewarmPipeline() {
        runCatching {
            val target = resolvePlayTarget()
            val caps = CodecCaps.queryParam.ifEmpty { null }
            api.playInfo(target, caps = caps)
        }
    }

    /**
     * Returns the catalogue id we should actually open the player on.
     *
     *  - Movies → the item itself (chino-stream has playable HLS for the id).
     *  - Series → ask /v1/series/{id}/next-episode for the next-up episode;
     *    fall back to the first episode in the loaded seasons; finally fall
     *    back to the series id (will 404 in chino-stream — surfaces the
     *    "no playable content" problem cleanly instead of silently doing
     *    nothing).
     *
     * Reason: series root ids don't have a master.m3u8; only episode ids do
     * (web's DetailPage routes the same way). Calling Play on a series id
     * used to 404 chino-stream and stall the player at black.
     */
    suspend fun resolvePlayTarget(): String {
        val ready = _state.value as? DetailUiState.Ready ?: return itemId
        if (ready.item.kind != "series") return itemId
        val next = runCatching { api.nextEpisode(itemId).id }.getOrNull()
        if (!next.isNullOrBlank()) return next
        val firstEpisode = ready.seasons.firstOrNull()?.episodes?.firstOrNull()?.id
        return firstEpisode ?: itemId
    }

    /** Plain "+"-press: add the item to the DEFAULT list when it's in no list,
     *  or (when already saved) remove it from every list. The specific-list
     *  picker is reached separately via [setItemInList]. */
    fun toggleWatchlist(present: Boolean) {
        telemetry.event(
            "watchlist_toggle",
            itemId = itemId,
            extra = mapOf("present" to present.toString()),
        )
        if (present) {
            userFlags.setWatchlist(itemId, true)
        } else {
            // The item is in >=1 list and the user pressed the filled icon —
            // clear it from all of them so the icon empties (web parity: the
            // plain toggle removes the "saved" state).
            val current = memberships.value[itemId].orEmpty()
            if (current.isEmpty()) userFlags.setWatchlist(itemId, false)
            else current.forEach { listId -> userFlags.setItemInList(listId, itemId, false) }
        }
    }

    /** Toggle the item's membership in a specific named list (picker checkbox). */
    fun setItemInList(listId: String, present: Boolean) {
        telemetry.event(
            "watchlist_list_toggle",
            itemId = itemId,
            extra = mapOf("list" to listId, "present" to present.toString()),
        )
        userFlags.setItemInList(listId, itemId, present)
    }

    /** Create a new named list, and on success add the current item to it.
     *  Returns true when both succeeded. */
    suspend fun createListAndAdd(name: String): Boolean {
        val created = userFlags.createList(name) ?: return false
        userFlags.setItemInList(created.id, itemId, true)
        return true
    }
    fun toggleLike(present: Boolean) {
        telemetry.event(
            "like_toggle",
            itemId = itemId,
            extra = mapOf("present" to present.toString()),
        )
        userFlags.setLike(itemId, present)
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
        val next = ready.item.watchedAt == null
        telemetry.event(
            "watched_toggle",
            itemId = itemId,
            extra = mapOf("watched" to next.toString()),
        )
        _state.value = ready.copy(item = ready.item.copy(watchedAt = if (next) "now" else null))
        viewModelScope.launch {
            runCatching { if (next) api.postWatched(itemId) else api.deleteWatched(itemId) }
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
                ) as T
        }
    }
}
