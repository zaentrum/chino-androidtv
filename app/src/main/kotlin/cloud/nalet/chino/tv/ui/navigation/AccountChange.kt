package cloud.nalet.chino.tv.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.auth.AccountPickerScreen
import cloud.nalet.chino.tv.ui.theme.ShellScreen

/** The back stack entry's note of the account its screen was started for. */
private const val STARTED_FOR = "startedFor"

/** [STARTED_FOR] of a screen started while nobody was active. */
private const val NOBODY = ""

/**
 * Starts over at [route], started for [forAccount] (the account active now):
 * the route is then all the back stack holds, so BACK cannot return to a screen
 * of an account before, with its rows and its stream token in its state
 * (change P.C16 of the specification). Where the back stack is that one screen
 * already it stays as it is - for an account's home, only where that home was
 * started for [forAccount]; who's watching and sign-in are nobody's.
 */
internal fun NavController.startOverAt(route: String, forAccount: String?) {
    val lone = previousBackStackEntry == null && currentDestination?.route == route
    if (lone) {
        val startedFor = runCatching { currentBackStackEntry?.savedStateHandle?.get<String>(STARTED_FOR) }.getOrNull()
        if (route != Routes.LIBRARY || startedFor == (forAccount ?: NOBODY)) return
    }
    navigate(route) { popUpTo(graph.id) { inclusive = true } }
    runCatching { currentBackStackEntry?.savedStateHandle?.set(STARTED_FOR, forAccount ?: NOBODY) }
}

/** True when the account needs a fresh device-flow sign-in: the access token
 *  has already expired AND there's no refresh token to swap (legacy migration
 *  accounts, revoked sessions, or post-`offline_session_idle`). */
internal fun Account.needsReauth(): Boolean {
    val expired = System.currentTimeMillis() >= expiresAtEpochMillis
    val noRefresh = refreshToken.isNullOrBlank()
    return expired && noRefresh
}

/**
 * Where the app starts over when the active account is [now], the back stack's
 * screens having been made for [before], with [accounts] signed in; null where
 * it goes on as it is.
 * - The active account's sign-in no longer holds: sign-in.
 * - The account before is no longer signed in (removed): who's watching, or
 *   sign-in when nobody is left.
 * - Another account chosen or added: its home.
 */
internal fun startOverFor(before: String?, now: Account?, accounts: List<Account>): String? = when {
    now?.needsReauth() == true -> Routes.AUTH
    now?.id == before -> null
    now == null -> if (accounts.isEmpty()) Routes.AUTH else Routes.PICKER
    before != null && accounts.none { it.id == before } -> Routes.PICKER
    else -> Routes.LIBRARY
}

/**
 * Starts over each time the active account changes, whoever changed it, and
 * also where the screen that changed it is gone by then (a BACK pressed in
 * between): nothing of the account before stays on the back stack. [now] is the
 * account store as the screen shows it, the accounts and the active one as one
 * value; [initial] the account active when the back stack was started.
 *
 * Not while Change server is on the screen: it removes every account, saves the
 * new server and restarts the app on it, and its screen stays until then. Left
 * without that restart (BACK, or a removal that failed part-way), the app
 * starts over then.
 */
@Composable
internal fun StartOverOnAccountChange(nav: NavHostController, now: AccountStore.Snapshot, initial: String?) {
    val shownFor = remember { mutableStateOf(initial) }
    val entry by nav.currentBackStackEntryAsState()
    val onServerChange = entry?.destination?.route == Routes.SERVER_CHANGE
    LaunchedEffect(now.activeAccount?.id, now.activeAccount?.expiresAtEpochMillis, onServerChange) {
        if (onServerChange) return@LaunchedEffect
        val route = startOverFor(shownFor.value, now.activeAccount, now.accounts) ?: return@LaunchedEffect
        shownFor.value = now.activeAccount?.id
        nav.startOverAt(route, now.activeAccount?.id)
    }
}

/**
 * The app's navigation as it goes with the active account: the account store
 * followed, [StartOverOnAccountChange] on it, and who's watching
 * ([WhoIsWatching]) among the routes, beside the other [routes]. [boot] is the
 * store as it was when the app started, [startDestination] where it started.
 */
@Composable
internal fun AccountNavHost(
    nav: NavHostController,
    accountStore: AccountStore,
    boot: AccountStore.Snapshot,
    startDestination: String,
    routes: NavGraphBuilder.() -> Unit,
) {
    val now by accountStore.snapshots.collectAsState(initial = boot)
    StartOverOnAccountChange(nav, now, boot.activeAccount?.id)
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.PICKER) { ShellScreen { WhoIsWatching(nav, accountStore) } }
        routes()
    }
}

/** Who's watching, as the app's back stack goes with it: an account chosen
 *  starts on its home, the one active already too. The active one removed
 *  changes the active account, and [StartOverOnAccountChange] starts over. */
@Composable
internal fun WhoIsWatching(nav: NavController, accountStore: AccountStore) {
    AccountPickerScreen(
        accountStore = accountStore,
        onPicked = { account -> nav.startOverAt(Routes.LIBRARY, account.id) },
        onAddAccount = { nav.navigate(Routes.AUTH) },
    )
}
