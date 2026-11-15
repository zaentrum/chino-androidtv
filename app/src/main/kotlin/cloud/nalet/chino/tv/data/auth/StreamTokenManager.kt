package cloud.nalet.chino.tv.data.auth

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant

/**
 * Caches the chino-api-minted `?stream=` HMAC token across all consumers (poster
 * URLs, backdrop URLs, the video player). chino-api signs them with TTL ~6 h so we
 * mint once per account and re-mint a few minutes before expiry.
 *
 * The chino-api endpoint requires a valid OIDC bearer (the Retrofit auth interceptor
 * sends the active account's), so a token is that account's: it carries the
 * account's rights, an age cap among them. The token is therefore kept for the
 * choice of the account it was minted for alone. When another account is chosen
 * on who's watching, the next [valid] mints one for that account (change P.C16 of
 * the specification): nobody's streams and pictures are served under another
 * person's rights.
 *
 * The choice is read before the request and again after it: the bearer is read
 * on the HTTP client's own thread, so another account chosen in between, even
 * one chosen and left again, could otherwise label its token with the first. A
 * token whose choice changed while it was asked for is never kept or handed
 * out; it is asked for again.
 */
class StreamTokenManager(
    private val activeChoice: () -> ActiveChoice,
    private val mint: suspend () -> StreamTokenResponse,
) {
    /** [activeChoice] says who is active when a token is asked for, and which
     *  choice of them: the choice the token is kept for. */
    constructor(api: ChinoApi, activeChoice: () -> ActiveChoice) : this(activeChoice, { api.mintStreamToken() })

    /** A token, the choice it was minted for and when it runs out. One
     *  immutable value, so a reader never sees a token of one account with the
     *  account or expiry of another. */
    private data class Minted(val token: String, val choice: ActiveChoice, val expiresAtEpochMillis: Long)

    private val mutex = Mutex()

    /** The cached token; null while there is none. */
    @Volatile
    private var minted: Minted? = null

    /** The cached token, where it is [choice]'s and has time left. */
    private fun cached(choice: ActiveChoice, now: Long): String? =
        minted?.takeIf { it.choice == choice && now < it.expiresAtEpochMillis - SLACK_MS }?.token

    /**
     * Returns a valid stream token of the active account. Blocking, so call it
     * off the main thread (`withContext(Dispatchers.IO)`): it reads the account
     * store and, the first time for a choice, asks chino-api.
     */
    fun valid(): String = runBlocking {
        cached(activeChoice(), System.currentTimeMillis())?.let { return@runBlocking it }
        mutex.withLock {
            repeat(MINT_ATTEMPTS) {
                val choice = activeChoice()
                cached(choice, System.currentTimeMillis())?.let { return@runBlocking it }
                // Another account's token, or an old one: gone before the request, so
                // a request that fails leaves no token of somebody else's behind.
                minted = null
                val resp = mint()
                // Chosen anew while the token was asked for: whose bearer it was
                // asked with is not known, so it is neither kept nor handed out.
                if (activeChoice() != choice) return@repeat
                val expiresAt = resp.expiresAt
                    ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    ?: (System.currentTimeMillis() + 6L * 60 * 60 * 1000)
                minted = Minted(resp.token, choice, expiresAt)
                return@runBlocking resp.token
            }
            throw IllegalStateException("the active account changed while its stream token was asked for")
        }
    }

    /** Forgets the token: the account it was minted for is signed out. The
     *  next [valid] mints one for whoever is signed in then. */
    suspend fun clear() {
        mutex.withLock { minted = null }
    }

    companion object {
        private const val SLACK_MS = 5L * 60 * 1000 // refresh 5 min before expiry
        private const val MINT_ATTEMPTS = 3
    }
}
