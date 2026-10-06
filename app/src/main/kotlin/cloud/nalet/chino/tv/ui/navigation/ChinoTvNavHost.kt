package cloud.nalet.chino.tv.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.auth.AccountPickerScreen
import cloud.nalet.chino.tv.ui.theme.LogoMark
import cloud.nalet.chino.tv.ui.theme.ChinoBg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import cloud.nalet.chino.tv.ui.auth.AuthViewModel
import cloud.nalet.chino.tv.ui.auth.DeviceCodeScreen
import cloud.nalet.chino.tv.ui.detail.DetailScreen
import cloud.nalet.chino.tv.ui.detail.DetailViewModel
import cloud.nalet.chino.tv.ui.library.LibraryScreen
import cloud.nalet.chino.tv.ui.library.LibraryViewModel
import cloud.nalet.chino.tv.ui.notices.NoticesBell
import cloud.nalet.chino.tv.ui.notices.NoticesScreen
import cloud.nalet.chino.tv.ui.notices.NoticesViewModel
import cloud.nalet.chino.tv.ui.person.PersonScreen
import cloud.nalet.chino.tv.ui.person.PersonViewModel
import cloud.nalet.chino.tv.ui.player.PlayMode
import cloud.nalet.chino.tv.ui.player.PlayerScreen
import cloud.nalet.chino.tv.ui.player.PlayerViewModel
import cloud.nalet.chino.tv.ui.search.SearchScreen
import cloud.nalet.chino.tv.ui.search.SearchViewModel
import cloud.nalet.chino.tv.ui.settings.SettingsScreen
import cloud.nalet.chino.tv.ui.settings.SettingsViewModel
import cloud.nalet.chino.tv.ui.trailer.TrailerScreen
import cloud.nalet.chino.tv.ui.trailer.TrailerViewModel
import cloud.nalet.chino.tv.ui.watchlist.WatchlistScreen
import cloud.nalet.chino.tv.ui.watchlist.WatchlistViewModel

object Routes {
    const val SERVER = "server"
    /** Settings "Change server" — prefilled Add-Server that clears accounts
     *  and restarts the process on connect to a different server. */
    const val SERVER_CHANGE = "server_change"
    const val ZAP = "zap"
    const val AUTH = "auth"
    const val PICKER = "picker"
    const val LIBRARY = "library"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    /** Settings → Account → Delete Account: asks, then deletes the signed-in
     *  account on the server and signs it out here. */
    const val DELETE_ACCOUNT = "delete_account"
    const val PROFILE = "profile"
    const val WATCHLIST = "watchlist"
    /** What addons told the signed-in person — reached from the bell in
     *  every top bar. */
    const val NOTICES = "notices"
    const val DETAIL = "detail/{itemId}"
    /** Person / Filmography surface — reached from the Search people row and
     *  from tappable Detail cast chips. */
    const val PERSON = "person/{personId}"
    const val PLAYER = "player/{itemId}?fromStart={fromStart}&fromBinge={fromBinge}&resume={resume}"
    /** A title's trailer from this server (one of its extras) — from
     *  Detail's Trailer button. Plays it and closes; no progress. */
    const val TRAILER = "trailer/{itemId}/{extraId}"
    fun detail(itemId: String) = "detail/$itemId"
    fun person(personId: String) = "person/$personId"
    fun trailer(itemId: String, extraId: String) = "trailer/$itemId/$extraId"
    /** fromStart=true forces the player to ignore any saved resume
     *  position and start at 0:00 — used by the "Play from start" CTA on
     *  Detail. fromStart=false (default) honours the user's saved progress.
     *
     *  fromBinge=true tells PlayerViewModel.prepare() to pre-skip any intro
     *  segment beginning inside the first 60 s. Set only by the auto-play-
     *  next chain (PlayerScreen's PendingSkip.PlayNext fire path) so manual
     *  next/prev/detail entry still lets the user watch the intro. */
    fun player(itemId: String, fromStart: Boolean = false, fromBinge: Boolean = false, resumeSec: Int = 0) =
        "player/$itemId?fromStart=$fromStart&fromBinge=$fromBinge&resume=$resumeSec"
}

/** Idle-reset threshold: backgrounded shorter than this resumes in place;
 *  longer (or equal) reopens at the Library root. 30 minutes. */
private const val IDLE_RESET_AFTER_MS = 30L * 60L * 1000L

