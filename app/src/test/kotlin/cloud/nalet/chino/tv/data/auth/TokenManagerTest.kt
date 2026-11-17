package cloud.nalet.chino.tv.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The bearer of the account a request was made under (change P.C16 of the
 * specification): the token manager over the account store, with the token
 * endpoint of an identity provider on the loopback. A token is renewed for the
 * account asked for, with its own refresh token, whoever is active; an account
 * no longer on the device has none and nothing is asked; a sign-in that
 * lapsed is said only where it is the active account's.
 */
@RunWith(RobolectricTestRunner::class)
class TokenManagerTest {
    /** An identity provider's token endpoint at /token, renewing the refresh
     *  tokens it is not told to refuse, and a chino-api under /api/ that
     *  answers 204 and keeps each request's path and bearer. */
    private class Provider(private val refused: Set<String>) {
        /** The refresh token of each renewal asked for. */
        val renewals = CopyOnWriteArrayList<String>()
        val apiSeen = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    val path = (reader.readLine() ?: return@use).split(' ')[1]
                    var bearer = "-"
                    var length = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            bearer = line.substringAfter(':').trim().removePrefix("Bearer ")
                        }
                        if (line.startsWith("Content-Length:", ignoreCase = true)) {
                            length = line.substringAfter(':').trim().toInt()
                        }
                    }
                    val form = CharArray(length).also { var read = 0; while (read < length) read += reader.read(it, read, length - read) }
                    val (status, body) = if (path == "/token") {
                        val rt = String(form).split('&')
                            .map { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                            .first { it.first == "refresh_token" }.second
                        renewals += rt
                        if (rt in refused) {
                            "400 Bad Request" to """{"error":"invalid_grant"}"""
                        } else {
                            "200 OK" to """{"access_token":"renewed-by-$rt","expires_in":300}"""
                        }
                    } else {
                        apiSeen += "$path $bearer"
                        "204 No Content" to ""
                    }
                    c.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                        )
                        flush()
                    }
                }
            }
        }
        val base = "http://127.0.0.1:${socket.localPort}"

        fun stop() = socket.close()
    }

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("token-manager-test", Context.MODE_PRIVATE)
        .also { it.edit().clear().commit() }
    private val store = AccountStore(prefs)

    private var provider = Provider(refused = emptySet())
    private val lapses = CopyOnWriteArrayList<String>()

    private fun manager() = TokenManager(
        store,
        OidcDeviceClient(
            deviceAuthEndpoint = "${provider.base}/device",
            tokenEndpoint = "${provider.base}/token",
            userinfoEndpoint = "${provider.base}/userinfo",
            clientId = "tv",
        ),
        onLapsed = { lapses += it },
    )

    @After
    fun stop() = provider.stop()

    /** [id] signed in on the device: its token runs out at [expiresAt]. */
    private fun signedIn(id: String, expiresAt: Long = Long.MAX_VALUE, refreshToken: String? = "refresh-of-$id") =
        runBlocking {
            store.addOrUpdate(
                Account(
                    id = id, displayName = id, email = "", accessToken = "access-of-$id", refreshToken = refreshToken,
                    expiresAtEpochMillis = expiresAt, lastUsedAt = 0,
                ),
            )
        }

    private fun choose(id: String) = runBlocking { store.setActive(id) }

    /** The parent's token has run out, the child is active. */
    private fun parentRunOut(refreshToken: String? = "refresh-of-parent") {
        signedIn("parent", expiresAt = 0, refreshToken = refreshToken)
        signedIn("child")
        choose("child")
    }

    @Test
    fun `P_C16 a token run out is renewed for the account asked for, with its own refresh token, while another is active`() {
        parentRunOut()
        val manager = manager()
        assertEquals("renewed-by-refresh-of-parent", manager.tokenOf("parent"))
        assertEquals(listOf("refresh-of-parent"), provider.renewals.toList())
        assertEquals("renewed-by-refresh-of-parent", store.accountBlocking("parent")?.accessToken)
        assertEquals("access-of-child", store.accountBlocking("child")?.accessToken)
        // Renewed now: given as it is, nothing more asked.
        assertEquals("renewed-by-refresh-of-parent", manager.tokenOf("parent"))
        assertEquals("access-of-child", manager.tokenOf("child"))
        assertEquals(1, provider.renewals.size)
        assertEquals(emptyList<String>(), lapses.toList())
    }

    @Test
    fun `P_C16 after a 401 the account's own token is renewed, though it has not run out`() {
        signedIn("parent")
        signedIn("child")
        choose("child")
        assertEquals("renewed-by-refresh-of-parent", manager().forceRefreshOf("parent"))
        assertEquals(listOf("refresh-of-parent"), provider.renewals.toList())
        assertEquals("access-of-child", store.accountBlocking("child")?.accessToken)
    }

    @Test
    fun `P_C16 an account removed from the device has no token, nothing is asked and no lapse is said`() {
        signedIn("child")
        choose("child")
        val manager = manager()
        assertNull(manager.tokenOf("removed"))
        assertNull(manager.forceRefreshOf("removed"))
        assertEquals(emptyList<String>(), provider.renewals.toList())
        assertEquals(emptyList<String>(), lapses.toList())
    }

    @Test
    fun `P_C16 a renewal the provider refuses is a lapse only where the account is the active one`() {
        provider.stop()
        provider = Provider(refused = setOf("refresh-of-parent"))
        parentRunOut()
        val manager = manager()
        assertNull(manager.tokenOf("parent"))
        assertNull(manager.forceRefreshOf("parent"))
        manager.refusedAfterRefresh("parent")
        assertEquals(2, provider.renewals.size)
        assertEquals(emptyList<String>(), lapses.toList())

        choose("parent")
        assertNull(manager.tokenOf("parent"))
        assertEquals(listOf("parent"), lapses.toList())
        assertNull(manager.forceRefreshOf("parent"))
        manager.refusedAfterRefresh("parent")
        assertEquals(listOf("parent", "parent", "parent"), lapses.toList())
    }

    @Test
    fun `no refresh token is a lapse only where the account is the active one, and nothing is asked`() {
        parentRunOut(refreshToken = null)
        val manager = manager()
        assertNull(manager.tokenOf("parent"))
        assertEquals(emptyList<String>(), lapses.toList())
        choose("parent")
        assertNull(manager.tokenOf("parent"))
        assertEquals(listOf("parent"), lapses.toList())
        assertEquals(emptyList<String>(), provider.renewals.toList())
    }

    @Test
    fun `P_C16 through the client the app builds, a like made as the parent goes out with the parent's renewed token while the child is active`() {
        parentRunOut()
        val manager = manager()
        val api = RetrofitFactory.create(
            baseUrl = "${provider.base}/api/",
            tokenProvider = manager::validAccessTokenBlocking,
            forceRefresh = manager::forceRefreshBlocking,
            accounts = manager,
        ).create(ChinoApi::class.java)
        runBlocking {
            api.addLike("title-1", RequestAccount("parent"))
            api.deleteWatchlist("list-1")
        }
        assertEquals(
            listOf("/api/v1/me/likes/title-1 renewed-by-refresh-of-parent", "/api/v1/me/watchlists/list-1 access-of-child"),
            provider.apiSeen.toList(),
        )
    }
}
