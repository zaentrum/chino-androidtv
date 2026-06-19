package cloud.nalet.chino.tv.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Decade buckets shown as filter chips (mirrors the library's DECADES). */
data class Decade(val label: String, val min: Int, val max: Int)

val BROWSE_DECADES = listOf(
    Decade("2020s", 2020, 2099),
    Decade("2010s", 2010, 2019),
    Decade("2000s", 2000, 2009),
    Decade("1990s", 1990, 1999),
    Decade("1980s", 1980, 1989),
    Decade("Older", 1900, 1979),
)

val BROWSE_RATINGS = listOf(8.0, 7.0, 6.0)

/** Sort options; label -> chino-api `sort` query value. */
val BROWSE_SORTS = listOf(
    "Title" to "title",
    "Newest added" to "newest",
    "Rating" to "rating",
    "Year (newest)" to "year",
)

data class BrowseFilters(
    val genre: String? = null,
    val decade: Decade? = null,
    val ratingMin: Double? = null,
    val sort: String = "title",
)

sealed interface BrowseUiState {
    data object Loading : BrowseUiState
    data class Ready(
        val items: List<Item>,
        val filters: BrowseFilters,
        val genres: List<String>,
        val baseUrl: String,
        val streamToken: String,
        val loadingMore: Boolean,
        val hasMore: Boolean,
    ) : BrowseUiState
    data class Error(val message: String) : BrowseUiState
}

/**
 * Dedicated Movies / Shows browse page backing model. Paged listItems for a
 * single [type] with genre / decade / rating / sort filters, matching the
 * chino-mobile BrowseScreen.
 */
class BrowseViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val userFlags: UserFlagsRepository,
    private val baseUrl: String,
    /** "movie" or "series" — fixed for the lifetime of this page. */
    private val type: String,
) : ViewModel() {
    private val _state = MutableStateFlow<BrowseUiState>(BrowseUiState.Loading)
    val state: StateFlow<BrowseUiState> = _state.asStateFlow()

    /** Item ids in >=1 watchlist — drives the poster "saved" badge. */
    val savedItems: StateFlow<Set<String>> = userFlags.savedItems

    private var filters = BrowseFilters()
    private var genres: List<String> = emptyList()
    private var nextToken: String? = null
    private var token: String = ""
    private var loadingMore = false

    init {
        telemetry.event("screen_view", extra = mapOf("screen" to "browse", "type" to type))
        viewModelScope.launch {
            genres = runCatching { api.listGenres().genres }.getOrDefault(emptyList())
            token = withContext(Dispatchers.IO) { streamTokens.valid() }
            reload(filters)
        }
        // Warm the shared lists/memberships cache so the "saved" badge reflects
        // server truth (a no-op when another screen already warmed it).
        viewModelScope.launch { userFlags.warm() }
    }

    fun setFilters(next: BrowseFilters) {
        if (next == filters) return
        filters = next
        reload(next)
    }

    private fun reload(f: BrowseFilters) {
        _state.value = BrowseUiState.Loading
        nextToken = null
        viewModelScope.launch {
            try {
                val page = api.listItems(
                    limit = PAGE_SIZE,
                    type = type,
                    genre = f.genre,
                    yearMin = f.decade?.min,
                    yearMax = f.decade?.max,
                    ratingMin = f.ratingMin,
                    sort = f.sort,
                )
                nextToken = page.nextPageToken
                _state.value = BrowseUiState.Ready(
                    items = page.items,
                    filters = f,
                    genres = genres,
                    baseUrl = baseUrl,
                    streamToken = token,
                    loadingMore = false,
                    hasMore = page.nextPageToken != null,
                )
            } catch (e: Exception) {
                _state.value = BrowseUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** Card-action watched toggle (web parity: useWatchedToggle on the card
     *  overflow menu). `markWatched` is the NEXT desired state — true POSTs to
     *  mark, false DELETEs to un-mark — the SAME endpoints the detail eye uses.
     *  Browse is NOT an unwatched-only surface, so the card stays put and just
     *  flips its green ✓ badge: optimistically stamp/clear watched_at on the
     *  matching grid item. Best-effort; a failed call leaves the optimistic
     *  state and the next reload reconciles. */
    fun toggleWatched(itemId: String, markWatched: Boolean) {
        telemetry.event(
            "watched_toggle",
            itemId = itemId,
            extra = mapOf("watched" to markWatched.toString(), "surface" to "browse"),
        )
        val current = _state.value as? BrowseUiState.Ready ?: return
        _state.value = current.copy(
            items = current.items.map {
                if (it.id == itemId) it.copy(watchedAt = if (markWatched) "now" else null) else it
            },
        )
        viewModelScope.launch {
            runCatching { if (markWatched) api.postWatched(itemId) else api.deleteWatched(itemId) }
        }
    }

    fun loadMore() {
        if (loadingMore) return
        val tk = nextToken ?: return
        val current = _state.value as? BrowseUiState.Ready ?: return
        loadingMore = true
        _state.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            try {
                val page = api.listItems(
                    pageToken = tk,
                    limit = PAGE_SIZE,
                    type = type,
                    genre = filters.genre,
                    yearMin = filters.decade?.min,
                    yearMax = filters.decade?.max,
                    ratingMin = filters.ratingMin,
                    sort = filters.sort,
                )
                nextToken = page.nextPageToken
                val now = _state.value as? BrowseUiState.Ready ?: return@launch
                _state.value = now.copy(
                    items = now.items + page.items,
                    hasMore = page.nextPageToken != null,
                    loadingMore = false,
                )
            } catch (_: Exception) {
                val now = _state.value as? BrowseUiState.Ready ?: return@launch
                _state.value = now.copy(loadingMore = false, hasMore = false)
            } finally {
                loadingMore = false
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 48

        fun factory(container: AppContainer, type: String) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrowseViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    userFlags = container.userFlags,
                    baseUrl = container.baseUrl,
                    type = type,
                ) as T
        }
    }
}