/** Boot-time snapshot: the account state plus whether a server is configured
 *  (post-migration), resolved together off the main thread so the NavHost
 *  start destination is locked on the first real frame. */
private data class BootState(
    val accounts: AccountStore.Snapshot,
    val serverConfigured: Boolean,
)

/** Heuristic for "this account needs a fresh device-flow sign-in". True when
 *  the access token has already expired AND there's no refresh token to
 *  swap (legacy migration accounts, revoked sessions, or post-`offline_session_idle`). */
private fun Account.needsReauth(): Boolean {
    val expired = System.currentTimeMillis() >= expiresAtEpochMillis
    val noRefresh = refreshToken.isNullOrBlank()
    return expired && noRefresh
}

@Composable
fun ChinoTvNavHost(container: AppContainer) {
    // Boot snapshot resolved off the main thread. accountStore is `by lazy`
    // inside AppContainer, so its first access materialises both the
    // MasterKey + the EncryptedSharedPreferences — operations that round-
    // trip through AndroidKeyStore and can stall multiple seconds. Running
    // them on Dispatchers.IO keeps the main thread responsive while the
    // splash below is visible. NavHost composition itself is deferred until
    // `boot.value` is non-null so startDestination is locked at the correct
    // value on the first real frame (the cold-flow `initial = …` trick we
    // used to rely on can't help us until the snapshot exists).
    val boot by produceState<BootState?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val accts = container.accountStore.snapshotBlocking()
            var configured = container.serverConfigStore.current()?.isConfigured == true
            // Migrate pre-ServerConfig installs: an existing user (has accounts)
            // with no stored server is seeded from the build-flavor default so
            // they land in the library, not the Add-Server screen. Fresh installs
            // (no accounts, no server) fall through to Routes.SERVER.
            if (!configured && accts.accounts.isNotEmpty()) {
                container.serverConfigStore.save(container.buildDefaultServerConfig())
                configured = true
            }
            BootState(accts, configured)
        }
    }
    val bootState = boot ?: run {
        BootSplash()
        return
    }
    val snapshot = bootState.accounts
    val navController = rememberNavController()
    // The bell in every top bar: the person's notices, and the way to them.
    // Building it builds no API client (AppContainer.notices) — only the
    // bell, on the screens that exist once signed in, asks for notices.
    val noticesBell = remember(navController) {
        NoticesBell(container.notices) {
            navController.navigate(Routes.NOTICES) { launchSingleTop = true }
        }
    }
    val accounts by container.accountStore.accounts.collectAsState(initial = snapshot.accounts)
    val activeAccount by container.accountStore.activeAccount.collectAsState(initial = snapshot.activeAccount)

    // ── Idle reset to Home ────────────────────────────────────────────────
    // Android restores the task wherever it was, so reopening the app hours
    // later would land on the last-watched title's screen. Standard TV-app
    // behavior instead: stamp a monotonic clock when the activity leaves the
    // foreground; on ON_START, if the app sat in the background for >= 30
    // minutes, reset navigation to the LIBRARY root (back stack cleared) so
    // the session starts fresh at home — the Library's own ON_RESUME
    // continue-watching refresh then repopulates the rows. Deliberately NOT
    // clearTaskOnLaunch (that resets even on a 30-second hop away).
    // ON_START-only means a foregrounded app is never interrupted; short hops
    // (quick HOME press, assistant overlay) stay under the threshold and
    // resume exactly where the user was. elapsedRealtime, not
    // currentTimeMillis: monotonic + counts device sleep, immune to
    // NTP/wall-clock jumps overnight.
    //
    // The stamp lives in rememberSaveable, NOT a plain remember: on a long
    // idle Android usually KILLS the process and later recreates the activity
    // while restoring the nav stack from saved instance state — the most
    // common case this reset exists for. An in-memory stamp comes back null
    // there and the reset silently no-ops; saving it keeps the stamp paired
    // with the very nav state it guards (on recreation the restored stamp is
    // delivered to the observer via the synthetic catch-up ON_START). Saved
    // instance state does NOT survive a reboot, so a post-reboot
    // elapsedRealtime restarting at zero can never meet a stale stamp — no
    // false resets. A null stamp still doubles as the first-launch guard.
    //
    // Stamped on ON_PAUSE *and* ON_STOP (not ON_STOP alone): rememberSaveable
    // values are captured at onSaveInstanceState time, and on pre-API-28
    // devices that runs BEFORE/at onStop — an ON_STOP-only write could miss
    // the bundle in exactly the scenario we save it for. onPause always
    // precedes the state save, so the ON_PAUSE write guarantees the bundle
    // carries the stamp; the ON_STOP write merely refreshes it on API 28+
    // where saving happens after stop. Safe: every ON_START is preceded by a
    // fresh ON_PAUSE/ON_STOP rewrite, and a pause-only blip that resumes
    // without stopping never reaches the ON_START check at all.
    val backgroundedAtMs = androidx.compose.runtime.saveable.rememberSaveable {
        androidx.compose.runtime.mutableStateOf<Long?>(null)
    }
    val activityLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(activityLifecycleOwner, navController) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
                androidx.lifecycle.Lifecycle.Event.ON_STOP ->
                    backgroundedAtMs.value = android.os.SystemClock.elapsedRealtime()
                androidx.lifecycle.Lifecycle.Event.ON_START -> {
                    val since = backgroundedAtMs.value
                    backgroundedAtMs.value = null
                    if (since != null &&
                        android.os.SystemClock.elapsedRealtime() - since >= IDLE_RESET_AFTER_MS
                    ) {
                        val route = navController.currentDestination?.route
                        // Never reset out of the onboarding/identity surfaces —
                        // kicking a user parked on server-setup / sign-in / the
                        // profile picker over to LIBRARY would skip required
                        // steps. Already at the Library root → nothing to do
                        // (avoids a pointless pop+recreate flash).
                        val onOnboarding = route == Routes.SERVER || route == Routes.SERVER_CHANGE ||
                            route == Routes.AUTH || route == Routes.PICKER
                        val atHomeRoot = route == Routes.LIBRARY &&
                            navController.previousBackStackEntry == null
                        if (route != null && !onOnboarding && !atHomeRoot) {
                            navController.navigate(Routes.LIBRARY) {
                                popUpTo(0) // wipe the entire back stack — fresh session
                                launchSingleTop = true
                            }
                        }
                    }
                }
                else -> Unit
            }
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Start-destination decision:
    //  - 0 accounts                              → AUTH (first-run sign-in)
    //  - active account exists but needs reauth  → AUTH (re-sign-in same user)
    //  - 2+ accounts (with or without active)    → PICKER ("who's watching?")
    //  - 1 account, healthy                      → LIBRARY (auto-select)
    //  - default fallback                        → LIBRARY
    // Important: the 2+ branch goes ABOVE the "active != null" branch so the
    // picker always shows on launch when multiple profiles exist, even if
    // one is marked active. Matches the Netflix/Disney+ pattern + the user
    // expectation laid out when the feature was scoped.
    val start = when {
        !bootState.serverConfigured -> Routes.SERVER
        snapshot.accounts.isEmpty() -> Routes.AUTH
        snapshot.activeAccount?.needsReauth() == true -> Routes.AUTH
        snapshot.accounts.size >= 2 -> Routes.PICKER
        snapshot.activeAccount != null -> Routes.LIBRARY
        else -> Routes.LIBRARY
    }

    // Mid-session re-auth trigger: if the active account becomes unusable
    // while the user is somewhere in the navigation stack (token revoked
    // server-side, offline session idle, refresh failed), bounce them to
    // AUTH so they can re-sign-in to the same Keycloak user. Without this
    // they'd hit a wall of API 401s and a black library.
    LaunchedEffect(activeAccount?.id, activeAccount?.expiresAtEpochMillis) {
        val acct = activeAccount ?: return@LaunchedEffect
        if (acct.needsReauth()) {
            navController.navigate(Routes.AUTH) {
                popUpTo(0) // wipe the entire backstack
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = start) {
        composable(Routes.SERVER) {
            val vm: cloud.nalet.chino.tv.ui.onboarding.ServerSetupViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.onboarding.ServerSetupViewModel.factory(container))
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            cloud.nalet.chino.tv.ui.onboarding.ServerSetupScreen(
                viewModel = vm,
                onConnected = {
                    // The AppContainer's lazy clients (OIDC/Retrofit) were built from
                    // the previous (default) server config; save() doesn't rebuild
                    // them. Restart the process so the graph re-reads the just-saved
                    // server — the boot gate then routes to AUTH on the new server.
                    val intent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    appCtx.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                },
            )
        }
        composable(Routes.SERVER_CHANGE) {
            val vm: cloud.nalet.chino.tv.ui.onboarding.ServerSetupViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.onboarding.ServerSetupViewModel.changeServerFactory(container))
            val appCtx = androidx.compose.ui.platform.LocalContext.current.applicationContext
            cloud.nalet.chino.tv.ui.onboarding.ServerSetupScreen(
                viewModel = vm,
                onConnected = {
                    // Same restart path as the first-run SERVER route: the lazy
                    // OIDC/Retrofit clients were built from the OLD server config,
                    // and save() (plus the account wipe the VM just did) doesn't
                    // rebuild them. Restart the process so the graph re-reads the
                    // just-saved server; the boot gate then routes to AUTH because
                    // accounts were cleared.
                    val intent = appCtx.packageManager.getLaunchIntentForPackage(appCtx.packageName)
                    intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    appCtx.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                },
                onCancel = { navController.popBackStack() },
            )
        }
        composable(Routes.ZAP) {
            // Full-screen channel-surf — no rail / top bar (unlike every other
            // destination). BACK returns home; CENTER expands into the real
            // player at the current scene via ?resume=.
            val vm: cloud.nalet.chino.tv.ui.zap.ZapViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.zap.ZapViewModel.factory(container))
            cloud.nalet.chino.tv.ui.zap.ZapScreen(
                viewModel = vm,
                onExpand = { id, resume -> navController.navigate(Routes.player(id, resumeSec = resume)) },
                onHome = {
                    navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } }
                },
            )
        }
        composable(Routes.AUTH) {
            val vm: AuthViewModel = viewModel(factory = AuthViewModel.factory(container))
            DeviceCodeScreen(
                viewModel = vm,
                onAuthenticated = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(0) // clear AUTH + any stale screens
                    }
                },
            )
        }
        composable(Routes.PICKER) {
            AccountPickerScreen(
                accountStore = container.accountStore,
                onPicked = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.PICKER) { inclusive = true }
                    }
                },
                onAddAccount = {
                    navController.navigate(Routes.AUTH)
                },
            )
        }
        composable(Routes.LIBRARY) {
            val vm: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
            // APP-START warm: prefetch the first upcoming Zap card so opening
            // Zap is instant. Idempotent per process (guarded inside the
            // container) and fully off the main thread — a cold home never
            // waits on it.
            LaunchedEffect(Unit) { container.warmFirstZapCard() }
            // Drain crash reports a previous process wrote on its way down.
            // The library only mounts once an account is active, so the
            // bearer the submit needs is available (chino-mobile drains from
            // its signed-in shell the same way). Idempotent per process —
            // BugReporter guards internally, so re-entering home doesn't
            // re-walk the directory.
            LaunchedEffect(Unit) { container.bugReporter.flushPending() }
            LibraryScreen(
                viewModel = vm,
                onItemSelected = { itemId -> navController.navigate(Routes.detail(itemId)) },
                onHeroPlay = { itemId -> navController.navigate(Routes.player(itemId)) { launchSingleTop = true } },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) {
                        popUpTo(Routes.LIBRARY) { inclusive = false }
                    }
                },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onZap = { navController.navigate(Routes.ZAP) },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.MOVIES) {
            val vm: cloud.nalet.chino.tv.ui.browse.BrowseViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.browse.BrowseViewModel.factory(container, "movie"), key = "browse/movie")
            cloud.nalet.chino.tv.ui.browse.BrowseScreen(
                viewModel = vm,
                pageTitle = "Movies",
                type = "movie",
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = {},
                onSeriesNav = { navController.navigate(Routes.SERIES) { popUpTo(Routes.MOVIES) { inclusive = true } } },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.SERIES) {
            val vm: cloud.nalet.chino.tv.ui.browse.BrowseViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.browse.BrowseViewModel.factory(container, "series"), key = "browse/series")
            cloud.nalet.chino.tv.ui.browse.BrowseScreen(
                viewModel = vm,
                pageTitle = "Shows",
                type = "series",
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) { popUpTo(Routes.SERIES) { inclusive = true } } },
                onSeriesNav = {},
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.WATCHLIST) {
            val vm: WatchlistViewModel = viewModel(factory = WatchlistViewModel.factory(container))
            WatchlistScreen(
                viewModel = vm,
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onZapNav = { navController.navigate(Routes.ZAP) },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.NOTICES) {
            val vm: NoticesViewModel = viewModel(factory = NoticesViewModel.factory(container))
            NoticesScreen(
                viewModel = vm,
                // A notice about a title opens its detail screen. launchSingleTop:
                // a remote can deliver one OK twice.
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) { launchSingleTop = true } },
                noticesBell = noticesBell,
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                activeAccount = activeAccount,
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
            SettingsScreen(
                viewModel = vm,
                onSwitchAccount = {
                    navController.navigate(Routes.PICKER) {
                        popUpTo(Routes.LIBRARY) { inclusive = false }
                    }
                },
                // Profile + watch-history (web/mobile parity) — reached from
                // the Account section. launchSingleTop so re-pressing the row
                // doesn't stack copies.
                onProfile = { navController.navigate(Routes.PROFILE) { launchSingleTop = true } },
                onDeleteAccount = if (activeAccount != null) {
                    { navController.navigate(Routes.DELETE_ACCOUNT) { launchSingleTop = true } }
                } else null,
                onChangeServer = { navController.navigate(Routes.SERVER_CHANGE) },
                serverHost = container.serverConfig.baseUrl
                    .substringAfter("://")
                    .substringBefore("/")
                    .takeIf { it.isNotBlank() },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSearch = { navController.navigate(Routes.SEARCH) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.DELETE_ACCOUNT) {
            val vm: cloud.nalet.chino.tv.ui.settings.DeleteAccountViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.settings.DeleteAccountViewModel.factory(container))
            cloud.nalet.chino.tv.ui.settings.DeleteAccountScreen(
                viewModel = vm,
                onCancel = { navController.popBackStack() },
                // Deleted and signed out: back to the start, the back stack
                // gone with the account — who's watching when others are
                // signed in on this TV, else sign-in.
                onSignedOut = { othersRemain ->
                    navController.navigate(if (othersRemain) Routes.PICKER else Routes.AUTH) {
                        popUpTo(0)
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.PROFILE) {
            val vm: cloud.nalet.chino.tv.ui.profile.ProfileViewModel =
                viewModel(factory = cloud.nalet.chino.tv.ui.profile.ProfileViewModel.factory(container))
            cloud.nalet.chino.tv.ui.profile.ProfileScreen(
                viewModel = vm,
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(Routes.SEARCH) {
            val vm: SearchViewModel = viewModel(factory = SearchViewModel.factory(container))
            SearchScreen(
                viewModel = vm,
                onItemSelected = { itemId -> navController.navigate(Routes.detail(itemId)) },
                onPersonSelected = { personId -> navController.navigate(Routes.person(personId)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                // launchSingleTop: the rail Watchlist button routes here from
                // every screen — re-pressing it while the hub is already on
                // top must not stack a second copy.
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
            val vm: DetailViewModel = viewModel(
                factory = DetailViewModel.factory(container, itemId),
                key = "detail/$itemId",
            )
            DetailScreen(
                viewModel = vm,
                onPlay = { id, resume ->
                    // launchSingleTop: the Detail Play button is a tv-material3
                    // Button that fires onClick on DPAD_CENTER key-UP; the BRAVIA
                    // remote can deliver that activation twice, which without this
                    // guard pushes a SECOND player/{id} entry. The duplicate
                    // DETAIL/PLAYER pair is what makes BACK loop back into the
                    // player instead of reaching home (Stube #151).
                    navController.navigate(Routes.player(id, fromStart = !resume)) {
                        launchSingleTop = true
                    }
                },
                onPlayEpisode = { episodeId ->
                    navController.navigate(Routes.player(episodeId)) { launchSingleTop = true }
                },
                // launchSingleTop: a doubled DPAD_CENTER must not stack two.
                onPlayTrailer = { id, extraId ->
                    navController.navigate(Routes.trailer(id, extraId)) { launchSingleTop = true }
                },
                onSimilarSelected = { id -> navController.navigate(Routes.detail(id)) },
                onPersonSelected = { personId -> navController.navigate(Routes.person(personId)) },
            )
        }
        composable(
            route = Routes.TRAILER,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("extraId") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
            val extraId = backStackEntry.arguments?.getString("extraId").orEmpty()
            val vm: TrailerViewModel = viewModel(
                factory = TrailerViewModel.factory(container, itemId, extraId),
                key = "trailer/$itemId/$extraId",
            )
            TrailerScreen(
                viewModel = vm,
                // At the trailer's end. Only while it is still the screen on
                // top: a BACK that came first has popped it already.
                onClose = {
                    if (navController.currentBackStackEntry?.id == backStackEntry.id) {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable(
            route = Routes.PERSON,
            arguments = listOf(navArgument("personId") { type = NavType.StringType }),
        ) { backStackEntry ->
            val personId = backStackEntry.arguments?.getString("personId").orEmpty()
            val vm: PersonViewModel = viewModel(
                factory = PersonViewModel.factory(container, personId),
                key = "person/$personId",
            )
            PersonScreen(
                viewModel = vm,
                onItemSelected = { id -> navController.navigate(Routes.detail(id)) },
                onHomeNav = { navController.navigate(Routes.LIBRARY) { popUpTo(Routes.LIBRARY) { inclusive = true } } },
                onMoviesNav = { navController.navigate(Routes.MOVIES) },
                onSeriesNav = { navController.navigate(Routes.SERIES) },
                onSearch = { navController.navigate(Routes.SEARCH) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onWatchlist = { navController.navigate(Routes.WATCHLIST) { launchSingleTop = true } },
                onAccountClick = {
                    navController.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
                },
                noticesBell = noticesBell,
                activeAccount = activeAccount,
            )
        }
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("itemId") { type = NavType.StringType },
                navArgument("fromStart") { type = NavType.BoolType; defaultValue = false },
                navArgument("fromBinge") { type = NavType.BoolType; defaultValue = false },
                navArgument("resume") { type = NavType.IntType; defaultValue = 0 },
            ),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getString("itemId").orEmpty()
            val fromStart = backStackEntry.arguments?.getBoolean("fromStart") ?: false
            val fromBinge = backStackEntry.arguments?.getBoolean("fromBinge") ?: false
            // Zap hands off with ?resume=<scene sec> so the full player resumes
            // at the channel-surf playhead, not the saved progress (0 for a
            // never-watched teaser). >0 wins over the server progress lookup.
            val resume = backStackEntry.arguments?.getInt("resume") ?: 0
            val vm: PlayerViewModel = viewModel(
                factory = PlayerViewModel.factory(container, PlayMode.Title(itemId, fromStart, fromBinge, resume)),
                // Include fromStart + fromBinge in the key so navigating to
                // the SAME episode with a different entry mode (Resume → Play
                // from start, or manual Next → auto-play-next) rebuilds the
                // VM and re-runs prepare() with the new flags instead of
                // reusing the cached Ready state.
                key = "player/$itemId/fromStart=$fromStart/fromBinge=$fromBinge/resume=$resume",
            )
            PlayerScreen(
                viewModel = vm,
                // Explicit Home affordance in the full-screen player chrome
                // (no persistent rail there). Pops the whole player/detail
                // stack and lands on the library root, matching the home-nav
                // used by the other screens' rail buttons.
                onHome = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.LIBRARY) { inclusive = true }
                    }
                },
                onPlayNext = { nextId ->
                    // Auto-chain into the next episode in binge mode so the
                    // VM pre-skips its intro. Manual prev/next buttons in the
                    // chrome route through the same callback but we can't
                    // distinguish here — both feel like "user wants the next
                    // episode now", so both get the pre-skip. If we want a
                    // different behavior for the chrome buttons later we'd
                    // need to thread a separate callback through.
                    navController.navigate(Routes.player(nextId, fromBinge = true)) {
                        popUpTo(Routes.PLAYER) { inclusive = true }
                    }
                },
            )
        }
    }
}

/** Brand-coloured cold-launch splash. Shows for the duration of the
 *  AccountStore EncryptedSharedPreferences open + decrypt — typically
 *  <300 ms on real Bravia hardware, 1-3 s on AOSP x86 emulators where
 *  AndroidKeyStore is software-emulated. The same `>c` logo we render in
 *  the LibraryScreen top bar, centered on the dark canvas, so the
 *  transition into the library feels like the logo "finds its place"
 *  rather than the screen flashing through a blank intermediate state. */
@Composable
private fun BootSplash() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ChinoBg),
        contentAlignment = Alignment.Center,
    ) {
        LogoMark(sizeDp = 64)
    }
}
