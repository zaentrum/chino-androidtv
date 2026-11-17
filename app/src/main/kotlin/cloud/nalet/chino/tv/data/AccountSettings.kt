package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.AccountSettingsChange
import cloud.nalet.chino.tv.data.api.AccountSettingsResponse
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.api.loadFailureOf
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Separate movies and shows as last known on this device, by account: what
 * the switch shows while the account's choice is read, and where that read
 * failed (section 16.4.7). Written whenever a read or a save for the account
 * succeeds; forgotten when the account leaves the device.
 */
interface LastKnownChoices {
    /** The account's choice as last read or saved here; null where none was. */
    fun of(accountId: String): Boolean?
    fun keep(accountId: String, on: Boolean)

    /** The account has left this device: its choice goes with it. */
    fun forget(accountId: String)
}

/** [LastKnownChoices] held in memory only. */
class LastKnownInMemory : LastKnownChoices {
    private val choices = ConcurrentHashMap<String, Boolean>()
    override fun of(accountId: String): Boolean? = choices[accountId]
    override fun keep(accountId: String, on: Boolean) { choices[accountId] = on }
    override fun forget(accountId: String) { choices.remove(accountId) }
}

/** [LastKnownChoices] kept in the app's own preferences, each account under a
 *  digest of its id rather than the id itself. */
class LastKnownOnDevice(private val prefs: android.content.SharedPreferences) : LastKnownChoices {
    private fun key(accountId: String): String =
        "watchlist_by_kind." + MessageDigest.getInstance("SHA-256")
            .digest(accountId.toByteArray()).joinToString("") { "%02x".format(it) }

    override fun of(accountId: String): Boolean? =
        key(accountId).let { if (prefs.contains(it)) prefs.getBoolean(it, false) else null }

    override fun keep(accountId: String, on: Boolean) {
        prefs.edit().putBoolean(key(accountId), on).apply()
    }

    override fun forget(accountId: String) {
        prefs.edit().remove(key(accountId)).apply()
    }
}

/**
 * The settings the account keeps on the server, the same on every device it
 * signs in on (change 16.C25 of the specification): here Separate movies and
 * shows, which splits every list into its movies and its shows (section
 * 8.8.1). Read and written through GET and PATCH /api/v1/me/settings only.
 *
 * What is held is one account's: that of the choice of the account that was
 * active when it was asked for (change P.C16). Another account chosen takes
 * it off the screens at once ([activeIs]); a read is shown only where the
 * choice it was made for is still the active one, and goes out with that
 * account's bearer; a save goes out under the account whose setting the
 * switch showed.
 *
 * As soon as the active account's choice is asked for, the one last known on
 * this device for that account is held ([lastKnown]; off where there is
 * none), without waiting for a read still out for the account before; while
 * it is read [reading] says so, and where the read failed that choice stays,
 * works and is what the lists follow until the choice is read again.
 */
