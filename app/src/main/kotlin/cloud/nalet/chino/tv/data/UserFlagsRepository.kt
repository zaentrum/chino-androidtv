package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.AccountSignedOutException
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * In-memory cache of the user's named watchlists + likes shared across the
 * whole app. Mirrors chino-web's hooks/useUserFlags pattern: one fetch per
 * kind on first access, optimistic toggle, server reconciliation.
 *
 * Named lists: each user always has a default list ("Watchlist", isDefault).
 * We expose three derived StateFlows:
 *  - [savedItems]   — item ids in >=1 list.
 *  - [watchlist]    — item ids in the DEFAULT list only: what the bookmark
 *                     on a title's page, its episode rows, cards and Zap
 *                     shows and toggles (docs/chino, title actions).
 *  - [memberships]  — item id → the list ids that contain it (powers the
 *                     add-to-list picker checkmarks).
 * Plus [lists] for the watchlist surface chips.
 *
 * Lives in [AppContainer] so DetailScreen, library cards, and the watchlist
 * tab all see the same StateFlows.
 *
 * The cache holds one account's lists and likes: those of the choice of the
 * account that was active when they were loaded. Another account chosen on
 * who's watching starts it from nothing (change P.C16 of the specification),
 * and nothing read for one choice is shown under another: what is read is
 * published only once the choice is known to be the same.
 */
