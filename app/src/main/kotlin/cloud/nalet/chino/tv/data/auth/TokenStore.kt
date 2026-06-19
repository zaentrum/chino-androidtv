package cloud.nalet.chino.tv.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Persists OIDC tokens for the signed-in session, backed by AES-GCM
 * EncryptedSharedPreferences (jetpack-security). The master key lives in the
 * Android Keystore, so even a rooted attacker with the prefs blob can't read
 * the access/refresh tokens without the device-bound key.
 *
 * Replaces the v0 plaintext DataStore implementation; data migration is one-
 * shot on first run via [migrateLegacyDataStoreIfPresent], called from the
 * AppContainer init path. Old plaintext file is wiped after a successful read.
 */
class TokenStore(context: Context) {
    private val app = context.applicationContext
    private val masterKey: MasterKey = MasterKey.Builder(app)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        app,
        "chino_tokens_enc",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init { migrateLegacyDataStoreIfPresent() }

    val tokens: Flow<Tokens?> = callbackFlow {
        // SharedPreferences emits one callback per commit; debounce + dedup
        // via distinctUntilChanged downstream.
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    suspend fun save(tokens: Tokens) {
        prefs.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .also { e -> tokens.refreshToken?.let { e.putString(KEY_REFRESH, it) } }
            .putLong(KEY_EXPIRES_AT, tokens.expiresAtEpochMillis)
            .apply()
    }

    suspend fun clear() {
        prefs.edit().clear().apply()
    }

    /**
     * Synchronous read for the Retrofit auth interceptor. Interceptors run on
     * OkHttp's dispatcher threads (never the main thread) so the blocking
     * `first()` is safe; EncryptedSharedPreferences itself is already sync.
     */
    fun currentAccessTokenBlocking(): String? = read()?.accessToken

    private fun read(): Tokens? {
        val access = prefs.getString(KEY_ACCESS, null) ?: return null
        return Tokens(
            accessToken = access,
            refreshToken = prefs.getString(KEY_REFRESH, null),
            expiresAtEpochMillis = prefs.getLong(KEY_EXPIRES_AT, 0L),
        )
    }

    /**
     * Wipes the old v0 plaintext DataStore file on first encrypted-prefs run.
     * No data migration — anyone signed in on the old build re-signs via the
     * device-flow QR. (Acceptable: the beta has a handful of users and the
     * cost is a 30-second re-auth.)
     */
    private fun migrateLegacyDataStoreIfPresent() {
        val legacyFile = java.io.File(app.filesDir, "datastore/chino_tokens.preferences_pb")
        if (legacyFile.exists()) runCatching { legacyFile.delete() }
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EXPIRES_AT = "expires_at_epoch_millis"
    }
}

data class Tokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long,
)
