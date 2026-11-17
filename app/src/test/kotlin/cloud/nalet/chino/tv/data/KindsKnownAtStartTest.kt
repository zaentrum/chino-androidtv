package cloud.nalet.chino.tv.data

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.settings.ListsByKindSetting
import cloud.nalet.chino.tv.ui.settings.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Settings and the watchlist know whether the server fills lists by kind
 * from their first frame after any start (sections 16.4.7 and 8.8.1): what
 * the server said the last time it answered is kept on this TV with the
 * server's config, and the start holds it, with the active account's choice
 * as last known here - right after a start on a slow server, after a read of
 * /api/config that failed, in a process restored straight into settings. A
 * change of server forgets it. The container as the app builds it, on a
 * chino-api of the test's; only the account store is the test's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class KindsKnownAtStartTest {
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** How /api/config answers: not while the test looks, with an error, or
     *  saying the server fills lists by kind, or that it doesn't. */
    private enum class ConfigAnswer { Held, Fails, Offers, OffersNot }

    @Volatile private var config = ConfigAnswer.Offers
    private val configGoesOn = CountDownLatch(1)

    private val server = LocalServer { method, path ->
        when ("$method $path") {
            "GET /api/config" -> when (config) {
                ConfigAnswer.Held -> {
                    configGoesOn.await(20, TimeUnit.SECONDS)
                    503 to "{}"
                }
                ConfigAnswer.Fails -> 503 to "{}"
                ConfigAnswer.Offers -> 200 to """{"features":{"feedback":false,"watchlist_kinds":true}}"""
                ConfigAnswer.OffersNot -> 200 to """{"features":{"feedback":false,"watchlist_kinds":false}}"""
            }
            "GET /api/v1/me/settings" -> 200 to """{"watchlist_by_kind":true}"""
            "PATCH /api/v1/me/settings" -> 200 to """{"watchlist_by_kind":false}"""
            else -> null
        }
    }

    private val base = "${server.origin}/api"

    private val lastKnownOnDevice = ctx.getSharedPreferences("account_settings", Context.MODE_PRIVATE)

    private val accounts = AccountStore(
        ctx.getSharedPreferences("kinds-at-start-accounts", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    )

    init {
        lastKnownOnDevice.edit().clear().commit()
        runBlocking {
            ServerConfigStore(ctx).save(ServerConfig(baseUrl = "$base/", issuer = "${server.origin}/realms/test", clientId = "chino-tv"))
            accounts.addOrUpdate(
                Account(
                    id = "viewer", displayName = "Viewer", email = "", accessToken = "viewer-token",
                    refreshToken = "refresh", expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
                ),
                setActive = true,
            )
        }
        // The account's choice as last known on this TV: on.
        LastKnownOnDevice(lastKnownOnDevice).keep("viewer", true)
    }

    @After
    fun stop() {
        configGoesOn.countDown()
        server.stop()
    }

    /** The app's graph as a start builds it, and the start's hold, off the
     *  main thread as the start does it. */
    private fun started(): AppContainer = AppContainer(ctx, accounts).also { runBlocking { it.holdListsByKind() } }

    /** Settings opened: their view model from the app's factory. */
    private fun settings(c: AppContainer) = SettingsViewModel.factory(c).create(SettingsViewModel::class.java)

    private fun waitFor(done: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 10 s; asked ${server.seen}" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `16_4_7 right after a start on a slow server settings and the watchlist know it from their first frame`() {
        ServerConfigStore(ctx).run { runBlocking { saveWatchlistKinds(base, true) } }
        config = ConfigAnswer.Held
        val c = started()

        // The first state, before anything was answered.
        assertEquals(ListsByKindSetting(shown = true, reading = true), settings(c).listsByKind.value)
        assertTrue(c.listsByKind.now())
        assertTrue(server.seen.toString(), server.seen.none { it == "GET /api/v1/me/settings" })
    }

    @Test
    fun `16_4_7 after a read of api config that failed the group stays and works`() {
        ServerConfigStore(ctx).run { runBlocking { saveWatchlistKinds(base, true) } }
        config = ConfigAnswer.Fails
        val c = started()
        assertTrue(runBlocking { c.serverFeatures.watchlistKinds() })
        assertTrue(server.seen.toString(), "GET /api/config" in server.seen)

        val s = settings(c)
        assertEquals(true, s.listsByKind.value.shown)
        assertTrue(c.listsByKind.now())
        // Read, then pressed: saved.
        waitFor { !s.listsByKind.value.busy }
        assertEquals(true, s.listsByKind.value.shown)
        s.setListsByKind(false)
        waitFor { "PATCH /api/v1/me/settings" in server.seen && !s.listsByKind.value.busy }
        assertEquals(false, s.listsByKind.value.shown)
    }

    @Test
    fun `16_4_7 a process restored straight into settings shows the group on their first frame`() {
        // The process before: the server answered, and what it said is kept.
        val before = AppContainer(ctx, accounts)
        assertTrue(runBlocking { before.serverFeatures.watchlistKinds() })
        assertEquals(true, ServerConfigStore(ctx).watchlistKindsBlocking(base))

        // Restored: a graph anew over what the TV keeps, the start's hold,
        // then settings first - no home, no warm - while the server is slow.
        config = ConfigAnswer.Held
        val asked = server.seen.size
        val c = started()
        assertEquals(ListsByKindSetting(shown = true, reading = true), settings(c).listsByKind.value)
        assertTrue(c.listsByKind.now())
        assertTrue(server.seen.drop(asked).toString(), server.seen.drop(asked).none { it == "GET /api/v1/me/settings" })
    }

    @Test
    fun `16_4_7 where the server said it doesn't the group stays out from the first frame`() {
        config = ConfigAnswer.OffersNot
        val before = AppContainer(ctx, accounts)
        assertFalse(runBlocking { before.serverFeatures.watchlistKinds() })
        assertEquals(false, ServerConfigStore(ctx).watchlistKindsBlocking(base))

        config = ConfigAnswer.Held
        val c = started()
        assertNull(settings(c).listsByKind.value.shown)
        assertFalse(c.listsByKind.now())
    }

    @Test
    fun `16_4_7 a change of server forgets what the old one said`() {
        val store = ServerConfigStore(ctx)
        runBlocking { store.saveWatchlistKinds(base, true) }
        // The same server's config saved again: kept.
        runBlocking { store.save(ServerConfig(baseUrl = "$base/", issuer = "${server.origin}/realms/other", clientId = "chino-tv")) }
        assertEquals(true, store.watchlistKindsBlocking(base))

        // Another server: gone, for both.
        val other = "http://127.0.0.1:9/api"
        runBlocking { store.save(ServerConfig(baseUrl = "$other/", issuer = "http://127.0.0.1:9/realms/test", clientId = "chino-tv")) }
        assertNull(store.watchlistKindsBlocking(base))
        assertNull(store.watchlistKindsBlocking(other))
        val c = started()
        assertEquals(other, c.baseUrl)
        assertFalse(c.listsByKind.now())
        assertNull(settings(c).listsByKind.value.shown)

        // Change server forgets the server, and what it said with it.
        runBlocking { store.saveWatchlistKinds(other, true) }
        assertEquals(true, store.watchlistKindsBlocking(other))
        runBlocking { store.clear() }
        assertNull(store.watchlistKindsBlocking(other))
    }
}
