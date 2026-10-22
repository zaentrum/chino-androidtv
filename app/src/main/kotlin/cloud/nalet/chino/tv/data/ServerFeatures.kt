package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the connected server offers that the app shows only where it does
 * (GET /api/config, its features, [fetch]): bug reports, which go to the
 * people who run the server only where it is set up to take them - a row
 * that offers to send one promises a recipient. Asked once a session: the
 * first answer holds until the app restarts, which a change of server does.
 * A server that does not say (older than the field) offers none; a request
 * that fails is none for now, and is asked again next time.
 */
class ServerFeatures(private val fetch: suspend () -> Boolean) {
    private val mutex = Mutex()

    @Volatile
    private var feedback: Boolean? = null

    /** Whether the server takes bug reports (features.feedback). */
    suspend fun feedback(): Boolean {
        feedback?.let { return it }
        return mutex.withLock {
            feedback?.let { return@withLock it }
            try {
                fetch().also { feedback = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
        }
    }
}
