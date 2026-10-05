package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the app knows of the signed-in person's notices. */
data class NoticesState(
    /** Whose notices these are: the account they were asked for, null before. */
    val account: String? = null,
    val list: NoticeList = NoticeList(),
    /** When chino-api last answered for [account] (epoch ms), 0 before. */
    val answeredAt: Long = 0L,
    /** Counts the changes made, so that an answer asked for before one is
     *  not laid over it. */
    val changes: Int = 0,
) {
    fun isFor(account: String?): Boolean = account != null && this.account == account

    /** The bell shows for [account]: portal-api answered for them. With
     *  `available` false it shows nothing — no bell, no count, no error. */
    fun showsFor(account: String?): Boolean = isFor(account) && list.available
}

/**
 * The signed-in person's notices, shared by the bell in every top bar and the
 * Notices screen. [refresh] asks chino-api (GET /v1/notices) — the bell does,
 * now, every minute while it is shown and when the app comes back — and a
 * change goes out as POST …/{id}/read, POST …/read-all or DELETE …/{id}. A
 * change shows once chino-api says it is made, so one portal-api could not
 * make (502) leaves the notice as it was; the list is then asked for again.
 *
 * The bearer is the active account's, so the state says whose notices it
 * holds: a refresh for another account starts from nothing, and nothing is
 * asked or changed for an account no longer active. Before a server is
 * connected [api] is null and nothing is asked: building this, or calling it
 * then, never builds the API client — which cannot exist without a server's
 * address (Telemetry's rule). Never throws but for cancellation.
 */
class NoticesRepository(
    /** The API to ask, or null while no server is connected. Asked on each
     *  call, never on construction. */
    private val api: suspend () -> ChinoApi?,
    /** The active account's id: whose bearer the API sends now. */
    private val activeAccount: suspend () -> String?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(NoticesState())
    val state: StateFlow<NoticesState> = _state.asStateFlow()

    /** One GET at a time: a second ask waits for the first, then finds its
     *  answer fresh. */
    private val fetch = Mutex()

    /** Asks for [account]'s notices, unless an answer for them came less
     *  than [MIN_REFRESH_MS] ago and this is not [force]d — the bells of two
     *  screens a hop apart ask once. With no answer at all (chino-api not
     *  reached), what is shown stays as it was. */
    suspend fun refresh(account: String, force: Boolean = false) {
        try {
            withContext(Dispatchers.IO) { fetch.withLock { fetchLocked(account, force) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No answer: the next ask tries again.
        }
    }

    private suspend fun fetchLocked(account: String, force: Boolean) {
        val api = api() ?: return
        if (activeAccount() != account) return
        // Another account's notices are not this one's: start from nothing.
        _state.update { if (it.account == account) it else NoticesState(account = account) }
        val asked = _state.value
        if (!force && asked.answeredAt != 0L && now() - asked.answeredAt < MIN_REFRESH_MS) return
        val answer = noticeListOf(api.notices())
        if (activeAccount() != account) return
        _state.update { s ->
            when {
                // Switched away, or changed meanwhile: this answer is stale.
                s.account != account || s.changes != asked.changes -> s
                // portal-api did not answer: the bell shows nothing, and what
                // was listed stays as it was.
                !answer.available -> s.copy(list = s.list.copy(available = false), answeredAt = now())
                else -> s.copy(list = answer, answeredAt = now())
            }
        }
    }

    /** Opening a notice reads it: POST /v1/notices/{id}/read, for an unread
     *  one. True once chino-api says it is read. */
    suspend fun read(account: String, notice: Notice): Boolean {
        if (!notice.isUnread || !isNoticeId(notice.id)) return false
        return change(account, { it.readNotice(notice.id) }) { markRead(it, notice.id, stamp()) }
    }

    /** Every notice read: POST /v1/notices/read-all. */
    suspend fun readAll(account: String): Boolean =
        change(account, { it.readAllNotices() }) { markAllRead(it, stamp()) }

    /** A notice deleted: DELETE /v1/notices/{id}. */
    suspend fun delete(account: String, notice: Notice): Boolean {
        if (!isNoticeId(notice.id)) return false
        return change(account, { it.deleteNotice(notice.id) }) { removeNotice(it, notice.id) }
    }

    /** Forgets the notices: the account they belong to is signed out. */
    fun clear() {
        _state.value = NoticesState()
    }

    /** Sends a change of [account]'s notices and, once chino-api says it is
     *  made, applies it to the list; otherwise asks for the list again. */
    private suspend fun change(
        account: String,
        send: suspend (ChinoApi) -> retrofit2.Response<Unit>,
        apply: (NoticeList) -> NoticeList,
    ): Boolean = withContext(Dispatchers.IO) {
        val api = api() ?: return@withContext false
        // The bearer is the active account's: never send a change of one
        // person's notices with another's.
        if (!_state.value.isFor(account) || activeAccount() != account) return@withContext false
        val made = try {
            send(api).let { response ->
                response.errorBody()?.close()
                response.isSuccessful
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (made) {
            _state.update { s ->
                if (s.account != account) s else s.copy(list = apply(s.list), changes = s.changes + 1)
            }
        } else {
            // Not made: the notice stays as it was; see how things are now.
            refresh(account, force = true)
        }
        made
    }

    /** Now, as RFC 3339 in UTC: when a notice read here was read. */
    private fun stamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(now()))

    companion object {
        /** The least time between two asks that are not forced. */
        const val MIN_REFRESH_MS = 10_000L
    }
}
