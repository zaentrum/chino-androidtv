package cloud.nalet.chino.tv.ui.navigation

import android.content.Context
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.LastKnownInMemory
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whichever way another account becomes the active one, the app starts over:
 * BACK leads to no screen of the account before (change P.C16 of the
 * specification). The navigation is the app's own [AccountNavHost], the one
 * ChinoTvNavHost builds its routes in, on the account store: who's watching
 * and the start-over are its; the other screens stand in empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class AccountChangeTest {
    @get:Rule val compose = createComposeRule()

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("account-change-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
    private val store = AccountStore(prefs)

    /** Each account's Separate movies and shows as last known on this TV,
     *  through the account settings an account removed is forgotten by. */
    private val lastKnown = LastKnownInMemory()
    private val settings = AccountSettings(
        java.lang.reflect.Proxy.newProxyInstance(ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java)) { _, _, _ ->
            throw IllegalStateException("no server")
        } as ChinoApi,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        activeChanges = { store.activeChangesNow() },
        lastKnown = lastKnown,
    ) { store.activeChoiceBlocking() }

    private lateinit var nav: NavHostController

    private fun account(id: String, expired: Boolean = false) = Account(
        id = id, displayName = id.replaceFirstChar(Char::uppercase), email = "", accessToken = "access-of-$id",
        refreshToken = if (expired) null else "refresh-of-$id",
        expiresAtEpochMillis = if (expired) 0 else Long.MAX_VALUE, lastUsedAt = 0,
    )

    /** [ids] signed in, the first active; the app on its home. */
    private fun host(vararg ids: String) {
        runBlocking {
            ids.forEach { store.addOrUpdate(account(it)) }
            store.setActive(ids.first())
        }
        val boot = store.snapshotBlocking()
        compose.setContent {
            nav = rememberNavController()
            ChinoTvTheme {
                AccountNavHost(nav, store, boot, startDestination = Routes.LIBRARY, forgetAccount = settings::forget) {
                    composable(Routes.LIBRARY) {}
                    composable(Routes.AUTH) {}
                    composable(Routes.SETTINGS) {}
                    composable(Routes.SERVER_CHANGE) {}
                }
            }
        }
        compose.waitForIdle()
    }

    /** Home, then who's watching from the rail, as the library's onAccountClick goes. */
    private fun openPickerFromHome() {
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.navigate(Routes.PICKER) { popUpTo(Routes.LIBRARY) { inclusive = false } }
        }
        compose.waitForIdle()
    }

    /** Waits until [route] is all the back stack holds; [shown] the store
     *  the app shows. */
    private fun assertAloneAt(route: String, shown: AccountStore = store) {
        try {
            compose.waitUntil(5_000) {
                nav.currentDestination?.route == route && nav.previousBackStackEntry == null
            }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val stack = compose.runOnIdle { nav.currentBackStack.value.mapNotNull { it.destination.route } }
            val now = shown.snapshotBlocking()
            throw AssertionError(
                "not alone at $route: the back stack is $stack, the active account " +
                    "${now.activeAccount?.id}, signed in ${now.accounts.map { it.id }}",
                e,
            )
        }
        compose.runOnIdle {
            assertEquals(route, nav.currentDestination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    private fun tile(name: String) = compose.onNode(hasText(name) and hasClickAction())

    /** The confirm dialog's button, pressed as the remote does; its card, which
     *  says the same, takes no click. */
    private fun removeButton() = compose.onNode(hasText("remove account") and hasClickAction() and isEnabled())

    @Test
    fun `P_C16 another account chosen on who's watching starts on a home of its own, with nothing below it`() {
        host("parent", "child")
        openPickerFromHome()
        tile("Child").performClick()
        assertAloneAt(Routes.LIBRARY)
        compose.runOnIdle { assertFalse(nav.popBackStack()) }
        assertEquals("child", store.snapshotBlocking().activeAccount?.id)
    }

    @Test
    fun `the account already active chosen on who's watching starts on its home too`() {
        host("parent", "child")
        openPickerFromHome()
        tile("Parent").performClick()
        assertAloneAt(Routes.LIBRARY)
    }

    @Test
    fun `P_C16 the active account removed on who's watching leaves who's watching alone on the stack`() {
        host("parent", "child")
        openPickerFromHome()
        tile("Parent").performTouchInput { longClick() }
        removeButton().performSemanticsAction(SemanticsActions.OnClick)
        assertAloneAt(Routes.PICKER)
    }

    @Test
    fun `16_4_7 an account removed on who's watching takes the choice last known for it, and only its own`() {
        host("parent", "child")
        lastKnown.keep("parent", true)
        lastKnown.keep("child", false)
        openPickerFromHome()
        tile("Child").performTouchInput { longClick() }
        removeButton().performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntil(5_000) { lastKnown.of("child") == null }
        assertEquals(listOf("parent"), store.snapshotBlocking().accounts.map { it.id })
        assertEquals(true, lastKnown.of("parent"))
    }

    @Test
    fun `P_C16 the last account removed leaves sign-in alone on the stack`() {
        host("parent")
        openPickerFromHome()
        tile("Parent").performTouchInput { longClick() }
        removeButton().performSemanticsAction(SemanticsActions.OnClick)
        assertAloneAt(Routes.AUTH)
    }

    @Test
    fun `P_C16 another account active while the screen that chose it is gone starts over all the same`() {
        host("parent", "child")
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        // Chosen by no screen that navigates: as where a BACK took the picker
        // off the stack between the choice and its navigation.
        runBlocking { store.setActive("child") }
        assertAloneAt(Routes.LIBRARY)
    }

    @Test
    fun `P_C16 an account added on sign-in starts on a home of its own, with nothing below it`() {
        host("parent")
        openPickerFromHome()
        compose.runOnIdle { nav.navigate(Routes.AUTH) }
        runBlocking { store.addOrUpdate(account("child"), setActive = true) }
        assertAloneAt(Routes.LIBRARY)
    }

    @Test
    fun `P_C16 the active account deleted elsewhere leaves who's watching alone on the stack`() {
        host("parent", "child")
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        runBlocking { store.remove("parent") }
        assertAloneAt(Routes.PICKER)
    }

    @Test
    fun `a sign-in that no longer holds leaves sign-in alone on the stack`() {
        host("parent")
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        runBlocking { store.addOrUpdate(account("parent", expired = true)) }
        assertAloneAt(Routes.AUTH)
    }

    @Test
    fun `starting over at a home started for the account active keeps it - the pick's home is made once`() {
        host("parent", "child")
        openPickerFromHome()
        tile("Child").performClick()
        assertAloneAt(Routes.LIBRARY)
        val home = compose.runOnIdle { nav.currentBackStackEntry }
        // The start-over that follows the change of the active account, and any
        // other for the same account, keeps the home the pick started.
        compose.waitForIdle()
        compose.runOnIdle { nav.startOverAt(Routes.LIBRARY, "child") }
        compose.runOnIdle { assertSame(home, nav.currentBackStackEntry) }
    }

    @Test
    fun `P_C16 another account active while the stack is the home of the one before alone - a home of its own`() {
        host("parent", "child")
        val parentsHome = compose.runOnIdle { nav.currentBackStackEntry }
        runBlocking { store.setActive("child") }
        compose.waitUntil(5_000) { nav.currentBackStackEntry !== parentsHome }
        assertAloneAt(Routes.LIBRARY)
    }

    @Test
    fun `who's watching and sign-in alone stay as they are, whoever is active`() {
        host("parent")
        compose.runOnIdle { nav.startOverAt(Routes.AUTH, forAccount = null) }
        assertAloneAt(Routes.AUTH)
        val signIn = compose.runOnIdle { nav.currentBackStackEntry }
        compose.runOnIdle { nav.startOverAt(Routes.AUTH, "parent") }
        compose.runOnIdle { assertSame(signIn, nav.currentBackStackEntry) }
    }

    @Test
    fun `Change server removing every account keeps its screen until it restarts the app`() {
        host("parent", "child")
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.navigate(Routes.SERVER_CHANGE)
        }
        compose.waitForIdle()
        runBlocking {
            store.remove("parent")
            store.remove("child")
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(Routes.SERVER_CHANGE, nav.currentDestination?.route)
            assertEquals(Routes.SETTINGS, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `Change server left with BACK after every account was removed starts over at sign-in`() {
        host("parent", "child")
        compose.runOnIdle {
            nav.navigate(Routes.SETTINGS)
            nav.navigate(Routes.SERVER_CHANGE)
        }
        compose.waitForIdle()
        runBlocking {
            store.remove("parent")
            store.remove("child")
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.popBackStack() }
        assertAloneAt(Routes.AUTH)
    }

    /**
     * The parent active, on its settings over its home; then the process
     * killed, the parent deleted and signed out meanwhile (the player's save
     * loop runs while the app is stopped), and the app opened again with its
     * saved back stack, as Android restores it: the store is then [after].
     */
    private fun restoredAfter(after: AccountStore) {
        runBlocking {
            store.addOrUpdate(account("parent"))
            store.addOrUpdate(account("child"))
            store.setActive("parent")
        }
        var shown = store
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavController()
            val now = shown
            // Read as ChinoTvNavHost reads it, as the app starts.
            val boot = remember { now.snapshotBlocking() }
            ChinoTvTheme {
                AccountNavHost(nav, now, boot, startDestination = Routes.LIBRARY, forgetAccount = {}) {
                    composable(Routes.LIBRARY) {}
                    composable(Routes.AUTH) {}
                    composable(Routes.SETTINGS) {}
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { nav.navigate(Routes.SETTINGS) }
        compose.waitForIdle()
        shown = after
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
    }

    /** The accounts as the app finds them as it starts again: [ids] signed
     *  in, [active] the active one, or nobody. */
    private fun storeAsRestarted(vararg ids: String, active: String?): AccountStore {
        val after = AccountStore(
            ApplicationProvider.getApplicationContext<Context>()
                .getSharedPreferences("account-change-test-after", Context.MODE_PRIVATE)
                .also { it.edit().clear().commit() },
        )
        runBlocking {
            ids.forEach { after.addOrUpdate(account(it)) }
            active?.let { after.setActive(it) }
        }
        return after
    }

    @Test
    fun `16_5_6 a back stack restored after its account was deleted, nobody active, starts over at who's watching`() {
        val after = storeAsRestarted("child", active = null)
        restoredAfter(after)
        assertAloneAt(Routes.PICKER, shown = after)
    }

    @Test
    fun `16_5_6 a back stack restored after its account was deleted, another active, starts over at who's watching`() {
        val after = storeAsRestarted("child", active = "child")
        restoredAfter(after)
        assertAloneAt(Routes.PICKER, shown = after)
    }

    @Test
    fun `a back stack restored with its account still active stays as it was`() {
        restoredAfter(storeAsRestarted("parent", "child", active = "parent"))
        compose.runOnIdle {
            assertEquals(Routes.SETTINGS, nav.currentDestination?.route)
            assertEquals(Routes.LIBRARY, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `where the app starts, from the accounts as it starts`() {
        val parent = account("parent")
        val child = account("child")
        fun start(accounts: List<Account>, active: Account?, configured: Boolean = true) =
            startDestination(configured, AccountStore.Snapshot(accounts, active))
        assertEquals(Routes.SERVER, start(emptyList(), null, configured = false))
        assertEquals(Routes.AUTH, start(emptyList(), null))
        assertEquals(Routes.AUTH, start(listOf(account("parent", expired = true)), account("parent", expired = true)))
        assertEquals(Routes.PICKER, start(listOf(parent, child), parent))
        assertEquals(Routes.LIBRARY, start(listOf(parent), parent))
        // 16.5.6: the active account deleted, the one left not made active.
        assertEquals(Routes.PICKER, start(listOf(child), null))
        assertEquals(Routes.PICKER, start(listOf(parent, child), null))
    }

    @Test
    fun `where the app starts over, from the account before to the active one`() {
        val parent = account("parent")
        val child = account("child")
        assertNull(startOverFor("parent", parent, listOf(parent, child)))
        assertEquals(Routes.LIBRARY, startOverFor("parent", child, listOf(parent, child)))
        assertEquals(Routes.LIBRARY, startOverFor(null, parent, listOf(parent)))
        assertEquals(Routes.PICKER, startOverFor("parent", child, listOf(child)))
        assertEquals(Routes.AUTH, startOverFor("parent", null, emptyList()))
        assertEquals(Routes.AUTH, startOverFor("parent", account("parent", expired = true), listOf(parent)))
    }
}
