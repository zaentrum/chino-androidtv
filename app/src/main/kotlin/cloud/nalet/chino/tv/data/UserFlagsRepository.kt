package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.Watchlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory cache of the user's named watchlists + likes shared across the
 * whole app. Mirrors chino-web's hooks/useUserFlags pattern: one fetch per
 * kind on first access, optimistic toggle, server reconciliation.
 *
 * Named lists: each user always has a default list ("Watchlist", isDefault).
 * We expose three derived StateFlows:
 *  - [savedItems]   — item ids in >=1 list (drives the card "saved" badge +
 *                     the detail filled/empty icon).
 *  - [watchlist]    — item ids in the DEFAULT list only (back-compat: the
 *                     plain "+"-press default behaviour + legacy callers).
 *  - [memberships]  — item id → the list ids that contain it (powers the
 *                     add-to-list picker checkmarks).
 * Plus [lists] for the watchlist surface chips.
 *
 * Lives in [AppContainer] so DetailScreen, library cards, and the watchlist
 * tab all see the same StateFlows.
 */
class UserFlagsRepository(private val api: ChinoApi) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** All of the user's lists — default first, then others by createdAt asc. */
    private val _lists = MutableStateFlow<List<Watchlist>>(emptyList())
    val lists: StateFlow<List<Watchlist>> = _lists.asStateFlow()

    /** item id → list ids containing it. The authoritative membership cache. */
    private val _memberships = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val memberships: StateFlow<Map<String, Set<String>>> = _memberships.asStateFlow()

    /** Item ids in >=1 list — the "saved" set for badges + the detail icon. */
    private val _savedItems = MutableStateFlow<Set<String>>(emptySet())
    val savedItems: StateFlow<Set<String>> = _savedItems.asStateFlow()

    /** Item ids in the DEFAULT list (back-compat with the old single watchlist). */
    private val _watchlist = MutableStateFlow<Set<String>>(emptySet())
    val watchlist: StateFlow<Set<String>> = _watchlist.asStateFlow()
    private var watchlistLoaded = false

    private val _likes = MutableStateFlow<Set<String>>(emptySet())
    val likes: StateFlow<Set<String>> = _likes.asStateFlow()
    private var likesLoaded = false

    val defaultListId: String?
        get() = _lists.value.firstOrNull { it.isDefault }?.id

    /** Idempotent — call from screens that need the flags. Returns when cache is warm. */
    suspend fun warm() {
        mutex.withLock {
            if (!watchlistLoaded) {
                // The named-lists overview gives us the default list + its
                // item ids in one place; the flat /watchlist GET stays the
                // back-compat fallback if the lists endpoint isn't reachable.
                val loaded = runCatching { loadListsLocked() }.isSuccess
                if (!loaded) {
                    runCatching { api.getWatchlist().items }.onSuccess { ids ->
                        _watchlist.value = ids.toSet()
                        _savedItems.value = ids.toSet()
                        watchlistLoaded = true
                    }
                }
            }
            if (!likesLoaded) {
                runCatching { api.getLikes().items }.onSuccess {
                    _likes.value = it.toSet()
                    likesLoaded = true
                }
            }
        }
    }

    /** Re-pull the lists overview + their contents. Must hold [mutex]. */
    private suspend fun loadListsLocked() {
        val lists = api.getWatchlists().lists
        _lists.value = lists
        // Fan out the per-list contents so we can rebuild the membership map +
        // the default/saved sets. Lists are bounded (<=50) and each is a small
        // id array, so the serial walk stays cheap.
        val membership = mutableMapOf<String, MutableSet<String>>()
        var defaultIds: Set<String> = emptySet()
        for (list in lists) {
            val detail = runCatching { api.getWatchlistDetail(list.id) }.getOrNull() ?: continue
            if (list.isDefault) defaultIds = detail.items.toSet()
            for (id in detail.items) membership.getOrPut(id) { mutableSetOf() }.add(list.id)
        }
        _memberships.value = membership.mapValues { it.value.toSet() }
        _savedItems.value = membership.keys.toSet()
        _watchlist.value = defaultIds
        watchlistLoaded = true
    }

    /** Force a refresh of the lists + memberships (e.g. after the watchlist
     *  surface mutates lists out-of-band). Safe to call off any screen. */
    fun refreshLists() {
        scope.launch { mutex.withLock { runCatching { loadListsLocked() } } }
    }

    /** Plain "+"-press behaviour: add to the default list when the item is in
     *  no list, otherwise the caller opens the picker. Optimistic. */
    fun setWatchlist(itemId: String, present: Boolean) {
        val defId = defaultListId
        if (defId == null) {
            // Lists not warm yet — fall back to the legacy default-list route.
            applyMembership(itemId, listId = LEGACY_DEFAULT, present = present)
            _watchlist.value = if (present) _watchlist.value + itemId else _watchlist.value - itemId
            scope.launch {
                runCatching {
                    if (present) api.addToWatchlist(itemId) else api.removeFromWatchlist(itemId)
                }.onFailure {
                    _watchlist.value = if (present) _watchlist.value - itemId else _watchlist.value + itemId
                    applyMembership(itemId, listId = LEGACY_DEFAULT, present = !present)
                }
            }
            return
        }
        setItemInList(defId, itemId, present)
    }

    /** Toggle an item's membership in a specific named list. Optimistic, with
     *  best-effort revert on failure. */
    fun setItemInList(listId: String, itemId: String, present: Boolean) {
        applyMembership(itemId, listId, present)
        scope.launch {
            runCatching {
                if (present) api.addItemToWatchlist(listId, itemId)
                else api.removeItemFromWatchlist(listId, itemId)
            }.onFailure {
                applyMembership(itemId, listId, !present)
            }
        }
    }

    /** Mutate the membership map + the derived default/saved sets in place. */
    private fun applyMembership(itemId: String, listId: String, present: Boolean) {
        val current = _memberships.value.toMutableMap()
        val forItem = current[itemId]?.toMutableSet() ?: mutableSetOf()
        val wasPresent = listId in forItem
        if (present) forItem.add(listId) else forItem.remove(listId)
        if (forItem.isEmpty()) current.remove(itemId) else current[itemId] = forItem
        _memberships.value = current
        _savedItems.value = current.keys.toSet()
        // Keep the default-list back-compat set in sync.
        if (listId == defaultListId || listId == LEGACY_DEFAULT) {
            _watchlist.value = if (present) _watchlist.value + itemId else _watchlist.value - itemId
        }
        // Nudge the matching list's itemCount so chips reflect the change without
        // a full refetch — only when membership actually flipped.
        if (wasPresent != present) {
            val delta = if (present) 1 else -1
            _lists.value = _lists.value.map { l ->
                if (l.id != listId) l else l.copy(itemCount = (l.itemCount + delta).coerceAtLeast(0))
            }
        }
    }

    /** Create a new named list, then refresh. Returns the new list (or null on
     *  failure, e.g. duplicate name / too many lists). */
    suspend fun createList(name: String): Watchlist? {
        val created = runCatching {
            api.createWatchlist(cloud.nalet.chino.tv.data.api.WatchlistNameBody(name.trim()))
        }.getOrNull() ?: return null
        mutex.withLock { runCatching { loadListsLocked() } }
        return created
    }

    /** Rename a list, then refresh. Returns false on failure. */
    suspend fun renameList(listId: String, name: String): Boolean {
        val ok = runCatching {
            api.renameWatchlist(listId, cloud.nalet.chino.tv.data.api.WatchlistNameBody(name.trim()))
        }.isSuccess
        if (ok) mutex.withLock { runCatching { loadListsLocked() } }
        return ok
    }

    /** Delete a list (cannot delete the default), then refresh. */
    suspend fun deleteList(listId: String): Boolean {
        val ok = runCatching { api.deleteWatchlist(listId) }.isSuccess
        if (ok) mutex.withLock { runCatching { loadListsLocked() } }
        return ok
    }

    fun setLike(itemId: String, present: Boolean) {
        _likes.value = if (present) _likes.value + itemId else _likes.value - itemId
        scope.launch {
            runCatching {
                if (present) api.addLike(itemId) else api.removeLike(itemId)
            }.onFailure {
                _likes.value = if (present) _likes.value - itemId else _likes.value + itemId
            }
        }
    }

    private companion object {
        // Synthetic list id used only when the lists overview hasn't loaded yet
        // and we fall back to the legacy default-list PUT/DELETE routes. Never
        // sent to the server; it just keeps the optimistic membership map
        // consistent so the badge fills/empties.
        const val LEGACY_DEFAULT = "__default__"
    }
}
