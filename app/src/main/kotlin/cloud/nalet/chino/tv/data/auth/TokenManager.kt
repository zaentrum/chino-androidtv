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
 *   fresh token, give up if that fails too: the account's sign-in lapsed ([onLapsed]),
 *   and the app goes to sign-in for it (SignInLapses).
 * - [tokenOf] and [forceRefreshOf] do the same for the account a request was made
 *   under, whether or not it is the active one; a lapse is said only where it is.
 *
 * When the user switches accounts via the picker, [AccountStore.setActive] flips the
 * active id and subsequent calls here naturally read tokens for the new account —
 * no rewiring of the OkHttpClient needed.
 */
class TokenManager(
    private val accounts: AccountStore,
    private val oidc: OidcDeviceClient,
    /** The account's sign-in lapsed: no refresh token, or the provider refused
     *  it. Not where the provider could not be reached - the refresh throws
     *  then, the connection's failure. */
    private val onLapsed: (accountId: String) -> Unit = {},
) : cloud.nalet.chino.tv.data.api.AccountBearers {
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
            val rt = latest.refreshToken ?: run {
                onLapsed(latest.id)
                return@runBlocking null
            }
            val refreshed = oidc.refresh(rt) ?: run {
                onLapsed(latest.id)
                return@runBlocking null
            }
            accounts.updateTokensForBlocking(latest.id, refreshed)
            refreshed.accessToken
        }
    }

    /** [accountId]'s access token, whether or not it is the active account,
     *  renewed where it is close to expiry: for a request made under it
     *  ([cloud.nalet.chino.tv.data.api.RequestAccount]). Null where it is no
     *  longer signed in on this device, or its sign-in lapsed: no refresh
     *  token, or the provider refused it - said where it is the active
     *  account's ([lapsedIfActive]). */
    override fun tokenOf(accountId: String): String? = runBlocking {
        val acct = accounts.accountBlocking(accountId) ?: return@runBlocking null
        if (System.currentTimeMillis() < acct.expiresAtEpochMillis - REFRESH_SLACK_MS) {
            return@runBlocking acct.accessToken
        }
        mutex.withLock {
            val latest = accounts.accountBlocking(accountId) ?: return@runBlocking null
            if (System.currentTimeMillis() < latest.expiresAtEpochMillis - REFRESH_SLACK_MS) {
                return@runBlocking latest.accessToken
            }
            renewLocked(latest)
        }
    }

    /** After a 401 to a request made under [accountId]: its fresh access
     *  token; null as [tokenOf]'s. */
    override fun forceRefreshOf(accountId: String): String? = runBlocking {
        mutex.withLock {
            val latest = accounts.accountBlocking(accountId) ?: return@runBlocking null
            renewLocked(latest)
        }
    }

    /** chino-api refused [accountId]'s fresh token too: its sign-in lapsed. */
    override fun refusedAfterRefresh(accountId: String) = lapsedIfActive(accountId)

    /** [account]'s access token renewed with its refresh token, and kept for
     *  it; null where it has none or the provider refused it: the sign-in
     *  lapsed ([lapsedIfActive]). Must hold [mutex]. */
    private suspend fun renewLocked(account: Account): String? {
        val refreshed = account.refreshToken?.let { oidc.refresh(it) }
        if (refreshed == null) {
            lapsedIfActive(account.id)
            return null
        }
        accounts.updateTokensForBlocking(account.id, refreshed)
        return refreshed.accessToken
    }

    /** The sign-in of [accountId] lapsed: said where it is the active
     *  account's, whose screens go to sign-in; another's is found out when
     *  it is chosen. */
    private fun lapsedIfActive(accountId: String) {
        if (accounts.snapshotBlocking().activeAccount?.id == accountId) onLapsed(accountId)
    }

    companion object {
        // Refresh once we're within 60 s of expiry. Keycloak access tokens default to
        // 5 min so this is a comfortable margin.
        private const val REFRESH_SLACK_MS = 60_000L
    }
}
