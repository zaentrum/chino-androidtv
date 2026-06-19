package cloud.nalet.chino.tv.data.auth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the access/refresh-token lifecycle for the currently active account.
 *
 * - [validAccessTokenBlocking] is what the OkHttp interceptor calls on every request.
 *   It returns a token guaranteed to be valid for at least [REFRESH_SLACK_MS] longer,
 *   refreshing under a mutex if the cached one is too close to expiry.
 * - [forceRefreshBlocking] is the Authenticator's path on a 401: try once more with a
 *   fresh token, give up if that fails too (the user falls back to picker / AUTH).
 *
 * When the user switches accounts via the picker, [AccountStore.setActive] flips the
 * active id and subsequent calls here naturally read tokens for the new account —
 * no rewiring of the OkHttpClient needed.
 */
class TokenManager(
    private val accounts: AccountStore,
    private val oidc: OidcDeviceClient,
) {
    private val mutex = Mutex()

    fun validAccessTokenBlocking(): String? = runBlocking {
        val current = accounts.activeAccount.first() ?: return@runBlocking null
        if (System.currentTimeMillis() < current.expiresAtEpochMillis - REFRESH_SLACK_MS) {
            return@runBlocking current.accessToken
        }
        // Bind the refresh to THIS account's id — if the user switches
        // accounts via the picker between this read and the write below,
        // we still update the right row.
        val refreshingId = current.id
        mutex.withLock {
            val latest = accounts.activeAccount.first()
            // If the picker switched away while we were waiting, fall
            // through with whoever is now active (still need to refresh
            // their token if applicable).
            val target = latest?.takeIf { it.id == refreshingId } ?: latest ?: return@runBlocking null
            if (System.currentTimeMillis() < target.expiresAtEpochMillis - REFRESH_SLACK_MS) {
                return@runBlocking target.accessToken
            }
            val rt = target.refreshToken ?: return@runBlocking null
            val refreshed = oidc.refresh(rt) ?: return@runBlocking null
            accounts.updateTokensForBlocking(target.id, refreshed)
            refreshed.accessToken
        }
    }

    /** Called by the OkHttp Authenticator on a 401. Returns a fresh access_token, or null. */
    fun forceRefreshBlocking(): String? = runBlocking {
        mutex.withLock {
            val latest = accounts.activeAccount.first() ?: return@runBlocking null
            val rt = latest.refreshToken ?: return@runBlocking null
            val refreshed = oidc.refresh(rt) ?: return@runBlocking null
            accounts.updateTokensForBlocking(latest.id, refreshed)
            refreshed.accessToken
        }
    }

    companion object {
        // Refresh once we're within 60 s of expiry. Keycloak access tokens default to
        // 5 min so this is a comfortable margin.
        private const val REFRESH_SLACK_MS = 60_000L
    }
}
