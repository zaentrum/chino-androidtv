package cloud.nalet.chino.tv.data

import android.content.Context
import cloud.nalet.chino.tv.BuildConfig
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.data.auth.OidcDeviceClient
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.auth.TokenManager
import cloud.nalet.chino.tv.data.auth.TokenStore
import kotlinx.coroutines.launch

/**
 * Hand-rolled DI container. We don't pull in Hilt/Koin for a project this small —
 * a single object graph built in [ChinoTvApp.onCreate] is easier to reason about
 * than annotations + generated code, and survives configuration changes because
 * the Application instance does.
 */
class AppContainer(context: Context) {
    private val appCtx = context.applicationContext

    /** Application-lifetime CoroutineScope for fire-and-forget POSTs that
     *  must outlive a ViewModel's onCleared — terminal progress/watched
     *  saves when the user backs out of the player race against the player
     *  VM's viewModelScope cancellation, dropping the request before OkHttp
     *  sees it. SupervisorJob so one failure doesn't poison the scope. */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
        )

    // Legacy single-account store, kept only so AccountStore can migrate
    // any in-place v0 tokens on first launch. Once the migration runs,
    // nothing reads from this any more — all auth state lives in
    // AccountStore.
    //
    // Lazy because TokenStore's constructor instantiates a MasterKey + opens
    // EncryptedSharedPreferences — both round-trip through AndroidKeyStore,
    // which on AOSP x86 emulators can take 5-10 s and triggers an ANR if it
    // runs on the main thread. Lazy + first-access-from-IO (see
    // ChinoTvNavHost's produceState) keeps Application.onCreate cheap.
    val tokenStore: TokenStore by lazy { TokenStore(appCtx) }

    val accountStore: AccountStore by lazy {
        AccountStore(appCtx).also { store ->
            store.migrateFromTokenStoreIfPresent(tokenStore)
            // Keep telemetry's account snapshot in sync with whatever's
            // active in AccountStore. Hot-collected on a long-lived
            // application-scoped coroutine so events fired off any thread
            // see the latest id without explicit plumbing through every VM.
            // Wired here (rather than a free-standing init block) so the
            // collector is registered exactly once, the first time someone
            // touches accountStore — same lifecycle as the store itself.
            kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
            ).launch {
                store.activeAccountId.collect { id -> telemetry.setActiveAccount(id) }
            }
        }
    }

    /** Persisted server config (URL + OIDC issuer/client + discovered
     *  endpoints). Plain DataStore — not secret; tokens stay in AccountStore. */
    val serverConfigStore: ServerConfigStore by lazy { ServerConfigStore(appCtx) }

    /** Build-flavor defaults — the Add-Server preset + an in-memory fallback
     *  if config is somehow read before a server is connected (no API calls
     *  happen on that path, so it's purely defensive; the NavHost boot seeds
     *  existing installs and routes fresh ones to Add-Server). */
    fun buildDefaultServerConfig() = ServerConfig(
        baseUrl = BuildConfig.API_BASE_URL,
        issuer = BuildConfig.OIDC_ISSUER,
        clientId = BuildConfig.OIDC_CLIENT_ID,
    )

    /**
     * The server this client is pointed at, resolved once off the main thread
     * from the persisted config; falls back to the build-flavor default in
     * memory (not persisted) when nothing is stored yet.
     */
    val serverConfig: ServerConfig by lazy {
        serverConfigStore.currentBlocking() ?: buildDefaultServerConfig()
    }

    val oidcDeviceClient: OidcDeviceClient by lazy {
        val sc = serverConfig
        if (sc.deviceAuthEndpoint != null && sc.tokenEndpoint != null && sc.userinfoEndpoint != null) {
            OidcDeviceClient(
                deviceAuthEndpoint = sc.deviceAuthEndpoint,
                tokenEndpoint = sc.tokenEndpoint,
                userinfoEndpoint = sc.userinfoEndpoint,
                clientId = sc.clientId,
            )
        } else {
            // No discovered metadata yet → fall back to the Keycloak path layout.
            OidcDeviceClient(issuer = sc.issuer, clientId = sc.clientId)
        }
    }

    val tokenManager: TokenManager by lazy { TokenManager(accountStore, oidcDeviceClient) }

    private val retrofit by lazy {
        RetrofitFactory.create(
            baseUrl = serverConfig.baseUrl,
            tokenProvider = tokenManager::validAccessTokenBlocking,
            forceRefresh = tokenManager::forceRefreshBlocking,
        )
    }

    val chinoApi: ChinoApi by lazy { retrofit.create(ChinoApi::class.java) }

    /**
     * One stream token shared by every poster/backdrop/play URL. The token survives
     * Keycloak silent renews so <img src> and <video src> URLs don't churn — the
     * same URL stays valid for 6 h, then [StreamTokenManager] refreshes transparently.
     */
    val streamTokenManager: StreamTokenManager by lazy { StreamTokenManager(chinoApi) }

    /** Shared watchlist + likes cache across DetailScreen and any future surfaces. */
    val userFlags: UserFlagsRepository by lazy { UserFlagsRepository(chinoApi) }

    /** Per-device playback ergonomics (binge auto-skip, auto-play, countdown). */
    val settings: SettingsStore by lazy { SettingsStore(appCtx) }

    /** Process-wide telemetry funnel — auto-attaches device/app/session/network context. */
    val telemetry: cloud.nalet.chino.tv.data.telemetry.Telemetry by lazy {
        cloud.nalet.chino.tv.data.telemetry.Telemetry(appCtx, chinoApi)
    }

    /** Crash-report queue at filesDir/bug_reports — written SYNCHRONOUSLY by
     *  ChinoTvApp's uncaught-exception handler while the process dies, drained
     *  by [bugReporter]'s flushPending on the next signed-in launch (the
     *  LIBRARY route fires it). Cheap to build (just a File wrapper), so the
     *  crash handler touching this lazy never wakes the heavy graph. */
    val pendingReports: cloud.nalet.chino.tv.feedback.FilePendingReportStore by lazy {
        cloud.nalet.chino.tv.feedback.FilePendingReportStore(java.io.File(appCtx.filesDir, "bug_reports"))
    }

    /** One funnel for bug reports — POST /v1/feedback. Auto reports (crash /
     *  player) are fire-and-forget + silent, the Settings category picker
     *  goes through reportManual. Stamps the same static device/app context
     *  Telemetry puts on events. */
    val bugReporter: cloud.nalet.chino.tv.feedback.BugReporter by lazy {
        cloud.nalet.chino.tv.feedback.BugReporter(
            api = chinoApi,
            staticContext = cloud.nalet.chino.tv.feedback.deviceStaticContext(),
            pendingStore = pendingReports,
        )
    }

    val baseUrl: String by lazy { serverConfig.baseUrl.trimEnd('/') }

    /**
     * Dedicated OkHttp client for ExoPlayer's data source. Sharing a Call.Factory
     * (rather than DefaultHttpDataSource's per-request HttpURLConnection) means:
     *  - Connection pool reused across master.m3u8 + each .m4s segment fetch
     *    (TLS handshakes amortized; matters on TV where the first segment used
     *    to take 200-400ms just to set up the connection)
     *  - The OkHttp logging interceptor surfaces every segment URL + response
     *    code in logcat, so chino-stream 404s are immediately visible during
     *    debug instead of hidden behind ExoPlayer's internal HttpDataSource
     *  - No OIDC Authorization header needed — chino-stream URLs already carry
     *    the `?stream=<token>` token in the query string
     *
     * Read timeout is LONG (120s): cold ffmpeg invocations take >20s, AND under
     * nas001 NFS read contention a single packaged /seg-N.m4s can stall far
     * past 45s (metadata GETATTR stalls were measured at ~18-38s and worse) —
     * the old 45s timeout ABORTED those fetches (SocketTimeoutException), so the
     * buffer could never fill and playback rebuffered. With a long timeout the
     * slow segment still completes and the big LoadControl buffer rides it out.
     */
    val streamHttpClient: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor(
                okhttp3.logging.HttpLoggingInterceptor().apply {
                    level = okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                },
            )
            .build()
    }

    /**
     * Process-wide Zap segment prefetcher. Warms the shared on-disk [ZapCache]
     * for upcoming Zap cards (and the first upcoming card at app start) so
     * swiping to the next card / opening Zap begins instantly. Shares the
     * stream OkHttp client (connection pool reuse) and the same cache the
     * ZapScreen player reads from. Lazy so it only materialises when Zap (or
     * the app-start warm) first touches it.
     */
    val zapPrefetcher: cloud.nalet.chino.tv.ui.zap.ZapPrefetcher by lazy {
        cloud.nalet.chino.tv.ui.zap.ZapPrefetcher(appCtx, streamHttpClient)
    }

    /**
     * Eagerly materialise the shared Zap [SimpleCache][cloud.nalet.chino.tv.ui.zap.ZapCache]
     * OFF the main thread at app init. [ZapCache.get] lazily opens the SimpleCache
     * (StandaloneDatabaseProvider + on-disk index scan) on its FIRST caller's
     * thread; without this that first caller is the ZapScreen LaunchedEffect on
     * the MAIN thread, so the disk I/O can ANR. Building it here on [appScope]
     * (Dispatchers.IO) means the main-thread player path always hits an
     * already-constructed cache. Fire-and-forget; idempotent (ZapCache is a
     * process-wide singleton, double-checked). Safe to call once per process.
     */
    fun warmZapCacheOffMainThread() {
        appScope.launch {
            runCatching { cloud.nalet.chino.tv.ui.zap.ZapCache.get(appCtx) }
        }
    }

    // Run the app-start Zap warm at most once per process so re-entering home
    // doesn't re-issue the lightweight feed fetch.
    private val zapWarmStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * APP-START warm: when home first loads, kick a lightweight prefetch of just
     * the FIRST upcoming Zap card so opening Zap is instant. Builds a minimal Zap
     * feed off the IO scope and warms the DETERMINISTIC card[0] — the top-ranked
     * packaged candidate at the fixed [DETERMINISTIC_FIRST_CARD_RATIO] seek. The
     * Zap screen's ViewModel computes the SAME card[0] from the same candidate
     * source + the same shared stream token, so the bytes warmed here are exactly
     * the master → variant → init → seek-window the screen plays first (no miss
     * from an independent random first card). Fire-and-forget and failure-
     * tolerant — a cold home must never block on this. Idempotent per process.
     */
    fun warmFirstZapCard() {
        if (!zapWarmStarted.compareAndSet(false, true)) return
        appScope.launch {
            runCatching {
                val token = streamTokenManager.valid()
                if (token.isEmpty()) return@launch
                val feed = cloud.nalet.chino.tv.ui.zap.ZapFeed(api = chinoApi)
                feed.load()
                // DETERMINISTIC card[0]: top-ranked packaged candidate (not the
                // shuffled/epsilon-sampled queue head) at the fixed seek ratio —
                // identical to the Zap screen's first card.
                val first = feed.topCandidate() ?: return@launch
                val seekSec = cloud.nalet.chino.tv.ui.zap.deterministicFirstCardSeekSec(first.durationMs)
                val caps = cloud.nalet.chino.tv.data.CodecCaps.queryParam
                val masterUrl = buildString {
                    append("$baseUrl/v1/items/${first.id}/play/master.m3u8?stream=$token")
                    if (caps.isNotEmpty()) append("&caps=$caps")
                    append("&q=medium")
                }
                zapPrefetcher.prefetch(
                    listOf(
                        cloud.nalet.chino.tv.ui.zap.ZapPrefetchTarget(
                            itemId = first.id,
                            masterUrl = masterUrl,
                            seekSec = seekSec,
                        ),
                    ),
                )
            }
        }
    }
}
