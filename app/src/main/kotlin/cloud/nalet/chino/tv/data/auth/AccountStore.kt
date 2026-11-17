package cloud.nalet.chino.tv.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * Multi-account replacement for [TokenStore]. Persists a list of [Account]
 * records + the active account id, all in a single encrypted JSON blob.
 *
 * Backing store: AES256-GCM EncryptedSharedPreferences (single value under
 * KEY_BLOB) so the per-account tokens are device-key-bound and a rooted
 * attacker can't read them off disk without the keystore.
 *
 * Migration: [migrateFromTokenStoreIfPresent] reads the legacy single-
 * account TokenStore on first run, creates one Account from it (id =
 * "legacy" because we don't have the sub claim until userinfo can be
 * fetched — the next refresh will replace the synthetic id with the real
 * sub), and clears the old store.
 */
class AccountStore internal constructor(
    /** The app's are the encrypted ones; a test's may be plain. */
    private val prefs: SharedPreferences,
) {
    constructor(context: Context) : this(encryptedPrefs(context.applicationContext))

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Every read-modify-write of the blob holds it: a token refresh landing
     *  from an OkHttp thread while an account is chosen must not write the
     *  account before back as the active one. */
    private val lock = Any()

    /** How often the active account has changed in this process; held by [lock]. */
    private var activeChanges = 0L

    private fun read(): AccountStoreBlob {
        val raw = prefs.getString(KEY_BLOB, null) ?: return AccountStoreBlob()
        return runCatching { json.decodeFromString(AccountStoreBlob.serializer(), raw) }
            .getOrElse { AccountStoreBlob() }
    }

    /** Writes [blob] over [before], the blob it was made from. Must hold [lock]. */
    private fun write(before: AccountStoreBlob, blob: AccountStoreBlob) {
        if (blob.activeId != before.activeId) activeChanges += 1
        prefs.edit()
            .putString(KEY_BLOB, json.encodeToString(AccountStoreBlob.serializer(), blob))
            .apply()
    }

    /** Cold-emitting flow that re-reads on every prefs commit. */
    private val state: Flow<AccountStoreBlob> = callbackFlow {
        trySend(read())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Convenience derived flows. */
    val accounts: Flow<List<Account>> = state.map { it.accounts }
    val activeAccountId: Flow<String?> = state.map { it.activeId }
    val activeAccount: Flow<Account?> = state.map { b -> b.accounts.firstOrNull { it.id == b.activeId } }

    /** The accounts and the active one, as one value for each change. */
    val snapshots: Flow<Snapshot> = state.map { b -> Snapshot(b.accounts, b.accounts.firstOrNull { it.id == b.activeId }) }

    /** Append a new account or update tokens of an existing one (matched by id).
     *  Sets [setActive] = true to make it the picker default after add. */
    suspend fun addOrUpdate(account: Account, setActive: Boolean = false) {
        synchronized(lock) {
            val blob = read()
            val without = blob.accounts.filter { it.id != account.id }
            val updated = (without + account).sortedByDescending { it.lastUsedAt }
            write(blob, blob.copy(accounts = updated, activeId = if (setActive) account.id else blob.activeId))
        }
    }

    suspend fun remove(id: String) {
        synchronized(lock) {
            val blob = read()
            val updated = blob.accounts.filter { it.id != id }
            val newActive = if (blob.activeId == id) updated.firstOrNull()?.id else blob.activeId
            write(blob, blob.copy(accounts = updated, activeId = newActive))
        }
    }

    suspend fun setActive(id: String) {
        synchronized(lock) {
            val blob = read()
            if (blob.accounts.none { it.id == id }) return
            // Bump lastUsedAt AND re-sort so the just-activated account moves to
            // the front — the picker focuses index 0, so this makes the last
            // logged-in person the preselected avatar next time. (Without the
            // re-sort the list kept its old order and index 0 wasn't the last
            // user.)
            val now = System.currentTimeMillis()
            val updated = blob.accounts
                .map { if (it.id == id) it.copy(lastUsedAt = now) else it }
                .sortedByDescending { it.lastUsedAt }
            write(blob, blob.copy(accounts = updated, activeId = id))
        }
    }

    /** OkHttp interceptor entrypoint — runs on dispatcher threads, blocking is fine. */
    fun currentAccessTokenBlocking(): String? {
        val blob = read()
        return blob.accounts.firstOrNull { it.id == blob.activeId }?.accessToken
    }

    /** Synchronous snapshot of the current state — used by the NavHost on its
     *  very first composition to pick the right start destination without
     *  waiting for the cold `state` flow to emit (which would otherwise show
     *  the AUTH page for one frame and then strand the user there because
     *  NavHost.startDestination is locked after the first render). */
    fun snapshotBlocking(): Snapshot {
        val blob = read()
        val active = blob.accounts.firstOrNull { it.id == blob.activeId }
        return Snapshot(accounts = blob.accounts, activeAccount = active)
    }

    data class Snapshot(val accounts: List<Account>, val activeAccount: Account?)

    /** Who is active, read in one step with how often the active account has
     *  changed: two reads that agree saw the same choice, with no other in
     *  between - even one that was undone again. Blocking, like [snapshotBlocking]. */
    fun activeChoiceBlocking(): ActiveChoice = synchronized(lock) {
        ActiveChoice(read().activeId, activeChanges)
    }

    /** The account [id], while it is signed in on this device. Blocking. */
    fun accountBlocking(id: String): Account? = read().accounts.firstOrNull { it.id == id }

    /** Replaces just the token fields on the named account; preserves
     *  displayName / email / id. Called by TokenManager after a refresh —
     *  important to scope by accountId rather than "active" because the
     *  user could have switched accounts in the picker mid-refresh, and a
     *  blind "write to active" would corrupt the new account's tokens with
     *  the old account's refreshed values. */
    fun updateTokensForBlocking(accountId: String, tokens: Tokens) {
        synchronized(lock) {
            val blob = read()
            val updated = blob.accounts.map {
                if (it.id == accountId) {
                    it.copy(
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken ?: it.refreshToken,
                        expiresAtEpochMillis = tokens.expiresAtEpochMillis,
                    )
                } else it
            }
            write(blob, blob.copy(accounts = updated))
        }
    }

    /** One-shot migration from the legacy single-account [TokenStore]. Reads
     *  the old tokens, creates a placeholder Account (real sub/email filled
     *  in on first userinfo fetch), wipes the old prefs. Idempotent — if no
     *  legacy tokens exist or accounts are already populated, this is a
     *  no-op. */
    fun migrateFromTokenStoreIfPresent(legacyStore: TokenStore) {
        synchronized(lock) {
            val blob = read()
            if (blob.accounts.isNotEmpty()) return // already migrated
            val legacy = legacyStore.currentAccessTokenBlocking() ?: return
            val placeholderId = "legacy-${System.currentTimeMillis()}"
            val account = Account(
                id = placeholderId,
                displayName = "Account",
                email = "",
                accessToken = legacy,
                refreshToken = null, // TokenStore.tokens flow has it but the
                // sync read doesn't expose refresh; the user will hit a 401
                // when the token expires and re-auth via picker.
                expiresAtEpochMillis = 0L, // forces immediate refresh attempt
                lastUsedAt = System.currentTimeMillis(),
            )
            write(blob, AccountStoreBlob(accounts = listOf(account), activeId = placeholderId))
        }
    }

    private companion object {
        const val KEY_BLOB = "v1"

        fun encryptedPrefs(app: Context): SharedPreferences = EncryptedSharedPreferences.create(
            app,
            "chino_accounts_enc",
            MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}

/**
 * The active account as one choice of it: [accountId] (null while nobody is
 * active) and [changes], how often the active account had changed in this
 * process when it was read. What is asked for one account and kept for it is
 * kept for the choice: when another account was chosen in between, even if
 * the first was chosen again, the choice is another.
 */
data class ActiveChoice(val accountId: String?, val changes: Long)
