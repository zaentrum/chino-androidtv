package cloud.nalet.chino.tv.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.SLOT_SEARCH_EMPTY
import cloud.nalet.chino.tv.data.SlotActions
import cloud.nalet.chino.tv.data.SlotButton
import cloud.nalet.chino.tv.data.SlotKind
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Person
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.serverOrigin
import cloud.nalet.chino.tv.data.slotButtons
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.slots.SlotActionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

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
    private val slotActions: SlotActions,
    /** Where a slot action is sent from: the app's scope, so leaving the
     *  screen right after a press never drops the POST on its way out. */
    private val appScope: CoroutineScope,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "search")) }
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow<SearchUiState>(SearchUiState.Empty)
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** The server's origin, which the slot's rows resolve against. */
    private val origin = serverOrigin(baseUrl)

    /** The search.empty rows as chino-api served them, kept for the screen's
     *  life: asked the first time a search finds nothing (and again after an
     *  ask that failed). The rows do not depend on the query — {q} is
     *  substituted here. */
    private var emptySlotRows: JsonElement? = null

    private val _emptySlot = MutableStateFlow<List<SlotButton>>(emptyList())
    /** What addons offer under "No results" for the query that found
     *  nothing: none for a server with no addon. */
    val emptySlot: StateFlow<List<SlotButton>> = _emptySlot.asStateFlow()

    private val _slotActionStates = MutableStateFlow<Map<String, SlotActionState>>(emptyMap())
    /** How each action button's POST went, by key, for the buttons shown. */
    val slotActionStates: StateFlow<Map<String, SlotActionState>> = _slotActionStates.asStateFlow()

    fun onQueryChange(q: String) {
        _query.value = q
        // The slot's buttons belong to the query that found nothing.
        _emptySlot.value = emptyList()
        _slotActionStates.value = emptyMap()
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
                val nothing = titles.isEmpty() && people.isEmpty()
                _state.value = if (nothing) {
                    SearchUiState.NoMatches
                } else {
                    SearchUiState.Results(
                        items = titles,
                        people = people,
                        baseUrl = baseUrl,
                        streamToken = streamTokens.valid(),
                    )
                }
                // Nothing found: what addons offer instead, under "No
                // results". The message shows at once; the buttons join it.
                if (nothing) _emptySlot.value = emptySlotButtons(q)
            } catch (e: CancellationException) {
                // The next keystroke cut this search short: no failure.
                throw e
            } catch (e: Exception) {
                _state.value = SearchUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** The search.empty slot's buttons for [q]: none when the server has no
     *  addon, or its rows cannot be read just now. */
    private suspend fun emptySlotButtons(q: String): List<SlotButton> {
        val origin = origin ?: return emptyList()
        val rows = emptySlotRows ?: try {
            api.extensions(SLOT_SEARCH_EMPTY).also { emptySlotRows = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return slotButtons(rows, origin, mapOf("q" to q))
    }

    /** An action button pressed: its POST, one at a time and not again once
     *  the addon took it (a remote can deliver one press twice). A link's
     *  button shows its address on screen and sends nothing. */
    fun onSlotAction(button: SlotButton) {
        if (button.kind != SlotKind.Action) return
        when (_slotActionStates.value[button.key]) {
            SlotActionState.Sending, SlotActionState.Sent -> return
            SlotActionState.Failed, null -> Unit
        }
        val shown = _emptySlot.value
        _slotActionStates.update { it + (button.key to SlotActionState.Sending) }
        appScope.launch {
            val sent = slotActions.send(button.url)
            // A new query replaced the buttons: theirs start afresh.
            if (_emptySlot.value !== shown) return@launch
            _slotActionStates.update {
                it + (button.key to if (sent) SlotActionState.Sent else SlotActionState.Failed)
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
                    slotActions = container.slotActions,
                    appScope = container.appScope,
                ) as T
        }
    }
}
