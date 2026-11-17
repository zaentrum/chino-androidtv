package cloud.nalet.chino.tv.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The server this client is connected to. For the neutral self-host client
 * the user enters their own server URL on first run and the OIDC config is
 * discovered from it; until the Add-Server flow (Phase 1b) lands this is
 * seeded from the build flavor so the app behaves exactly like the baked-in
 * build.
 *
 * baseUrl / issuer / clientId are NOT secrets (tokens live in AccountStore),
 * so plain DataStore Preferences is fine. The discovered endpoints stay null
 * until OIDC discovery has run; when null, [cloud.nalet.chino.tv.data.auth.OidcDeviceClient]
 * falls back to building the Keycloak openid-connect endpoint paths
 * (auth/device, token, userinfo) from the issuer (the original behaviour).
 */
data class ServerConfig(
    val baseUrl: String,
    val issuer: String,
    val clientId: String,
    val deviceAuthEndpoint: String? = null,
    val tokenEndpoint: String? = null,
    val userinfoEndpoint: String? = null,
) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank()
}

/**
 * [api] once a server is connected — [config] holds one — and null before,
 * without building it: a fresh install has no server until Add Server, a
 * release build has no address to build an API client for, and building it
 * then would also pin the empty config for the rest of the process. What
 * [AppContainer] hands out the API by to Telemetry and the notices.
 */
suspend fun <T> onceConnected(config: suspend () -> ServerConfig?, api: () -> T): T? =
    if (config()?.isConfigured == true) api() else null

private val Context.serverConfigDataStore by preferencesDataStore(name = "server_config")

class ServerConfigStore(context: Context) {
    private val ds = context.applicationContext.serverConfigDataStore

    /** The stored config, or null when no server has been connected yet
     *  (a fresh neutral install before Add-Server). */
    suspend fun current(): ServerConfig? = fromPrefs(ds.data.first())

    /** Blocking read of the stored config (null if no server connected yet).
     *  Runs once while the lazy AppContainer graph materialises off the main
     *  thread; mirrors AccountStore.snapshotBlocking. Does NOT persist a
     *  default — seeding existing installs is the NavHost boot's job. */
    fun currentBlocking(): ServerConfig? = runBlocking { current() }

    /** Most-recently-connected server URLs (newest first, max 5) for the
     *  Add-Server quick-connect list. Stored newline-joined; URLs never
     *  contain newlines. */
    suspend fun recents(): List<String> =
        ds.data.first()[KEY_RECENTS]?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    suspend fun addRecent(url: String) {
        val next = (listOf(url) + recents().filter { it != url }).take(5)
        ds.edit { it[KEY_RECENTS] = next.joinToString("\n") }
    }

    suspend fun save(c: ServerConfig) {
        ds.edit { p ->
            // Another server: what the one before said it offers goes.
            if (p[KEY_BASE_URL] != c.baseUrl) {
                p.remove(KEY_FEATURES_OF)
                p.remove(KEY_WATCHLIST_KINDS)
            }
            p[KEY_BASE_URL] = c.baseUrl
            p[KEY_ISSUER] = c.issuer
            p[KEY_CLIENT_ID] = c.clientId
            putOrRemove(p, KEY_DEVICE_AUTH_EP, c.deviceAuthEndpoint)
            putOrRemove(p, KEY_TOKEN_EP, c.tokenEndpoint)
            putOrRemove(p, KEY_USERINFO_EP, c.userinfoEndpoint)
        }
    }

    /** Forgets the connected server — used by Settings "change server" (1b) —
     *  and what it said it offers. */
    suspend fun clear() = ds.edit { it.clear() }

    /** Whether the server at [baseUrl] said, the last time it answered
     *  /api/config, that it keeps Separate movies and shows and fills a list
     *  by kind (features.watchlist_kinds): kept so that settings and the
     *  watchlist know it from their first frame after a start, before the
     *  server answers again, and where it doesn't. Null where it never
     *  answered. Another server saved, or the server forgotten, takes it
     *  with it. Blocking, like [currentBlocking]. */
    fun watchlistKindsBlocking(baseUrl: String): Boolean? = runBlocking {
        val p = ds.data.first()
        p[KEY_WATCHLIST_KINDS]?.takeIf { p[KEY_FEATURES_OF] == baseUrl }
    }

    /** Keeps what the server at [baseUrl] said ([watchlistKindsBlocking]):
     *  one server's, the one connected. */
    suspend fun saveWatchlistKinds(baseUrl: String, on: Boolean) {
        ds.edit { p ->
            p[KEY_FEATURES_OF] = baseUrl
            p[KEY_WATCHLIST_KINDS] = on
        }
    }

    private fun putOrRemove(p: MutablePreferences, key: Preferences.Key<String>, v: String?) {
        if (v != null) p[key] = v else p.remove(key)
    }

    private fun fromPrefs(p: Preferences): ServerConfig? {
        val base = p[KEY_BASE_URL] ?: return null
        val issuer = p[KEY_ISSUER] ?: return null
        val clientId = p[KEY_CLIENT_ID] ?: return null
        return ServerConfig(
            baseUrl = base,
            issuer = issuer,
            clientId = clientId,
            deviceAuthEndpoint = p[KEY_DEVICE_AUTH_EP],
            tokenEndpoint = p[KEY_TOKEN_EP],
            userinfoEndpoint = p[KEY_USERINFO_EP],
        )
    }

    private companion object {
        val KEY_BASE_URL = stringPreferencesKey("server_base_url")
        val KEY_ISSUER = stringPreferencesKey("oidc_issuer")
        val KEY_CLIENT_ID = stringPreferencesKey("oidc_client_id")
        val KEY_DEVICE_AUTH_EP = stringPreferencesKey("oidc_device_auth_endpoint")
        val KEY_TOKEN_EP = stringPreferencesKey("oidc_token_endpoint")
        val KEY_USERINFO_EP = stringPreferencesKey("oidc_userinfo_endpoint")
        val KEY_RECENTS = stringPreferencesKey("recent_server_urls")

        /** The server [KEY_WATCHLIST_KINDS] is of. */
        val KEY_FEATURES_OF = stringPreferencesKey("features_base_url")
        val KEY_WATCHLIST_KINDS = booleanPreferencesKey("features_watchlist_kinds")
    }
}