class UserFlagsRepository(
    private val api: ChinoApi,
    /** Where the toggles' requests run. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Who is active now, and which choice of them; blocking, so it is read
     *  off the main thread. */
    private val activeChoice: () -> ActiveChoice,
) {
    private val mutex = Mutex()

    /** The choice the cache holds, or null while it holds nothing. Written
     *  under [mutex]. */
    @Volatile
    private var cachedFor: ActiveChoice? = null

    /** How often the active account had changed in the newest choice
     *  [activeIs] found active: a press is made under the choice the cache
     *  holds only where that one is as new, never under one gone by then. */
    @Volatile
    private var newestChoice: Long = Long.MIN_VALUE

    private suspend fun choiceNow(): ActiveChoice = withContext(Dispatchers.IO) { activeChoice() }

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
            val choice = choiceNow()
            if (cachedFor != choice) {
                resetLocked()
                cachedFor = choice
            }
            // Read into locals: nothing is shown before the choice is checked.
            var lists: Lists? = null
            var flatWatchlist: Set<String>? = null
            var likes: Set<String>? = null
            if (!watchlistLoaded) {
                // The named-lists overview gives us the default list + its
                // item ids in one place; the flat /watchlist GET stays the
                // back-compat fallback if the lists endpoint isn't reachable.
                lists = runCatching { fetchLists() }.getOrNull()
                if (lists == null) flatWatchlist = runCatching { api.getWatchlist().items.toSet() }.getOrNull()
            }
            if (!likesLoaded) likes = runCatching { api.getLikes().items.toSet() }.getOrNull()
            // Chosen anew while they were read: they are another choice's, and
            // none of it is shown.
            if (choiceNow() != choice) {
                resetLocked()
                return@withLock
            }
            lists?.let(::publishLocked)
            flatWatchlist?.let { ids ->
                _watchlist.value = ids
                _savedItems.value = ids
                watchlistLoaded = true
            }
            likes?.let {
                _likes.value = it
                likesLoaded = true
            }
        }
    }

    /** The lists overview with what each list holds. */
    private class Lists(val lists: List<Watchlist>, val memberships: Map<String, Set<String>>, val defaultIds: Set<String>)

    /** Reads the lists overview + their contents; shows nothing. */
    private suspend fun fetchLists(): Lists {
        val lists = api.getWatchlists().lists
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
        return Lists(lists, membership.mapValues { it.value.toSet() }, defaultIds)
    }

    /** Shows [l]. Must hold [mutex]. */
    private fun publishLocked(l: Lists) {
        _lists.value = l.lists
        _memberships.value = l.memberships
        _savedItems.value = l.memberships.keys
        _watchlist.value = l.defaultIds
        watchlistLoaded = true
    }

    /** Reads the lists again and shows them, where the cache still holds the
     *  choice that is active once they are read. Must hold [mutex]. */
    private suspend fun reloadListsLocked() {
        val choice = cachedFor ?: return
        val lists = runCatching { fetchLists() }.getOrNull() ?: return
        if (cachedFor == choice && choiceNow() == choice) publishLocked(lists)
    }

    /** Forgets the cached lists and likes: the account they belong to is
     *  signed out, or another account is chosen. The next [warm] loads them
     *  for whoever is active then. */
    suspend fun clear() {
        mutex.withLock { resetLocked() }
    }

    /** [accountId] is the active account now: lists and likes of another are
     *  gone from the screens at once, before any screen of the new one asks,
     *  and forgotten once a read in flight for them has let go of the cache;
     *  a press from then on goes out under the account active as its request
     *  is made, not under theirs.
     *  Where the cache is the active choice's already - a change that came
     *  late, after the account was chosen again and loaded anew - nothing
     *  goes. Waits for nothing; reads the account store, so it is called off
     *  the main thread. */
    fun activeIs(accountId: String?) {
        val held = cachedFor
        val now = runCatching(activeChoice).getOrNull()
        // From here on a press is not made under a choice before the active
        // one, though the cache still holds it while a read for it holds the
        // cache - the reset below waits for that read.
        val newest = now?.changes ?: held?.takeIf { it.accountId != accountId }?.let { it.changes + 1 }
        if (newest != null && newest > newestChoice) newestChoice = newest
        val current = held != null && (if (now != null) held == now else held.accountId == accountId)
        if (current) return
        // Also where the cache holds nothing: a toggle made before any warm
        // shows in the flows all the same.
        emptyFlows()
        if (held == null) return
        scope.launch {
            // Whoever is active when this runs: a cache loaded anew for the
            // active choice in between stays.
            mutex.withLock { if (cachedFor.let { it != null && it != choiceNow() }) resetLocked() }
        }
    }

    /** The choice a press is made under: the one the cache holds, unless
     *  another was found active since ([activeIs]) - the cache may still hold
     *  the one before while a read for it runs - and then none. */
    private fun pressedUnder(): ActiveChoice? = cachedFor?.takeIf { it.changes >= newestChoice }

    /** The account a press was made under: the one of the choice it was
     *  made under ([pressedUnder]), or, without one, the one active as its
     *  request is made. Its request goes out with that account's bearer. */
    private fun madeUnder(choice: ActiveChoice?): RequestAccount {
        val id = (choice ?: activeChoice()).accountId ?: throw AccountSignedOutException("")
        return RequestAccount(id)
    }

    /** Undoes an optimistic change whose request failed, where the cache still
     *  holds the choice the change was made under: never into another's. */
    private suspend fun revertUnder(choice: ActiveChoice?, undo: () -> Unit) {
        mutex.withLock { if (cachedFor == choice) undo() }
    }

    /** Shows nothing. */
    private fun emptyFlows() {
        _lists.value = emptyList()
        _memberships.value = emptyMap()
        _savedItems.value = emptySet()
        _watchlist.value = emptySet()
        _likes.value = emptySet()
    }

    /** Holds nothing again. Must hold [mutex]. */
    private fun resetLocked() {
        emptyFlows()
        watchlistLoaded = false
        likesLoaded = false
        cachedFor = null
    }

    /** Force a refresh of the lists + memberships (e.g. after the watchlist
     *  surface mutates lists out-of-band). Safe to call off any screen. */
    fun refreshLists() {
        scope.launch { mutex.withLock { reloadListsLocked() } }
    }

    /** The bookmark's press: add [itemId] to the Watchlist (the default
     *  list) or take it off - that list only; other lists are left as they
     *  are, the picker's to change. Optimistic. */
    fun setWatchlist(itemId: String, present: Boolean) {
        val defId = defaultListId
        if (defId == null) {
            // Lists not warm yet — fall back to the legacy default-list route.
            val choice = pressedUnder()
            applyMembership(itemId, listId = LEGACY_DEFAULT, present = present)
            _watchlist.value = if (present) _watchlist.value + itemId else _watchlist.value - itemId
            scope.launch {
                runCatching {
                    val account = madeUnder(choice)
                    if (present) api.addToWatchlist(itemId, account) else api.removeFromWatchlist(itemId, account)
                }.onFailure {
                    revertUnder(choice) {
                        _watchlist.value = if (present) _watchlist.value - itemId else _watchlist.value + itemId
                        applyMembership(itemId, listId = LEGACY_DEFAULT, present = !present)
                    }
                }
            }
            return
        }
        setItemInList(defId, itemId, present)
    }

    /** Toggle an item's membership in a specific named list. Optimistic, with
     *  best-effort revert on failure. */
    fun setItemInList(listId: String, itemId: String, present: Boolean) {
        val choice = pressedUnder()
        applyMembership(itemId, listId, present)
        scope.launch {
            runCatching {
                val account = madeUnder(choice)
                if (present) api.addItemToWatchlist(listId, itemId, account)
                else api.removeItemFromWatchlist(listId, itemId, account)
            }.onFailure {
                revertUnder(choice) { applyMembership(itemId, listId, !present) }
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
        mutex.withLock { reloadListsLocked() }
        return created
    }

    /** Rename a list, then refresh. Returns false on failure. */
    suspend fun renameList(listId: String, name: String): Boolean {
        val ok = runCatching {
            api.renameWatchlist(listId, cloud.nalet.chino.tv.data.api.WatchlistNameBody(name.trim()))
        }.isSuccess
        if (ok) mutex.withLock { reloadListsLocked() }
        return ok
    }

    /** Delete a list (cannot delete the default), then refresh. */
    suspend fun deleteList(listId: String): Boolean {
        val ok = runCatching { api.deleteWatchlist(listId) }.isSuccess
        if (ok) mutex.withLock { reloadListsLocked() }
        return ok
    }

    fun setLike(itemId: String, present: Boolean) {
        val choice = pressedUnder()
        _likes.value = if (present) _likes.value + itemId else _likes.value - itemId
        scope.launch {
            runCatching {
                val account = madeUnder(choice)
                if (present) api.addLike(itemId, account) else api.removeLike(itemId, account)
            }.onFailure {
                revertUnder(choice) { _likes.value = if (present) _likes.value - itemId else _likes.value + itemId }
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
