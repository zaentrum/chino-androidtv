package cloud.nalet.chino.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Person
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SearchUiState {
    data object Empty : SearchUiState                          // no query yet
    data object Searching : SearchUiState
    data class Results(
        // Movies + series merged into one grid (movies first), matching the
        // single merged results grid chino-mobile/web render. Rendered in the
        // SERVER's relevance order (exact > prefix > FTS rank > alpha) — no
        // client-side re-rank.
        val items: List<Item>,
        // Matching people for the "Cast & crew" row. Server-ranked; rendered
        // as-is. Empty when no people match (the row hides).
        val people: List<Person>,
        val baseUrl: String,
        val streamToken: String,
    ) : SearchUiState
    data object NoMatches : SearchUiState
    data class Error(val message: String) : SearchUiState
}

class SearchViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "search")) }
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Empty)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(q: String) {
        _query.value = q
        // Debounce 250ms so we don't hammer chino-api on every keystroke
        // (matches the mobile SearchScreen debounce).
        searchJob?.cancel()
        if (q.isBlank()) {
            _state.value = SearchUiState.Empty
            return
        }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.value = SearchUiState.Searching
            try {
                // Fan out movies + series + people in parallel. Movies+series are
                // merged into one grid (movies first) — chino-mobile/web render a
                // single results grid rather than per-type shelves. People feed
                // the "Cast & crew" row. The server already ranks each list
                // (exact > prefix > FTS rank > alpha), so we render every list in
                // the order chino-api returns — NO client-side relevance re-sort.
                val (titles, people) = coroutineScope {
                    val mDef = async { api.listItems(q = q, limit = PAGE_SIZE, type = "movie") }
                    val sDef = async { api.listItems(q = q, limit = PAGE_SIZE, type = "series") }
                    // People search is best-effort: an older chino-api without the
                    // endpoint (404) just leaves the row empty rather than failing
                    // the whole search.
                    val pDef = async {
                        runCatching { api.searchPeople(q = q, limit = PEOPLE_LIMIT).people }
                            .getOrDefault(emptyList())
                    }
                    (mDef.await().items + sDef.await().items) to pDef.await()
                }
                telemetry.event(
                    "search_submit",
                    extra = mapOf(
                        "q_len" to q.length.toString(),
                        "result_count" to titles.size.toString(),
                        "people_count" to people.size.toString(),
                    ),
                )
                _state.value = if (titles.isEmpty() && people.isEmpty()) {
                    SearchUiState.NoMatches
                } else {
                    SearchUiState.Results(
                        items = titles,
                        people = people,
                        baseUrl = baseUrl,
                        streamToken = streamTokens.valid(),
                    )
                }
            } catch (e: Exception) {
                _state.value = SearchUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    companion object {
        private const val PAGE_SIZE = 60
        // Cap the people row — it sits above the title grid, so a long list
        // would push the titles below the fold.
        private const val PEOPLE_LIMIT = 12

        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                ) as T
        }
    }
}
