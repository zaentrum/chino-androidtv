package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the connected server offers that the app shows only where it does
 * (GET /api/config, its features, [fetch]): bug reports, which go to the
 * people who run the server only where it is set up to take them - a row
 * that offers to send one promises a recipient - and lists by kind, which
 * the account's Separate movies and shows asks for. Asked once a session:
 * the first answer holds until the app restarts, which a change of server
 * does. A server that does not say (older than the field) offers none; a
 * request that fails is none for now, and is asked again next time.
 *
 * Lists by kind are known from the start: [kept] is what the server said of
 * them the last time it answered, kept on this device with the server, and
 * held until it answers in this session - before it does, and where its
 * answer fails; every answer is kept again through [keep].
 */
class ServerFeatures(
    /** features.watchlist_kinds as the server said it the last time it
     *  answered, kept on this device with the server; null where it never
     *  did. */
    kept: Boolean?,
    /** Keeps features.watchlist_kinds as the server says it now. */
    private val keep: suspend (Boolean) -> Unit,
    private val fetch: suspend () -> ServerConfigResponse.Features,
) {
    private val mutex = Mutex()

    @Volatile
    private var features: ServerConfigResponse.Features? = null

    /** What is kept on this device of features.watchlist_kinds. */
    @Volatile
    private var keptKinds: Boolean? = kept

    private val _watchlistKindsNow = MutableStateFlow(kept ?: false)

    /** [watchlistKindsNow] as it changes: the server's answer where it
     *  differs from what was kept. */
    val watchlistKindsChanges: StateFlow<Boolean> = _watchlistKindsNow.asStateFlow()

    private suspend fun features(): ServerConfigResponse.Features? {
        features?.let { return it }
        return mutex.withLock {
            features?.let { return@withLock it }
            val answer = try {
                fetch().also { features = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            answer?.let { keepKinds(it.watchlistKinds) }
            answer
        }
    }

    /** [on], the server's answer, followed and kept on this device where it
     *  isn't yet. The copy is a convenience: the answer stands without it. */
    private suspend fun keepKinds(on: Boolean) {
        _watchlistKindsNow.value = on
        if (keptKinds == on) return
        try {
            keep(on)
            keptKinds = on
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    /** Whether the server takes bug reports (features.feedback). */
    suspend fun feedback(): Boolean = features()?.feedback ?: false

    /** Whether the server keeps Separate movies and shows for an account and
     *  answers a list by kind (features.watchlist_kinds): asked where it
     *  hasn't answered in this session; where that ask fails, as it said
     *  before, kept on this device. */
    suspend fun watchlistKinds(): Boolean = features()?.watchlistKinds ?: keptKinds ?: false

    /** [watchlistKinds] as far as is known now, asking nothing: the server's
     *  answer in this session, else what it said before, kept on this
     *  device; off where it never said. */
    fun watchlistKindsNow(): Boolean = _watchlistKindsNow.value
}
