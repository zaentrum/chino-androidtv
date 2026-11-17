package cloud.nalet.chino.tv.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object RetrofitFactory {
    /** How every chino-api response is decoded (internal: the DTO contract
     *  test decodes with exactly this). */
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /**
     * @param tokenProvider returns the current valid access token (auto-refreshed when close to expiry).
     * @param forceRefresh called by the Authenticator on a 401 — returns a freshly minted token, or
     *        null if the refresh failed (server returns 401, the caller drops to AUTH).
     * @param accounts the bearer of the account a request carries ([RequestAccount]), in place
     *        of the active account's. Always given: a client without it sends no such request
     *        ([AccountBearers.None], for one that never makes any).
     */
    fun create(
        baseUrl: String,
        tokenProvider: () -> String?,
        forceRefresh: () -> String?,
        accounts: AccountBearers,
        onRefusedAfterRefresh: () -> Unit = {},
    ): Retrofit = retrofit(baseUrl, httpClient(tokenProvider, forceRefresh, accounts, onRefusedAfterRefresh).build())

    /** Retrofit over [client], decoding as every chino-api response is decoded. */
    internal fun retrofit(baseUrl: String, client: OkHttpClient): Retrofit =
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    /** The client an addon's slot action is sent with
     *  ([cloud.nalet.chino.tv.data.SlotActions]): the bearer and the refresh
     *  of every call - an action carries the account it was pressed under
     *  ([RequestAccount]) and goes out with that account's - but it follows
     *  no redirect: one could carry the bearer somewhere the URL check never
     *  saw. */
    fun actionClient(
        tokenProvider: () -> String?,
        forceRefresh: () -> String?,
        accounts: AccountBearers,
    ): OkHttpClient = httpClient(tokenProvider, forceRefresh, actionBearers(accounts))
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** [accounts] for an addon's actions: an addon that refuses an action
     *  with the renewed token too has refused the action, not the person's
     *  sign-in - the press fails, and nobody is sent to sign in. */
    private fun actionBearers(accounts: AccountBearers): AccountBearers = object : AccountBearers by accounts {
        override fun refusedAfterRefresh(accountId: String) {}
    }

    /** The client every chino-api call goes through: the bearer on every
     *  request - of the account it carries ([accounts]), else the active
     *  account's - one refresh-and-retry on a 401, and [onRefusedAfterRefresh]
     *  where chino-api refuses the fresh token too. Internal: tests answer
     *  on top of exactly this client. */
    internal fun httpClient(
        tokenProvider: () -> String?,
        forceRefresh: () -> String?,
        accounts: AccountBearers,
        onRefusedAfterRefresh: () -> Unit = {},
    ): OkHttpClient.Builder {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // A request that carries its account goes out with that
                // account's bearer, whoever is active now; every other with
                // the active account's.
                val account = chain.request().tag(RequestAccount::class.java)
                val bearer = if (account != null) {
                    accounts.tokenOf(account.accountId) ?: throw AccountSignedOutException(account.accountId)
                } else {
                    tokenProvider()
                }
                val req = chain.request().newBuilder().apply {
                    bearer?.let { header("Authorization", "Bearer $it") }
                    header("Accept", "application/json")
                }.build()
                chain.proceed(req)
            }
            // OkHttp Authenticator: invoked only on a 401 from the server. We trade
            // the refresh_token for a fresh access_token and replay the request once.
            // If forceRefresh returns null (refresh itself failed), returning null
            // makes OkHttp give up and propagate the 401 to the app, which sends the
            // user back to AUTH: the token manager says the sign-in lapsed, and so
            // does a fresh token chino-api refuses too (onRefusedAfterRefresh).
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    val account = response.request.tag(RequestAccount::class.java)
                    if (response.request.header("X-Auth-Retry") != null) {
                        if (account != null) accounts.refusedAfterRefresh(account.accountId) else onRefusedAfterRefresh()
                        return null
                    }
                    val fresh = (if (account != null) accounts.forceRefreshOf(account.accountId) else forceRefresh())
                        ?: return null
                    return response.request.newBuilder()
                        .header("Authorization", "Bearer $fresh")
                        .header("X-Auth-Retry", "1")
                        .build()
                }
            })
            .addInterceptor(logging)
    }
}
