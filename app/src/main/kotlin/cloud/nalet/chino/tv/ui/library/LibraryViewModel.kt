package cloud.nalet.chino.tv.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ProgressBody
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BrowseFilter(
    val genre: String? = null,
    val yearMin: Int? = null,
    val yearMax: Int? = null,
    val ratingMin: Double? = null,
    /** null = both movies + series; "movie" / "series" narrows. */
    val type: String? = null,
) {
    val isActive: Boolean get() = genre != null || yearMin != null || ratingMin != null || type != null
}

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data class Ready(
        /** Movies row — populated when filter.type is null or "movie". */
        val movies: List<Item>,
        /** Series row — populated when filter.type is null or "series". */
        val series: List<Item>,
        val continueWatching: List<cloud.nalet.chino.tv.data.api.ContinueWatchingItem>,
        val baseUrl: String,
        val streamToken: String,
        val moviesHasMore: Boolean,
        val seriesHasMore: Boolean,
        val loadingMore: Boolean,
        val genres: List<String>,
        val filter: BrowseFilter,
        // Curated rotation pool for the hero banner — top-rated items, picked
        // separately from the main rows so the hero stays consistent even
        // when the user applies a filter that narrows them.
        val heroPool: List<Item>,
        val heroIndex: Int,
        // Top Rated home rail — capped, non-paging (web/mobile parity,
        // HomeSection top-rated shelf). Empty when a Type filter is active
        // (it's a movies-only shelf, like web).
        val topRated: List<Item> = emptyList(),
    ) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}

class LibraryViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
) : ViewModel() {
    private val _state = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading)
    val state: StateFlow<LibraryUiState> = _state.asStateFlow()

    // Monotonic "user just (re)entered Home" signal. LibraryContent's
    // focus/align effect is keyed on this so it re-fires on EVERY home entry —
    // including the rail-Home-while-already-on-Home path, which does NOT
    // recompose LibraryContent and so can't rely on a fresh composition to
    // re-run the effect. The 9 navigation/back paths re-enter composition and
    // run the effect once anyway; this nonce closes the 10th (in-place) path.
    private val _homeEntryNonce = MutableStateFlow(0)
    val homeEntryNonce: StateFlow<Int> = _homeEntryNonce.asStateFlow()

    private var moviesNextToken: String? = null
    private var seriesNextToken: String? = null
    private var loadingMore = false
    private var cachedGenres: List<String> = emptyList()
    private var cachedHeroPool: List<Item> = emptyList()
    private var cachedTopRated: List<Item> = emptyList()
    private var currentFilter: BrowseFilter = BrowseFilter()
    // Set while the hero banner (Play / More Info) holds DPAD focus. The
    // rotation ticker honours it so the carousel never swaps the backdrop out
    // from under a focused button — a swap recreates the Crossfade content,
    // which would destroy the focused node and dump focus onto the rail.
    // Resumes the moment the user steps down to a shelf. (Same behaviour as
    // web pausing the hero carousel on hover/focus.)
    @Volatile private var heroRotationPaused = false

    init {
        telemetry.event("screen_view", extra = mapOf("screen" to "library"))
        refresh()
        // Independent ticker for hero rotation — doesn't restart on filter
        // changes so the hero feels alive even when the grid is reloading.
        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(HERO_ROTATE_MS)
                if (heroRotationPaused) continue
                val ready = _state.value as? LibraryUiState.Ready ?: continue
                if (ready.heroPool.size < 2) continue
                val next = (ready.heroIndex + 1) % ready.heroPool.size
                _state.value = ready.copy(heroIndex = next)
            }
        }
    }

    fun refresh() {
        _state.value = LibraryUiState.Loading
        moviesNextToken = null
        seriesNextToken = null
        viewModelScope.launch {
            _state.value = try {
                // Fan out: fetch movies + series pages in parallel when the user
                // hasn't narrowed by type. When they have, the off-axis row goes
                // empty (saves a redundant request). Same `genre/year/rating`
                // filters apply to both because they cross types semantically.
                val wantMovies = currentFilter.type == null || currentFilter.type == "movie"
                val wantSeries = currentFilter.type == null || currentFilter.type == "series"
                val (moviesPage, seriesPage) = coroutineScope {
                    val mDef = async {
                        if (!wantMovies) cloud.nalet.chino.tv.data.model.ItemsPage()
                        else api.listItems(
                            limit = LIBRARY_PAGE_SIZE,
                            genre = currentFilter.genre,
                            yearMin = currentFilter.yearMin,
                            yearMax = currentFilter.yearMax,
                            ratingMin = currentFilter.ratingMin,
                            type = "movie",
                            sort = "newest",
                            // Home "Recently Added Movies" rail hides finished
                            // titles + backfills (Browse/Search leave it off).
                            unwatched = true,
                        )
                    }
                    val sDef = async {
                        if (!wantSeries) cloud.nalet.chino.tv.data.model.ItemsPage()
                        else api.listItems(
                            limit = LIBRARY_PAGE_SIZE,
                            genre = currentFilter.genre,
                            yearMin = currentFilter.yearMin,
                            yearMax = currentFilter.yearMax,
                            ratingMin = currentFilter.ratingMin,
                            type = "series",
                            sort = "newest",
                            // Home "Recently Added Series" rail hides finished
                            // titles + backfills (Browse/Search leave it off).
                            unwatched = true,
                        )
                    }
                    mDef.await() to sDef.await()
                }
                moviesNextToken = moviesPage.nextPageToken
                seriesNextToken = seriesPage.nextPageToken
                // continue-watching is best-effort — empty list on failure (chino-api
                // can return an empty body if the table has no rows for this user).
                val cw = runCatching { api.continueWatching().items }.getOrDefault(emptyList())
                if (cachedGenres.isEmpty()) {
                    cachedGenres = runCatching { api.listGenres().genres }.getOrDefault(emptyList())
                }
                if (cachedHeroPool.isEmpty()) {
                    // Match chino-web's useHeroPool: 40 top-rated movies (rating
                    // >= 7) so the trailer carousel always has something good to
                    // foreground. Best-effort — fall back to first row items.
                    cachedHeroPool = runCatching {
                        api.listItems(
                            limit = HERO_POOL_SIZE,
                            type = "movie",
                            sort = "rating",
                            ratingMin = 7.0,
                            // Hero pool is a Home surface — foreground titles the
                            // user hasn't finished; backfill keeps it full.
                            unwatched = true,
                        ).items
                    }.getOrDefault(emptyList())
                }
                if (cachedTopRated.isEmpty()) {
                    // Top Rated home rail — capped at HOME_RAIL_SIZE, sorted by
                    // rating (web HomeSection top-rated: limit 20, ratingMin 8).
                    cachedTopRated = runCatching {
                        api.listItems(
                            limit = HOME_RAIL_SIZE,
                            type = "movie",
                            sort = "rating",
                            ratingMin = 8.0,
                            // Home "Top Rated" rail hides finished titles +
                            // backfills (Browse/Search leave it off).
                            unwatched = true,
                        ).items
                    }.getOrDefault(emptyList())
                }
                val rawPool = if (cachedHeroPool.isNotEmpty()) cachedHeroPool
                    else (moviesPage.items + seriesPage.items).take(1)
                // The slim list endpoint omits the description, so the hero
                // overview renders blank. Fan out parallel getItem(id) detail
                // fetches and copy the per-item overview onto each hero Item
                // (mirror chino-mobile). Run concurrently so the slowest single
                // call gates, not the sum; keep the original item on failure.
                val poolFallback = coroutineScope {
                    rawPool.map { item ->
                        async {
                            runCatching {
                                val detail = api.getItem(item.id)
                                item.copy(overview = detail.overview ?: item.overview)
                            }.getOrDefault(item)
                        }
                    }.map { it.await() }
                }
                val heroStart = if (poolFallback.size >= 2) {
                    (0 until poolFallback.size).random()
                } else 0
                // Stream-token mint is a blocking helper that does an OkHttp call;
                // run on IO so we don't block the main thread.
                val token = withContext(Dispatchers.IO) { streamTokens.valid() }
                LibraryUiState.Ready(
                    movies = moviesPage.items,
                    series = seriesPage.items,
                    continueWatching = cw,
                    baseUrl = baseUrl,
                    streamToken = token,
                    moviesHasMore = moviesPage.nextPageToken != null,
                    seriesHasMore = seriesPage.nextPageToken != null,
                    loadingMore = false,
                    genres = cachedGenres,
                    filter = currentFilter,
                    heroPool = poolFallback,
                    heroIndex = heroStart,
                    // Top Rated is a movies-only shelf — hide it when the user
                    // narrowed to series (web shows it only on the unfiltered home).
                    topRated = if (currentFilter.type == "series") emptyList() else cachedTopRated,
                )
            } catch (e: Exception) {
                LibraryUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** Pause/resume the hero carousel. Driven by the hero banner's focus
     *  state so a focused Play/More-Info button is never swapped away. */
    fun setHeroRotationPaused(paused: Boolean) {
        heroRotationPaused = paused
    }

    /** Rail "Home" pressed while already on the library. Bumps the home-entry
     *  nonce so the focus/align effect re-parks focus on the hero Play button
     *  (this path never navigates, so a fresh composition won't do it), and
     *  clears any active filter. Kept separate from applyFilter so that path
     *  stays pure — applyFilter early-returns when next == current, which would
     *  otherwise swallow the re-park when no filter is active. */
    fun onRailHome() {
        _homeEntryNonce.value += 1
        applyFilter(BrowseFilter()) // clears an active filter; no-op otherwise
    }

    /** Re-fetch just the continue-watching shelf. Called on ON_RESUME so
     *  finishing a movie / closing the player updates the row without a full
     *  grid reload (which would flicker the hero and reset scroll position).
     *  Best-effort: on failure we keep whatever's already on screen. */
    fun refreshContinueWatching() {
        val ready = _state.value as? LibraryUiState.Ready ?: return
        viewModelScope.launch {
            val cw = runCatching { api.continueWatching().items }.getOrNull() ?: return@launch
            val current = _state.value as? LibraryUiState.Ready ?: return@launch
            _state.value = current.copy(continueWatching = cw)
        }
    }

    /** "Remove from Continue Watching" (web parity: HomeSection dismiss).
     *  Marks the item watched (POST) so chino-api drops it from the
     *  continue-watching feed, then refreshes the shelf. Optimistically removes
     *  the card immediately so the row updates without waiting on the round-trip
     *  (web does the same — POST then refetch). Best-effort: a failed POST still
     *  leaves the optimistic removal; the next ON_RESUME refetch reconciles. */
    fun removeFromContinueWatching(itemId: String) {
        telemetry.event("continue_watching_remove", itemId = itemId)
        val ready = _state.value as? LibraryUiState.Ready ?: return
        val dur = ready.continueWatching.find { it.id == itemId }?.durationSec ?: 0
        _state.value = ready.copy(
            continueWatching = ready.continueWatching.filterNot { it.id == itemId },
        )
        viewModelScope.launch {
            // Stamp progress at the end (web's "dismiss" path) so the CW query
            // drops the row (it drops rows within 60s of duration) WITHOUT
            // marking a part-watched item fully WATCHED — postWatched would add a
            // green badge + drop it from Zap. Fall back to postWatched only when
            // the duration is unknown (can't compute the end position).
            runCatching {
                if (dur > 0) api.postProgress(itemId, ProgressBody(positionSec = dur, durationSec = dur))
                else api.postWatched(itemId)
            }
            // Reconcile from server truth — chino-api may surface a substituted
            // "Next Up" episode once the in-progress row leaves.
            val cw = runCatching { api.continueWatching().items }.getOrNull() ?: return@launch
            val current = _state.value as? LibraryUiState.Ready ?: return@launch
            _state.value = current.copy(continueWatching = cw)
        }
    }

    /** Card-action watched toggle (web parity: useWatchedToggle on the card
     *  overflow menu). `markWatched` is the NEXT desired state — true POSTs to
     *  mark, false DELETEs to un-mark — the SAME endpoints the detail eye uses.
     *  Optimistic: because every Home rail is fetched with `unwatched=true`
     *  (it hides finished titles), marking an item watched drops its card from
     *  whichever rail it sits in — same optimistic-remove feel as the
     *  Continue-Watching dismiss. Un-marking can't re-add a card to a capped
     *  rail (the server decides membership), so it's a no-op on screen until
     *  the next refresh; the network call still clears the watched flag.
     *  Best-effort: a failed call leaves the optimistic state; the next refresh
     *  reconciles from server truth. */
    fun toggleWatched(itemId: String, markWatched: Boolean) {
        telemetry.event(
            "watched_toggle",
            itemId = itemId,
            extra = mapOf("watched" to markWatched.toString(), "surface" to "home_rail"),
        )
        val ready = _state.value as? LibraryUiState.Ready ?: return
        if (markWatched) {
            // Optimistically drop the card from every Home rail + CW shelf — the
            // rails request unwatched=true so a finished title no longer belongs.
            _state.value = ready.copy(
                movies = ready.movies.filterNot { it.id == itemId },
                series = ready.series.filterNot { it.id == itemId },
                topRated = ready.topRated.filterNot { it.id == itemId },
                continueWatching = ready.continueWatching.filterNot { it.id == itemId },
            )
        } else {
            // Un-mark: flip the embedded watched flag where the card is still
            // visible (CW rows carry watched_at) so any badge updates instantly.
            _state.value = ready.copy(
                continueWatching = ready.continueWatching.map {
                    if (it.id == itemId) it.copy(watchedAt = null) else it
                },
            )
        }
        viewModelScope.launch {
            runCatching { if (markWatched) api.postWatched(itemId) else api.deleteWatched(itemId) }
        }
    }

    /** Replace the current filter and re-query. Drops pagination state. */
    fun applyFilter(next: BrowseFilter) {
        if (next == currentFilter) return
        telemetry.event(
            "library_filter_change",
            extra = mapOf(
                "genre" to (next.genre ?: ""),
                "year_min" to (next.yearMin?.toString() ?: ""),
                "year_max" to (next.yearMax?.toString() ?: ""),
                "rating_min" to (next.ratingMin?.toString() ?: ""),
                "type" to (next.type ?: ""),
            ),
        )
        currentFilter = next
        refresh()
    }

    /** Called by the movies shelf when focus gets within a screen of the end. */
    fun loadMoreMovies() = loadMore(isSeries = false)

    /** Called by the series shelf when focus gets within a screen of the end. */
    fun loadMoreSeries() = loadMore(isSeries = true)

    private fun loadMore(isSeries: Boolean) {
        if (loadingMore) return
        val token = (if (isSeries) seriesNextToken else moviesNextToken) ?: return
        val ready = _state.value as? LibraryUiState.Ready ?: return
        loadingMore = true
        _state.value = ready.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = api.listItems(
                    pageToken = token,
                    limit = LIBRARY_PAGE_SIZE,
                    genre = currentFilter.genre,
                    yearMin = currentFilter.yearMin,
                    yearMax = currentFilter.yearMax,
                    ratingMin = currentFilter.ratingMin,
                    type = if (isSeries) "series" else "movie",
                    sort = "newest",
                )
                if (isSeries) seriesNextToken = page.nextPageToken
                else moviesNextToken = page.nextPageToken
                val current = _state.value as? LibraryUiState.Ready ?: return@launch
                _state.value = if (isSeries) current.copy(
                    series = current.series + page.items,
                    seriesHasMore = page.nextPageToken != null,
                    loadingMore = false,
                ) else current.copy(
                    movies = current.movies + page.items,
                    moviesHasMore = page.nextPageToken != null,
                    loadingMore = false,
                )
            } catch (_: Exception) {
                val current = _state.value as? LibraryUiState.Ready ?: return@launch
                _state.value = if (isSeries) current.copy(loadingMore = false, seriesHasMore = false)
                    else current.copy(loadingMore = false, moviesHasMore = false)
            } finally {
                loadingMore = false
            }
        }
    }

    companion object {
        // Home rails are capped at HOME_RAIL_SIZE per #150 — 20 items + a
        // "See All" tile that jumps to the full Movies/Series overview. The
        // previous 60-per-page + infinite onLoadMore paging made each shelf
        // grow without end as the user D-padded right, which read as an
        // infinitely-looping rail. Full paging now lives on the BrowseScreen.
        private const val HOME_RAIL_SIZE = 20
        private const val LIBRARY_PAGE_SIZE = HOME_RAIL_SIZE
        // 8-item hero pool (mobile parity) — small enough that the hero
        // pagination dots read cleanly, rotating every 12s like the tablet.
        private const val HERO_POOL_SIZE = 8
        private const val HERO_ROTATE_MS = 12_000L

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                LibraryViewModel(container.chinoApi, container.streamTokenManager, container.telemetry, container.baseUrl) as T
        }
    }
}
