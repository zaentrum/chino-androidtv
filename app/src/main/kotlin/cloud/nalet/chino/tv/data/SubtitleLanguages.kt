package cloud.nalet.chino.tv.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException

/**
 * The subtitle languages the connected server is set up for
 * (GET /v1/subtitles/languages, [fetch]): what the subtitle menu lists after
 * the viewer's own language. Asked once a session: the first answer - its
 * languages, or none from a server older than the route (404) - holds until
 * the app restarts, which a change of server does. A request that fails
 * otherwise (no connection, the server's error) is no languages for the
 * playback that asked, and is asked again by the next one.
 */
class SubtitleLanguages(private val fetch: suspend () -> List<String>) {
    private val mutex = Mutex()

    @Volatile
    private var known: List<String>? = null

    suspend fun get(): List<String> {
        known?.let { return it }
        return mutex.withLock {
            known?.let { return@withLock it }
            try {
                fetch().map { it.trim() }.filter { it.isNotEmpty() }.distinct().also { known = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HttpException) {
                if (e.code() == 404) emptyList<String>().also { known = it } else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