class AccountSettings(
    private val api: ChinoApi,
    /** Where a save runs: the app's, so that a screen left while it is out
     *  does not take it with it. */
    private val scope: CoroutineScope,
    /** How often the active account has changed so far, read without the
     *  store ([cloud.nalet.chino.tv.data.auth.AccountStore.activeChangesNow]). */
    private val activeChanges: () -> Long,
    /** Each account's choice as last known on this device. */
    private val lastKnown: LastKnownChoices,
    /** Who is active now, and which choice of them; blocking, so it is read
     *  off the main thread. */
    private val activeChoice: () -> ActiveChoice,
) {
    /** One read at a time; a save's answer is kept under it too, so a read
     *  answered before the save was can't keep its older choice after it. */
    private val reads = Mutex()

    /** Held by every change of what is held, never across a request. */
    private val lock = Any()

    /** The choice [watchlistByKind] is of, or null while it holds nothing. */
    @Volatile
    private var heldFor: ActiveChoice? = null

    /** The choice that was read - answered, or failed - since it was held:
     *  what is held for it stands until it is read again. */
    @Volatile
    private var askedFor: ActiveChoice? = null

    /** The choice a read is out for, while one is. */
    @Volatile
    private var readingFor: ActiveChoice? = null

    /** Counts the reads and saves begun; an account forgotten is noted with
     *  the count then, and what was asked before is not kept for it. */
    private val asks = AtomicLong()
    private val forgottenAt = ConcurrentHashMap<String, Long>()

    private val _watchlistByKind = MutableStateFlow<Boolean?>(null)

    /** Separate movies and shows of the active account, as read or saved for
     *  it, or as last known on this device while it is read or where that
     *  read failed; null while nothing is held - not asked for yet, nobody
     *  signed in, or another account chosen since. */
    val watchlistByKind: StateFlow<Boolean?> = _watchlistByKind.asStateFlow()

    private val _reading = MutableStateFlow(false)

    /** Whether the active account's choice is being read. */
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    private suspend fun choiceNow(): ActiveChoice = withContext(Dispatchers.IO) { activeChoice() }

    /** Separate movies and shows of the active account: the one held for it,
     *  or read for it now - [fresh], read again though one was read, as it
     *  may have been changed on another device. Where that read fails, the
     *  one held stays: the one read before, or the one last known on this
     *  device (off where there is none). Null where nobody is signed in or
     *  another account was chosen while it was read. */
    suspend fun watchlistByKind(fresh: Boolean): Boolean? {
        // Held at once, also while a read for the account before is still
        // out: the switch and the lists show the one last known meanwhile.
        if (!hold(choiceNow())) return null
        return reads.withLock {
            val choice = choiceNow()
            val account = choice.accountId
            if (!hold(choice) || account == null) return@withLock null
            synchronized(lock) {
                if (!fresh && askedFor == choice) return@withLock _watchlistByKind.value
                readingFor = choice
                showReadingLocked()
            }
            val ask = asks.incrementAndGet()
            val (read, now) = try {
                val answer: AccountSettingsResponse? = try {
                    api.accountSettings(RequestAccount(account))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                answer to choiceNow()
            } catch (e: CancellationException) {
                synchronized(lock) { readDoneLocked() }
                throw e
            }
            synchronized(lock) {
                readDoneLocked()
                if (read != null) keepLocked(account, read.watchlistByKind, ask)
                // Chosen anew while it was read: it is another choice's, and
                // not shown.
                if (now != choice || heldFor != choice) {
                    if (heldFor == choice) resetLocked()
                    return@withLock null
                }
                askedFor = choice
                // Not read: the choice held stays, and works.
                if (read != null) _watchlistByKind.value = read.watchlistByKind
                _watchlistByKind.value
            }
        }
    }

    /** Holds the active account's setting as last known on this device, off
     *  where there is none, reading nothing: what settings and the lists show
     *  from their first frame until it is read. What is held for that
     *  account already stays; where nobody is signed in, nothing is held.
     *  Reads the account store off the main thread. */
    suspend fun holdActive() {
        hold(choiceNow())
    }

    /** Holds [choice]'s setting, or nothing where nobody is signed in;
     *  whether somebody is. */
    private fun hold(choice: ActiveChoice): Boolean = synchronized(lock) {
        if (choice.accountId == null) resetLocked() else holdLocked(choice)
        choice.accountId != null
    }

    /** The choice a press of the switch is made under: the one whose setting
     *  is shown, while no other account has been chosen since and it is not
     *  being read; else none, and nothing is sent. */
    fun pressedUnder(): ActiveChoice? = synchronized(lock) {
        val held = heldFor ?: return null
        if (_watchlistByKind.value == null || _reading.value) return null
        val changes = runCatching(activeChanges).getOrDefault(Long.MIN_VALUE)
        held.takeIf { it.changes >= changes }
    }

    /**
     * Saves Separate movies and shows as [on] for the account of [under] (from
     * [pressedUnder]), on the account settings' own scope rather than the
     * caller's: a screen left while it is out does not take it with it. The
     * request carries that account, whoever is active as it is sent. Kept, it
     * is the account's last known on this device and is shown where that
     * choice is still the active one; refused or not answered, what was shown
     * stays. The answer is null where it was kept, else why not, by its status
     * ([LoadFailure]) - never by the server's words or codes.
     */
    fun saveWatchlistByKind(on: Boolean, under: ActiveChoice): Deferred<LoadFailure?> =
        scope.async { save(on, under) }

    private suspend fun save(on: Boolean, under: ActiveChoice): LoadFailure? {
        val account = under.accountId
            ?: return LoadFailure(LoadFailure.Kind.SignedOut, "nobody was signed in")
        val ask = asks.incrementAndGet()
        val kept = try {
            api.changeAccountSettings(AccountSettingsChange(watchlistByKind = on), RequestAccount(account))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return loadFailureOf(e)
        }
        reads.withLock {
            val now = choiceNow()
            synchronized(lock) {
                keepLocked(account, kept.watchlistByKind, ask)
                if (heldFor == under && now == under) {
                    _watchlistByKind.value = kept.watchlistByKind
                    askedFor = under
                }
            }
        }
        return null
    }

    /** [accountId] is the active account now: the setting of another is gone
     *  from the screens at once, and a read still out for it is not shown
     *  when it is answered. Where what is held is the active choice's
     *  already, nothing goes. Reads the account store, so it is called off
     *  the main thread. */
    fun activeIs(accountId: String?) {
        val now = runCatching(activeChoice).getOrNull()
        synchronized(lock) {
            val held = heldFor ?: return
            val current = if (now != null) held == now else held.accountId == accountId
            if (!current) resetLocked()
        }
    }

    /** [accountId] has left this device - signed out, removed, deleted, or
     *  gone with a change of server: the choice last known for it goes, and
     *  so does what is held for it. A read or a save for it still out keeps
     *  nothing when it is answered. */
    fun forget(accountId: String) {
        synchronized(lock) {
            forgottenAt[accountId] = asks.incrementAndGet()
            lastKnown.forget(accountId)
            if (heldFor?.accountId == accountId) resetLocked()
        }
    }

    /** Holds [choice]'s setting where another's or none is held: the one last
     *  known on this device, off where there is none, until it is read. Must
     *  hold [lock]. */
    private fun holdLocked(choice: ActiveChoice) {
        val account = choice.accountId ?: return resetLocked()
        if (heldFor == choice) return
        heldFor = choice
        askedFor = null
        _watchlistByKind.value = lastKnown.of(account) ?: false
        showReadingLocked()
    }

    /** [on] kept as [account]'s last known, unless the account was forgotten
     *  after [ask] began. Must hold [lock]. */
    private fun keepLocked(account: String, on: Boolean, ask: Long) {
        if (ask > (forgottenAt[account] ?: 0L)) lastKnown.keep(account, on)
    }

    /** The read out is done. Must hold [lock]. */
    private fun readDoneLocked() {
        readingFor = null
        showReadingLocked()
    }

    /** [reading] for the choice held. Must hold [lock]. */
    private fun showReadingLocked() {
        _reading.value = readingFor != null && readingFor == heldFor
    }

    /** Holds nothing. Must hold [lock]. */
    private fun resetLocked() {
        heldFor = null
        askedFor = null
        _watchlistByKind.value = null
        showReadingLocked()
    }
}

/**
 * Whether every list is drawn by kind for the active account (section
 * 8.8.1): the server fills lists by kind (features.watchlist_kinds of
 * /api/config) and the account's Separate movies and shows is on. Off where
 * either can't be said.
 */
class ListsByKind(private val features: ServerFeatures, private val settings: AccountSettings) {
    /** Asks: the server's features, where they weren't answered yet, then the
     *  account's choice, where it wasn't read yet or [fresh], read again. */
    suspend fun ask(fresh: Boolean): Boolean =
        features.watchlistKinds() && settings.watchlistByKind(fresh) == true

    /** As far as is known now, asking nothing: off where the server never
     *  said it fills lists by kind - in this session, or before, as kept on
     *  this device - or nothing is held for the account; the choice held is
     *  the one last known on this device while it is read. */
    fun now(): Boolean = features.watchlistKindsNow() && settings.watchlistByKind.value == true

    /** [now] as what it is made of changes - the account's choice (read,
     *  saved, or another account's) and the server's word on lists by kind;
     *  null while nothing is held for the account. */
    val changes: Flow<Boolean?>
        get() = combine(features.watchlistKindsChanges, settings.watchlistByKind) { offered, on -> on?.let { offered && it } }
}
