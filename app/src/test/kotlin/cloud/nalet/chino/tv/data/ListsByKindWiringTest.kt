package cloud.nalet.chino.tv.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.settings.ListsByKindSetting
import cloud.nalet.chino.tv.ui.settings.SettingsViewModel
import cloud.nalet.chino.tv.ui.watchlist.WatchlistUiState
import cloud.nalet.chino.tv.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The container as the app builds it, on a chino-api of the test's: home's
 * warm asks the server's features and the account's choice through the
 * container's own client, the settings' and the watchlist's factories hand
 * their view models the container's account settings and lists by kind, and
 * the choice is kept on the device under a digest and forgotten with the
 * account. Only the account store is the test's (plain preferences in place
 * of the encrypted ones).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ListsByKindWiringTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private val server = LocalServer { method, path ->
        when ("$method $path") {
            "GET /api/config" -> 200 to """{"features":{"feedback":false,"watchlist_kinds":true}}"""
            "GET /api/v1/me/settings" -> 200 to """{"watchlist_by_kind":true}"""
            "GET /api/v1/me/watchlists" -> 200 to """{"lists":[{"id":"l1","name":"Watchlist","itemCount":1,"isDefault":true}]}"""
            "GET /api/v1/me/watchlists/l1?kind=movie" -> 200 to """{"id":"l1","name":"Watchlist","items":["m1"]}"""
            "GET /api/v1/me/watchlists/l1?kind=series" -> 200 to """{"id":"l1","name":"Watchlist","items":[]}"""
            "GET /api/v1/items/m1" -> 200 to """{"id":"m1","title":"Harbour Light","type":"movie","year":2019}"""
            "POST /api/v1/me/stream-token" -> 200 to """{"stream_token":"t"}"""
            else -> null
        }
    }

    private val lastKnownOnDevice = ctx.getSharedPreferences("account_settings", Context.MODE_PRIVATE)

    private val container: AppContainer

    init {
        lastKnownOnDevice.edit().clear().commit()
        runBlocking {
            ServerConfigStore(ctx).save(
                ServerConfig(baseUrl = "${server.origin}/api/", issuer = "${server.origin}/realms/test", clientId = "chino-tv"),
            )
        }
        val accounts = AccountStore(
            ctx.getSharedPreferences("wiring-test-accounts", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
        )
        runBlocking {
            accounts.addOrUpdate(
                Account(
                    id = "viewer", displayName = "Viewer", email = "", accessToken = "viewer-token",
                    refreshToken = "refresh", expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
                ),
                setActive = true,
            )
        }
        container = AppContainer(ctx, accounts)
    }

    @After
    fun stop() = server.stop()

    /** Runs the main thread until [done]: the view models' work comes back
     *  to it. */
    private fun waitFor(done: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 10 s; asked ${server.seen}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `16_4_7 home's warm, then settings from the factory - the group at the first state, kept on the device under a digest`() {
        container.warmListsByKind()
        waitFor { container.listsByKind.now() }
        assertTrue(server.seen.toString(), "GET /api/config" in server.seen && "GET /api/v1/me/settings" in server.seen)
        val settings = SettingsViewModel.factory(container).create(SettingsViewModel::class.java)
        assertEquals(ListsByKindSetting(shown = true, reading = true), settings.listsByKind.value)
        waitFor { !settings.listsByKind.value.busy }
        assertEquals(true, settings.listsByKind.value.shown)
        // The choice last known, under a digest of the account's id.
        assertEquals(1, lastKnownOnDevice.all.size)
        assertTrue(lastKnownOnDevice.all.keys.none { "viewer" in it })
        // The account gone from the TV: its choice too.
        container.forgetAccount("viewer")
        assertTrue(lastKnownOnDevice.all.isEmpty())
        assertNull(container.accountSettings.watchlistByKind.value)
    }

    @Test
    fun `8_C18 the watchlist from the factory asks every list by kind, as the container's setting says`() {
        val watchlist = WatchlistViewModel.factory(container).create(WatchlistViewModel::class.java)
        waitFor { (watchlist.state.value as? WatchlistUiState.Ready)?.byKind != null }
        val ready = watchlist.state.value as WatchlistUiState.Ready
        assertNotNull(ready.byKind?.get("l1"))
        assertTrue(server.seen.toString(), "GET /api/v1/me/watchlists/l1?kind=movie" in server.seen)
        assertEquals(true, container.accountSettings.watchlistByKind.value)
    }

    @Test
    fun `16_4_7 an account forgotten before anything asked for its settings loses its choice all the same, asking the server nothing`() {
        LastKnownOnDevice(lastKnownOnDevice).keep("viewer", true)
        container.forgetAccount("viewer")
        assertTrue(lastKnownOnDevice.all.isEmpty())
        assertTrue(server.seen.toString(), server.seen.isEmpty())
    }
}
