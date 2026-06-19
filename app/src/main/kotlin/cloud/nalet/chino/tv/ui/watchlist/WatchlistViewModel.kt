package cloud.nalet.chino.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Max posters a hub shelf renders before deferring to the See-all tile —
 *  matches web/mobile's hub cap. */
internal const val HUB_SHELF_CAP = 12

sealed interface WatchlistUiState {
    data object Loading : WatchlistUiState

    /** The hub: the lists overview + one resolved shelf per list. */
    data class Ready(
        /** All of the user's lists — default first (GET /me/watchlists order). */
        val lists: List<Watchlist>,
        /** listId → the first [HUB_SHELF_CAP] resolved Items, newest-added
         *  first. A missing key means "still loading"; an empty value means
         *  the list is empty (hub renders the save-titles hint). */
        val shelves: Map<String, List<Item>>,
        val baseUrl: String,
        val streamToken: String,
    ) : WatchlistUiState

    data class Error(val message: String) : WatchlistUiState
}

/**
 * Backs the watchlist HUB + per-list MORE view, the cross-client lists
 * surface (web's WatchlistSection / mobile's WatchlistScreenModel). Loads
 * the lists overview, then fans out one GET /me/watchlists/{id} per list IN
 * PARALLEL (the home-rails fan-out idiom) and resolves each shelf's first
 * [HUB_SHELF_CAP] item ids to full Items via per-id getItem. Opening a list
 * loads its complete item set for the MORE grid. Create / rename / delete
 * go through the shared [UserFlagsRepository] so every other surface
 * (detail add-to-list picker, card badges) stays in sync.
 */
class WatchlistViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val userFlags: UserFlagsRepository,
    private val telemetry: Telemetry,
    private val baseUrl: String,
) : ViewModel() {
    init { telemetry.event("screen_view", extra = mapOf("screen" to "watchlist")) }

    private val _state = MutableStateFlow<WatchlistUiState>(WatchlistUiState.Loading)
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    /** Item ids belonging to >=1 list — drives the MORE-grid card badges. */
    val savedItems: StateFlow<Set<String>> = userFlags.savedItems

    /** The list whose MORE view is open (null = the hub is showing). */
    private val _openListId = MutableStateFlow<String?>(null)
    val openListId: StateFlow<String?> = _openListId.asStateFlow()

    /** The open list's COMPLETE resolved items, newest-added first. */
    private val _openItems = MutableStateFlow<List<Item>>(emptyList())
    val openItems: StateFlow<List<Item>> = _openItems.asStateFlow()

    private val _openItemsLoading = MutableStateFlow(false)
    val openItemsLoading: StateFlow<Boolean> = _openItemsLoading.asStateFlow()

    init { refresh() }

    /** (Re)loads the hub. [showSpinner]=false reconciles silently — used when
     *  returning from the MORE view so the hub doesn't flash to Loading. */
    fun refresh(showSpinner: Boolean = true) {
        if (showSpinner) _state.value = WatchlistUiState.Loading
        viewModelScope.launch {
            try {
                val lists = api.getWatchlists().lists
                // Per-list shelf loads fan out in parallel so the slowest
                // list gates the hub, not the sum.
                val shelves = coroutineScope {
                    lists.map { list ->
                        async { list.id to loadItems(list.id, limit = HUB_SHELF_CAP) }
                    }.awaitAll().toMap()
                }
                _state.value = WatchlistUiState.Ready(
                    lists = lists,
                    shelves = shelves,
                    baseUrl = baseUrl,
                    streamToken = streamTokens.valid(),
                )
                // The open list vanished (deleted in another client) — drop
                // the user back on the hub instead of stranding them.
                if (_openListId.value != null && lists.none { it.id == _openListId.value }) {
                    _openListId.value = null
                    _openItems.value = emptyList()
                }
            } catch (e: Exception) {
                _state.value = WatchlistUiState.Error(e.message ?: e::class.java.simpleName)
            }
        }
    }

    /** Opens [listId]'s MORE view (full grid) and loads its complete items. */
    fun openList(listId: String) {
        _openListId.value = listId
        viewModelScope.launch {
            _openItemsLoading.value = true
            _openItems.value = emptyList()
            _openItems.value = loadItems(listId, limit = null)
            _openItemsLoading.value = false
        }
    }

    /** BACK from the MORE view to the hub. Shelves re-fetch silently so
     *  removals made from a detail page visited via the grid reconcile
     *  without flashing the whole hub. */
    fun closeList() {
        _openListId.value = null
        _openItems.value = emptyList()
        refresh(showSpinner = false)
    }

    /** Resolves a list's item ids (newest-added first) to full Items —
     *  capped at [limit] when non-null. chino-api has no batched
     *  /v1/items?ids=… endpoint, so getItem() fans out in parallel; order
     *  is preserved by re-indexing against the id list, failures drop. */
    private suspend fun loadItems(listId: String, limit: Int?): List<Item> {
        val all = runCatching { api.getWatchlistDetail(listId).items }.getOrDefault(emptyList())
        val ids = if (limit != null) all.take(limit) else all
        if (ids.isEmpty()) return emptyList()
        val byId = coroutineScope {
            ids.map { id -> async { runCatching { api.getItem(id) }.getOrNull() } }
                .awaitAll()
        }.filterNotNull().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    fun createList(name: String) {
        viewModelScope.launch {
            val created = userFlags.createList(name)
            telemetry.event("watchlist_create", extra = mapOf("ok" to (created != null).toString()))
            // The new (empty) shelf appears at the end of the hub.
            refresh(showSpinner = false)
        }
    }

    fun renameList(listId: String, name: String) {
        viewModelScope.launch {
            val ok = userFlags.renameList(listId, name)
            telemetry.event("watchlist_rename", extra = mapOf("ok" to ok.toString()))
            refresh(showSpinner = false)
        }
    }

    /** Deletes a list. The server refuses to delete the default list (the UI
     *  never offers it there). Deleting the OPEN list drops back to the hub. */
    fun deleteList(listId: String) {
        viewModelScope.launch {
            val ok = userFlags.deleteList(listId)
            telemetry.event("watchlist_delete", extra = mapOf("ok" to ok.toString()))
            if (ok && _openListId.value == listId) {
                _openListId.value = null
                _openItems.value = emptyList()
            }
            refresh(showSpinner = false)
        }
    }

    companion object {
        fun factory(container: AppContainer) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                WatchlistViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    userFlags = container.userFlags,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                ) as T
        }
    }
}
