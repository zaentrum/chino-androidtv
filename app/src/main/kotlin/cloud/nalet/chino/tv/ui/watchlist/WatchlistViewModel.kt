package cloud.nalet.chino.tv.ui.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.ListsByKind
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.api.loadFailureOf

/** Max posters a hub shelf renders before deferring to the See-all tile —
 *  matches web/mobile's hub cap. */
internal const val HUB_SHELF_CAP = 12

/** The two kinds a list is split into with Separate movies and shows on
 *  (section 8.8.1), in their order: what chino-api is asked for ([query]),
 *  and the row's name. Shows are everything else a list holds: its shows,
 *  its episodes, any other kind. */
enum class TitleKind(val query: String, val label: String) {
    Movies("movie", "movies"),
    Shows("series", "shows"),
}

/** One row of a list split by kind, as its one answer came. */
sealed interface KindRow {
    /** The answer: [count], the titles of the kind it holds; [items], the
     *  first of them resolved - [HUB_SHELF_CAP] on the watchlist, all of them
     *  in an opened list. */
    data class Loaded(val count: Int, val items: List<Item>) : KindRow

    /** The answer failed: the row says so on its own. */
    data class Failed(val failure: LoadFailure) : KindRow
}

/** A list's two rows, movies then shows, each as its own answer came: what
 *  one says is never mended by the other - a title may be in both, or in
 *  neither, until the list is loaded again ("Row by row", section 8.8.1). */
data class KindRows(val movies: KindRow, val shows: KindRow) {
    operator fun get(kind: TitleKind): KindRow = when (kind) {
        TitleKind.Movies -> movies
        TitleKind.Shows -> shows
    }

    fun with(kind: TitleKind, row: KindRow): KindRows = when (kind) {
        TitleKind.Movies -> copy(movies = row)
        TitleKind.Shows -> copy(shows = row)
    }

    /** Both answers came, and neither has a card to draw: nothing in them,
     *  or only titles the catalog has none of. */
    val empty: Boolean
        get() = (movies as? KindRow.Loaded)?.items?.isEmpty() == true &&
            (shows as? KindRow.Loaded)?.items?.isEmpty() == true
}

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
        /** With Separate movies and shows on: listId → its two rows. Null
         *  draws every list whole, as with the setting off. */
        val byKind: Map<String, KindRows>? = null,
    ) : WatchlistUiState

    /** What failed, for the error screen: what it says, and the reason. */
    data class Error(val failure: LoadFailure) : WatchlistUiState
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
 *
 * With the account's Separate movies and shows on, where the server fills a
 * list by kind (section 8.8.1), each list is asked for twice instead, its
 * movies and then its shows, and each row is what its answer said: a row
 * that couldn't load fails alone, and nothing of one answer is checked
 * against the other. The lists are asked for at once, alongside the setting
 * and the server's features, never after them: they are drawn with the
 * choice held for the account - the one last known on this device while it
 * is read, off where there is none or the server never said it fills lists
 * by kind - and again, once, where the answer differs.
 */
class WatchlistViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val userFlags: UserFlagsRepository,
    private val telemetry: Telemetry,
    private val baseUrl: String,
    /** Whether every list is drawn by kind for the active account (section
     *  8.8.1), as known now and as asked, and the account's choice as it
     *  changes - in settings, or read again: the lists are drawn anew where
     *  it no longer matches. */
    private val listsByKind: ListsByKind,
) : ViewModel() {
    init {
        telemetry.event("screen_view", extra = mapOf("screen" to "watchlist"))
        // The cards' bookmarks are the Watchlist's: warm it, as every screen
        // with cards does - this one may be the first after Home.
        viewModelScope.launch { userFlags.warm() }
    }

    private val _state = MutableStateFlow<WatchlistUiState>(WatchlistUiState.Loading)
    val state: StateFlow<WatchlistUiState> = _state.asStateFlow()

    /** Item ids in the Watchlist — drives the cards' Watchlist bookmark. */
    val savedItems: StateFlow<Set<String>> = userFlags.watchlist

    /** The list whose MORE view is open (null = the hub is showing). */
    private val _openListId = MutableStateFlow<String?>(null)
    val openListId: StateFlow<String?> = _openListId.asStateFlow()

    /** The open list's COMPLETE resolved items, newest-added first. */
    private val _openItems = MutableStateFlow<List<Item>>(emptyList())
    val openItems: StateFlow<List<Item>> = _openItems.asStateFlow()

    private val _openItemsLoading = MutableStateFlow(false)
    val openItemsLoading: StateFlow<Boolean> = _openItemsLoading.asStateFlow()

    /** With Separate movies and shows on: the open list's two parts, each
     *  COMPLETE as its answer came; null while the list is drawn whole. */
    private val _openKinds = MutableStateFlow<KindRows?>(null)
    val openKinds: StateFlow<KindRows?> = _openKinds.asStateFlow()

    /** How often a list was opened: an answer for one opened before is not
     *  shown. */
    private var opened = 0L

    /** Whether the open list was asked for by kind. */
    private var openByKind = false

    init { refresh() }

    init {
        // Separate movies and shows changed while the lists show - in
        // settings, or read again - or the server's word on lists by kind:
        // drawn anew, silently, an open list too.
        viewModelScope.launch {
            listsByKind.changes.collect { on -> if (on != null) redrawIfChanged() }
        }
    }

    /** The hub drawn again, silently, where it is drawn otherwise than the
     *  setting now says. */
    private fun redrawIfChanged() {
        val s = _state.value as? WatchlistUiState.Ready ?: return
        if (listsByKind.now() != (s.byKind != null)) refresh(showSpinner = false)
    }

    /** (Re)loads the hub. [showSpinner]=false reconciles silently — used when
     *  returning from the MORE view so the hub doesn't flash to Loading. */
    fun refresh(showSpinner: Boolean = true) {
        if (showSpinner) {
            _state.value = WatchlistUiState.Loading
            // A load of its own - the first, try again - asks for the setting
            // again, beside the lists rather than before them: it may have
            // been changed on another device. The lists are drawn with the
            // choice held meanwhile, and again where the answer differs: an
            // answer that changes what the lists are drawn by reaches the
            // collector above, which draws them anew, once.
            viewModelScope.launch { listsByKind.ask(fresh = true) }
        }
        viewModelScope.launch {
            try {
                val lists = api.getWatchlists().lists
                // As known once the lists are in: a setting answered first
                // is drawn at once.
                val byKind = listsByKind.now()
                // Per-list shelf loads fan out in parallel so the slowest
                // list gates the hub, not the sum.
                val shelves = if (byKind) emptyMap() else coroutineScope {
                    lists.map { list ->
                        async { list.id to loadItems(list.id, limit = HUB_SHELF_CAP) }
                    }.awaitAll().toMap()
                }
                val kinds = if (!byKind) null else coroutineScope {
                    lists.map { list ->
                        async { list.id to loadKindRows(list.id, limit = HUB_SHELF_CAP) }
                    }.awaitAll().toMap()
                }
                _state.value = WatchlistUiState.Ready(
                    lists = lists,
                    shelves = shelves,
                    baseUrl = baseUrl,
                    streamToken = withContext(Dispatchers.IO) { streamTokens.valid() },
                    byKind = kinds,
                )
                val open = _openListId.value
                if (open != null && lists.none { it.id == open }) {
                    // The open list vanished (deleted in another client) —
                    // drop the user back on the hub instead of stranding them.
                    _openListId.value = null
                    _openItems.value = emptyList()
                    _openKinds.value = null
                } else if (open != null && openByKind != byKind) {
                    // The setting changed while the list is open: it is
                    // opened again, split or whole as the hub now is.
                    openList(open)
                }
                // The setting answered while the rows were asked for: they
                // are drawn again with it.
                if (listsByKind.now() != byKind) refresh(showSpinner = false)
            } catch (e: Exception) {
                _state.value = WatchlistUiState.Error(loadFailureOf(e))
            }
        }
    }

    /** Opens [listId]'s MORE view (full grid) and loads its complete items:
     *  by kind where the hub is drawn so. */
    fun openList(listId: String) {
        _openListId.value = listId
        val byKind = (_state.value as? WatchlistUiState.Ready)?.byKind != null
        val ask = ++opened
        openByKind = byKind
        _openItemsLoading.value = true
        _openItems.value = emptyList()
        _openKinds.value = null
        viewModelScope.launch {
            val kinds = if (byKind) loadKindRows(listId, limit = null) else null
            val items = if (byKind) emptyList() else loadItems(listId, limit = null)
            // Another list opened since, or this one again: the answer is
            // not the open list's.
            if (opened != ask || _openListId.value != listId) return@launch
            _openKinds.value = kinds
            _openItems.value = items
            _openItemsLoading.value = false
        }
    }

    /** BACK from the MORE view to the hub. Shelves re-fetch silently so
     *  removals made from a detail page visited via the grid reconcile
     *  without flashing the whole hub. */
    fun closeList() {
        _openListId.value = null
        _openItems.value = emptyList()
        _openKinds.value = null
        _openItemsLoading.value = false
        refresh(showSpinner = false)
    }

    /** try again on a row of [listId] that couldn't load: that one row asked
     *  again - in the open list where [listId] is open, else on the hub -
     *  the other row left as it came. */
    fun retryRow(listId: String, kind: TitleKind) {
        val open = _openListId.value == listId
        viewModelScope.launch {
            val row = loadKind(listId, kind, limit = if (open) null else HUB_SHELF_CAP)
            if (open) {
                val parts = _openKinds.value
                if (parts != null && _openListId.value == listId) _openKinds.value = parts.with(kind, row)
            } else {
                val s = _state.value as? WatchlistUiState.Ready ?: return@launch
                val rows = s.byKind?.get(listId) ?: return@launch
                _state.value = s.copy(byKind = s.byKind + (listId to rows.with(kind, row)))
            }
        }
    }

    /** Resolves a list's item ids (newest-added first) to full Items —
     *  capped at [limit] when non-null. chino-api has no batched
     *  /v1/items?ids=… endpoint, so getItem() fans out in parallel; order
     *  is preserved by re-indexing against the id list, failures drop. */
    private suspend fun loadItems(listId: String, limit: Int?): List<Item> {
        val all = runCatching { api.getWatchlistDetail(listId).items }.getOrDefault(emptyList())
        return resolve(if (limit != null) all.take(limit) else all)
    }

    /** [ids] as Items, in their order; one that can't be had drops. */
    private suspend fun resolve(ids: List<String>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val byId = coroutineScope {
            ids.map { id -> async { runCatching { api.getItem(id) }.getOrNull() } }
                .awaitAll()
        }.filterNotNull().associateBy { it.id }
        return ids.mapNotNull { byId[it] }
    }

    /** A list's two rows, asked one after the other - movies, then shows -
     *  each kept as its answer came (section 8.8.1, "Row by row"). */
    private suspend fun loadKindRows(listId: String, limit: Int?): KindRows {
        val movies = loadKind(listId, TitleKind.Movies, limit)
        val shows = loadKind(listId, TitleKind.Shows, limit)
        return KindRows(movies, shows)
    }

    /** One row by [kind]: its count and its first [limit] titles, as the
     *  answer has them, or why it failed. */
    private suspend fun loadKind(listId: String, kind: TitleKind, limit: Int?): KindRow {
        val ids = try {
            api.getWatchlistOfKind(listId, kind.query).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return KindRow.Failed(loadFailureOf(e))
        }
        return KindRow.Loaded(count = ids.size, items = resolve(if (limit != null) ids.take(limit) else ids))
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
                    listsByKind = container.listsByKind,
                ) as T
        }
    }
}
