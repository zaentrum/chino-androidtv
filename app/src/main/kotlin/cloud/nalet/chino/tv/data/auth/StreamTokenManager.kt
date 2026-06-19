package cloud.nalet.chino.tv.data.auth

import cloud.nalet.chino.tv.data.api.ChinoApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Caches the chino-api-minted `?stream=` HMAC token across all consumers (poster
 * URLs, backdrop URLs, the video player). chino-api signs them with TTL ~6 h so we
 * mint once at app start and re-mint a few minutes before expiry.
 *
 * The chino-api endpoint requires a valid OIDC bearer (the Retrofit auth interceptor
 * handles that), so the token returned is per-user; rotating tokens on user switch
 * happens because [TokenStore.clear] forces a fresh device-flow + a brand new
 * StreamTokenManager instance via AppContainer reconstruction (we don't yet rotate
 * mid-session because there's no user-switch UI).
 */
class StreamTokenManager(private val api: ChinoApi) {
    private val mutex = Mutex()
    private val _current = MutableStateFlow<String?>(null)
    val current: StateFlow<String?> = _current.asStateFlow()
    private var expiresAtEpochMillis: Long = 0L

    /**
     * Returns a valid stream token. Synchronous because Coil's ImageRequest data
     * model + ExoPlayer URI builders prefer plain strings; the caller is the OkHttp
     * dispatcher or a coroutine, never the main thread directly.
     */
    fun valid(): String = runBlocking {
        val now = System.currentTimeMillis()
        _current.value?.takeIf { now < expiresAtEpochMillis - SLACK_MS }?.let { return@runBlocking it }
        mutex.withLock {
            val now2 = System.currentTimeMillis()
            _current.value?.takeIf { now2 < expiresAtEpochMillis - SLACK_MS }?.let { return@runBlocking it }
            val resp = api.mintStreamToken()
            val expiresAt = resp.expiresAt
                ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: (System.currentTimeMillis() + 6L * 60 * 60 * 1000)
            expiresAtEpochMillis = expiresAt
            _current.value = resp.token
            resp.token
        }
    }

    companion object {
        private const val SLACK_MS = 5L * 60 * 1000 // refresh 5 min before expiry
    }
}
