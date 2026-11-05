package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the connected server offers that the app shows only where it does
 * (GET /api/config, its features, [fetch]): bug reports, which go to the
 * people who run the server only where it is set up to take them - a row
 * that offers to send one promises a recipient - and a watchlist split by
 * kind. Asked once a session: the first answer holds until the app restarts,
 * which a change of server does. A server that does not say (older than the
 * field) offers none; a request that fails is none for now, and is asked again
 * next time.
 */
class ServerFeatures(private val fetch: suspend () -> ServerConfigResponse.Features) {
    private val mutex = Mutex()

    @Volatile
    private var features: ServerConfigResponse.Features? = null

    /** Whether the server takes bug reports (features.feedback). */
    suspend fun feedback(): Boolean = features().feedback

    /** Whether the server keeps the setting that splits a list by kind and can
     *  fill its rows: without both the setting is hidden. */
    suspend fun watchlistByKind(): Boolean = features().let { it.preferences && it.watchlistKinds }

    private suspend fun features(): ServerConfigResponse.Features {
        features?.let { return it }
        return mutex.withLock {
            features?.let { return@withLock it }
            try {
                fetch().also { features = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerConfigResponse.Features()
            }
        }
    }
}
