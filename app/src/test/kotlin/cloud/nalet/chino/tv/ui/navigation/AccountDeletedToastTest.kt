package cloud.nalet.chino.tv.ui.navigation

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.data.auth.DeletedAccounts
import cloud.nalet.chino.tv.data.auth.Removal
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.ui.settings.DeleteAccountViewModel
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.TOAST_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An account the server deleted, signed out on this TV, is said in the toast
 * "Account deleted" (section 16.5.6 of the specification), over the screen the
 * app starts over on as for an account removed: who's watching where another
 * account is signed in, sign-in where none is; so is one deleted on this TV,
 * through Delete account's route. The navigation is the app's own
 * [AccountNavHost] on the account store, with the app's Delete account route
 * ([deleteAccountScreen]); the toast's clock is the test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class AccountDeletedToastTest {
    @get:Rule val compose = createComposeRule()

    private val store = AccountStore(
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("account-deleted-toast-test", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() },
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Signs an account out as the container does: off the account store,
     *  nobody active after it where it was the active one. */
    private val deletedAccounts = DeletedAccounts(appScope) { id -> store.remove(id, nextActive = false) != Removal.NOT_HERE }

    /** chino-api for Delete account: it deletes the account it is asked to. */
    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "deleteMe" -> retrofit2.Response.success("""{"account":"deleted","deleted":{}}""".toResponseBody())
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw IllegalStateException("not asked here")
        }
    } as ChinoApi

    /** Delete account's view model, for the account active as it opens, as
     *  the app's factory builds it. */
    private fun deleteAccountViewModel() = DeleteAccountViewModel(
        api = api,
        accounts = store,
        streamTokens = StreamTokenManager(api) { store.activeChoiceBlocking() },
        userFlags = UserFlagsRepository(api, activeChanges = { store.activeChangesNow() }) { store.activeChoiceBlocking() },
        notices = NoticesRepository(api = { null }, activeAccount = { store.snapshotBlocking().activeAccount?.id }),
        forgetAccount = {},
        appScope = appScope,
        account = store.snapshotBlocking().activeAccount,
        serverHost = "example.com",
    )

    private lateinit var nav: NavHostController

    private fun account(id: String) = Account(
        id = id, displayName = id.replaceFirstChar(Char::uppercase), email = "", accessToken = "access-of-$id",
        refreshToken = "refresh-of-$id", expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    /** [ids] signed in, the first active; the app on its settings, over its home. */
    private fun host(vararg ids: String) {
        runBlocking {
            ids.forEach { store.addOrUpdate(account(it)) }
            store.setActive(ids.first())
        }
        val boot = store.snapshotBlocking()
        compose.setContent {
            nav = rememberNavController()
            ChinoTvTheme {
                AccountNavHost(
                    nav, store, boot, startDestination = Routes.LIBRARY, forgetAccount = {},
                    deleted = deletedAccounts.signedOut,
                ) {
                    composable(Routes.LIBRARY) {}
                    composable(Routes.AUTH) {}
                    composable(Routes.SETTINGS) {}
                    // The app's own route, its toast said as the app says it.
                    deleteAccountScreen(nav, deletedHere = deletedAccounts::deletedHere) {
                        remember { deleteAccountViewModel() }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        compose.waitForIdle()
        // From here the test moves the clock, frame by frame.
        compose.mainClock.autoAdvance = false
    }

    private fun toastShown() = compose.onAllNodes(hasText(ACCOUNT_DELETED_TOAST)).fetchSemanticsNodes().isNotEmpty()

    /** Moves the clock a frame at a time until [done], in under a second of it. */
    private fun framesUntil(what: String, done: () -> Boolean) {
        repeat(60) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            if (done()) return
            Thread.sleep(20)
        }
        val stack = nav.currentBackStack.value.mapNotNull { it.destination.route }
        throw AssertionError("not $what: the back stack is $stack, signed in ${store.snapshotBlocking().accounts.map { it.id }}")
    }

    /** Delete account opened from the settings, as Settings → Account goes. */
    private fun openDeleteAccount() {
        compose.mainClock.autoAdvance = true
        compose.runOnIdle { nav.navigate(Routes.DELETE_ACCOUNT) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        assertEquals(Routes.DELETE_ACCOUNT, nav.currentDestination?.route)
    }

    private fun aloneAt(route: String) = nav.currentDestination?.route == route && nav.previousBackStackEntry == null

    @Test
    fun `16_5_6 the active account deleted - who's watching, with the toast, which goes after five seconds`() {
        host("parent", "child")
        deletedAccounts.deleted("parent")
        framesUntil("on who's watching with the toast") { aloneAt(Routes.PICKER) && toastShown() }
        assertEquals(listOf("child"), store.snapshotBlocking().accounts.map { it.id })
        // The account left is not made active.
        assertNull(store.snapshotBlocking().activeAccount)
        // Still there a second before its five seconds are up; gone after.
        compose.mainClock.advanceTimeBy(TOAST_MS - 1_000)
        compose.waitForIdle()
        assertEquals(true, toastShown())
        compose.mainClock.advanceTimeBy(1_000)
        framesUntil("the toast gone") { !toastShown() }
        assertEquals(Routes.PICKER, nav.currentDestination?.route)
    }

    @Test
    fun `16_5_6 the only account deleted - sign-in, with the toast`() {
        host("parent")
        deletedAccounts.deleted("parent")
        framesUntil("on sign-in with the toast") { aloneAt(Routes.AUTH) && toastShown() }
        assertNull(store.snapshotBlocking().activeAccount)
    }

    @Test
    fun `16_5_6 another account than the active one deleted - the toast, and the screen stays`() {
        host("parent", "child")
        deletedAccounts.deleted("child")
        framesUntil("the toast") { toastShown() }
        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
        assertEquals("parent", store.snapshotBlocking().activeAccount?.id)
    }

    @Test
    fun `16_5_6 an account deleted on this TV, through Delete account's route - who's watching, with the toast, nobody active`() {
        host("parent", "child")
        openDeleteAccount()
        compose.onNode(hasText("delete account") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        framesUntil("on who's watching with the toast") { aloneAt(Routes.PICKER) && toastShown() }
        assertEquals(listOf("child"), store.snapshotBlocking().accounts.map { it.id })
        assertNull(store.snapshotBlocking().activeAccount)
    }

    @Test
    fun `16_5_6 the only account deleted on this TV, through Delete account's route - sign-in, with the toast`() {
        host("parent")
        openDeleteAccount()
        compose.onNode(hasText("delete account") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        framesUntil("on sign-in with the toast") { aloneAt(Routes.AUTH) && toastShown() }
        assertEquals(emptyList<String>(), store.snapshotBlocking().accounts.map { it.id })
    }

    @Test
    fun `an account no longer signed in here, answered deleted, is not said`() {
        host("parent")
        deletedAccounts.deleted("someone-else")
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            Thread.sleep(10)
        }
        assertEquals(false, toastShown())
        assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
    }
}
