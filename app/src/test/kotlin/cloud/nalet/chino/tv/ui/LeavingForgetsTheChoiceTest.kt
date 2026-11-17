package cloud.nalet.chino.tv.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.LastKnownInMemory
import cloud.nalet.chino.tv.data.LocalServer
import cloud.nalet.chino.tv.data.NoticesRepository
import cloud.nalet.chino.tv.data.ServerConfigStore
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.ui.onboarding.ServerSetupState
import cloud.nalet.chino.tv.ui.onboarding.ServerSetupViewModel
import cloud.nalet.chino.tv.ui.settings.DeleteAccountState
import cloud.nalet.chino.tv.ui.settings.DeleteAccountViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * An account that leaves the TV takes the Separate movies and shows last
 * known for it with it (section 16.4.7): deleted, or cleared by Change server
 * (removed on who's watching: AccountChangeTest). The other accounts keep
 * theirs where they stay.
 */
@RunWith(RobolectricTestRunner::class)
class LeavingForgetsTheChoiceTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val store = AccountStore(
        ctx.getSharedPreferences("leaving-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    )
    private val lastKnown = LastKnownInMemory()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** chino-api for the account: it deletes the account it is asked to. */
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

    private val settings = AccountSettings(
        api, appScope, activeChanges = { store.activeChangesNow() }, lastKnown = lastKnown,
    ) { store.activeChoiceBlocking() }

    private var server: LocalServer? = null

    @After
    fun stop() {
        server?.stop()
    }

    private fun account(id: String) = Account(
        id = id, displayName = id, email = "", accessToken = "access-of-$id", refreshToken = "refresh-of-$id",
        expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    /** [ids] signed in, the first active, each with a choice last known. */
    private fun signedIn(vararg ids: String) {
        runBlocking {
            ids.forEach { store.addOrUpdate(account(it)) }
            store.setActive(ids.first())
        }
        ids.forEachIndexed { i, id -> lastKnown.keep(id, i % 2 == 0) }
    }

    /** Runs the main thread until [done]: the view models' work comes back
     *  to it. */
    private fun waitFor(done: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 10 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `16_4_7 an account deleted takes the choice last known for it, and only its own`() {
        signedIn("parent", "child")
        val vm = DeleteAccountViewModel(
            api = api,
            accounts = store,
            streamTokens = StreamTokenManager(api) { ActiveChoice("parent", 0) },
            userFlags = UserFlagsRepository(api, activeChanges = { 0 }) { ActiveChoice("parent", 0) },
            notices = NoticesRepository(api = { null }, activeAccount = { "parent" }),
            forgetAccount = settings::forget,
            appScope = appScope,
            account = store.snapshotBlocking().activeAccount,
            serverHost = "media.test",
        )
        vm.delete()
        waitFor { vm.state.value is DeleteAccountState.SignedOut }
        assertNull(lastKnown.of("parent"))
        assertEquals(false, lastKnown.of("child"))
    }

    @Test
    fun `16_4_7 Change server clearing the accounts takes the choice last known for each`() {
        val local = LocalServer { method, path ->
            val issuer = "${server!!.origin}/realms/test"
            when {
                method != "GET" -> null
                path == "/api/healthz" -> 200 to """{"status":"ok","product":"chino"}"""
                path == "/api/config" -> 200 to """{"oidcIssuer":"$issuer","oidcClientId":{"tv":"chino-tv"}}"""
                path == "/realms/test/.well-known/openid-configuration" -> 200 to
                    """{"issuer":"$issuer","token_endpoint":"$issuer/token","device_authorization_endpoint":"$issuer/device"}"""
                else -> null
            }
        }
        server = local
        signedIn("parent", "child", "guest")
        val vm = ServerSetupViewModel(
            store = ServerConfigStore(ctx),
            presetUrl = "",
            changeServer = true,
            prefillUrl = "https://media.test",
            accountStore = store,
            forgetAccount = settings::forget,
        )
        vm.connect(local.origin)
        waitFor { vm.state.value == ServerSetupState.Done || vm.state.value is ServerSetupState.Error }
        assertEquals(ServerSetupState.Done, vm.state.value)
        assertTrue(store.snapshotBlocking().accounts.isEmpty())
        listOf("parent", "child", "guest").forEach { assertNull(it, lastKnown.of(it)) }
    }
}
