package cloud.nalet.chino.tv.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.api.AccountSignedOutException
import cloud.nalet.chino.tv.data.api.ProgressBody
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.auth.Account
import cloud.nalet.chino.tv.data.auth.AccountStore
import cloud.nalet.chino.tv.ui.settings.DeleteAccountState
import cloud.nalet.chino.tv.ui.settings.DeleteAccountViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An account the server has deleted is signed out on this TV (section 16.5.6
 * of the specification): chino-api answers what it sends 401
 * `account_deleted`, and the container's own client - its interceptor,
 * authenticator and token manager, against a chino-api and a sign-in service
 * of the test's on the loopback - asks for no fresh token, sends nothing
 * again, and signs that account out, once, for the toast. Another account
 * stays, and is not made active: what the deleted account's screens still
 * send goes out under it, or not at all. A write that waited out a deletion
 * still open, 503 `account busy`, signs nobody out; so does an addon's action
 * answered deleted, and a 401 with another code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AccountDeletedTest {
    /** One answer: a status, its content type and body, and headers. */
    private data class Answer(
        val status: Int,
        val body: String = "",
        val type: String = "application/json",
        val headers: Map<String, String> = emptyMap(),
    )

    /** chino-api and the sign-in service on the loopback, each request on a
     *  thread of its own: each is answered by [answer] from its method, path
     *  and bearer ("-" for none), and noted in [seen] as "METHOD path bearer",
     *  its body in [bodies] as "METHOD path body". */
    private class Server(private val answer: (method: String, path: String, bearer: String) -> Answer) {
        val seen = CopyOnWriteArrayList<String>()
        val bodies = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val origin = "http://127.0.0.1:${socket.localPort}"

        init {
            thread(isDaemon = true) {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { conn.use { serve(it) } }
                }
            }
        }

        private fun serve(c: Socket) {
            val reader = BufferedReader(InputStreamReader(c.getInputStream()))
            val (method, path) = (reader.readLine() ?: return).split(' ').let { it[0] to it[1] }
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
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val n = reader.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            seen += "$method $path $bearer"
            bodies += "$method $path ${String(body, 0, read)}"
            val a = answer(method, path, bearer)
            val head = buildString {
                append("HTTP/1.1 ${a.status} X\r\nContent-Type: ${a.type}\r\n")
                a.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                append("Content-Length: ${a.body.toByteArray().size}\r\nConnection: close\r\n\r\n")
            }
            c.getOutputStream().apply {
                write((head + a.body).toByteArray())
                flush()
            }
        }

        fun stop() = socket.close()
    }

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    /** The bearers chino-api answers deleted, and those it answers busy. */
    private val deleted = mutableSetOf("parent-token")
    private val busy = mutableSetOf<String>()

    /** Where set, each like is answered only once this many like requests
     *  have come in (or after 5 s). */
    @Volatile private var likesHeld: CountDownLatch? = null

    private val server = Server { method, path, bearer ->
        if ("/me/likes/" in path) likesHeld?.run { countDown(); await(5, TimeUnit.SECONDS) }
        when {
            path.endsWith("/protocol/openid-connect/token") ->
                Answer(200, """{"access_token":"renewed-token","refresh_token":"renewed-refresh","expires_in":300}""")
            bearer in deleted ->
                Answer(401, """{"error":"account_deleted","detail":"the account's deletion has begun: nothing more is kept for it"}""")
            bearer in busy ->
                Answer(503, "account busy", type = "text/plain", headers = mapOf("Retry-After" to "5"))
            bearer == "stale-token" -> Answer(401, "invalid token", type = "text/plain")
            bearer == "expired-token" -> Answer(401, """{"error":"token_expired","detail":"the token has expired"}""")
            "$method $path" == "POST /api/v1/me/stream-token" -> Answer(200, """{"stream_token":"st"}""")
            "$method $path" == "GET /api/v1/me/watchlists" -> Answer(200, """{"lists":[]}""")
            "$method $path" == "POST /api/v1/me/watchlists" -> Answer(200, """{"id":"list-2","name":"first list"}""")
            "$method $path" == "DELETE /api/v1/me" -> Answer(200, """{"account":"deleted","deleted":{}}""")
            else -> Answer(204)
        }
    }

    private val store = AccountStore(
        ctx.getSharedPreferences("account-deleted-test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    )

    private val container: AppContainer

    /** What the container said it signed out, deleted, in order. */
    private val signedOut = CopyOnWriteArrayList<String>()
    private val listening = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        runBlocking {
            ServerConfigStore(ctx).save(
                ServerConfig(baseUrl = "${server.origin}/api/", issuer = "${server.origin}/realms/test", clientId = "chino-tv"),
            )
            store.addOrUpdate(account("parent"))
            store.addOrUpdate(account("child"))
        }
        container = AppContainer(ctx, store)
        // The listener is collecting before any test sends.
        val listens = CompletableDeferred<Unit>()
        listening.launch {
            container.deletedAccounts.signedOut.onSubscription { listens.complete(Unit) }.collect { signedOut += it }
        }
        runBlocking { withTimeout(5_000) { listens.await() } }
    }

    @After
    fun stop() {
        listening.cancel()
        server.stop()
    }

    private fun account(id: String, token: String = "$id-token") = Account(
        id = id, displayName = id, email = "", accessToken = token, refreshToken = "$id-refresh",
        expiresAtEpochMillis = Long.MAX_VALUE, lastUsedAt = 0,
    )

    private fun signedIn() = store.snapshotBlocking().accounts.map { it.id }.sorted()

    private val active get() = store.snapshotBlocking().activeAccount?.id

    private fun waitFor(what: String, done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not $what in 5 s; signed in ${signedIn()}, asked ${server.seen}" }
            Thread.sleep(10)
        }
    }

    private val refreshes get() = server.seen.filter { "openid-connect/token" in it }

    /** The parent's like answered deleted, and the parent signed out. */
    private fun parentDeleted() {
        runCatching { runBlocking { container.chinoApi.addLike("title-1", RequestAccount("parent")) } }
        waitFor("signed out") { signedIn() == listOf("child") }
    }

    @Test
    fun `16_5_6 a write answered account_deleted asks for no fresh token and is not sent again`() {
        runBlocking { store.setActive("child") }
        val e = assertThrows(HttpException::class.java) {
            runBlocking { container.chinoApi.addLike("title-1", RequestAccount("parent")) }
        }
        assertEquals(401, e.code())
        assertEquals(listOf("PUT /api/v1/me/likes/title-1 parent-token"), server.seen.toList())
        assertEquals(emptyList<String>(), refreshes)
    }

    @Test
    fun `16_5_6 it signs that account out on this TV, says so once, and the account active stays`() {
        runBlocking { store.setActive("child") }
        parentDeleted()
        waitFor("said") { signedOut.isNotEmpty() }
        assertEquals(listOf("parent"), signedOut.toList())
        assertEquals("child", active)
        // What was still to go out under it is not sent; the other account's is.
        assertThrows(AccountSignedOutException::class.java) {
            runBlocking { container.chinoApi.addLike("title-2", RequestAccount("parent")) }
        }
        runBlocking { container.chinoApi.addLike("title-2", RequestAccount("child")) }
        assertEquals(
            listOf("PUT /api/v1/me/likes/title-1 parent-token", "PUT /api/v1/me/likes/title-2 child-token"),
            server.seen.toList(),
        )
        Thread.sleep(100)
        assertEquals(listOf("parent"), signedOut.toList())
    }

    @Test
    fun `16_5_6 a request that carries no account signs out the account whose bearer it went out with, and nobody is active`() {
        runBlocking { store.setActive("parent") }
        val e = assertThrows(HttpException::class.java) {
            runBlocking { container.chinoApi.getWatchlists() }
        }
        assertEquals(401, e.code())
        waitFor("signed out") { signedIn() == listOf("child") }
        waitFor("said") { signedOut.isNotEmpty() }
        assertEquals(listOf("parent"), signedOut.toList())
        // The account left is not made active: who's watching follows.
        assertNull(active)
        assertEquals(listOf("GET /api/v1/me/watchlists parent-token"), server.seen.toList())
        assertEquals(emptyList<String>(), refreshes)
    }

    @Test
    fun `16_5_6 the active account deleted - what its screen still presses goes out as nobody's, never as the other account's`() {
        // Its lists and likes loaded before the deletion.
        deleted.clear()
        runBlocking {
            store.setActive("parent")
            container.userFlags.warm()
        }
        deleted += "parent-token"
        server.seen.clear()
        // Its like, answered deleted: it is signed out, and nobody is active.
        container.userFlags.setLike("title-1", true)
        waitFor("signed out") { signedIn() == listOf("child") }
        waitFor("said") { signedOut.isNotEmpty() }
        assertNull(active)
        // A second OK on its screen, still up, its bookmark, and a list made
        // there: none goes out as the child's.
        container.userFlags.setLike("title-2", true)
        container.userFlags.setWatchlist("title-2", true)
        assertNull(runBlocking { container.userFlags.createList("first list") })
        Thread.sleep(300)
        assertEquals(listOf("PUT /api/v1/me/likes/title-1 parent-token"), server.seen.toList())
        assertEquals(listOf("child"), signedIn())
    }

    @Test
    fun `16_5_6 what a player queued under the deleted account is not sent as another's, and each account's goes under its own`() {
        runBlocking { store.setActive("parent") }
        // Quality reports queued by a player of each account.
        container.telemetry.queue("bandwidth", "title-1", mapOf("bps" to "1")) { RequestAccount("parent") }
        container.telemetry.queue("bandwidth", "title-2", mapOf("bps" to "2")) { RequestAccount("child") }
        // The parent's player's save answered deleted: the parent is signed
        // out; the child is chosen on who's watching, then the queue goes.
        runCatching {
            runBlocking {
                container.chinoApi.postProgress("title-1", ProgressBody(positionSec = 60, durationSec = 600), RequestAccount("parent"))
            }
        }
        waitFor("signed out") { signedIn() == listOf("child") }
        assertNull(active)
        runBlocking { store.setActive("child") }
        container.telemetry.flush()
        waitFor("the child's reports sent") { server.seen.any { "/play/events" in it } }
        Thread.sleep(200)
        assertEquals(
            listOf("POST /api/v1/items/title-1/progress parent-token", "POST /api/v1/play/events child-token"),
            server.seen.toList(),
        )
        val batch = server.bodies.single { it.startsWith("POST /api/v1/play/events ") }
        assertTrue(batch, "title-2" in batch)
        assertFalse(batch, "title-1" in batch)
    }

    @Test
    fun `16_5_6 delete account goes out under the account on screen, whoever is active by then`() {
        deleted.clear()
        runBlocking { store.setActive("parent") }
        val vm = DeleteAccountViewModel.factory(container).create(DeleteAccountViewModel::class.java)
        runBlocking { store.setActive("child") }
        vm.delete()
        waitFor("deleted") { vm.state.value is DeleteAccountState.SignedOut }
        assertEquals(listOf("DELETE /api/v1/me parent-token"), server.seen.toList())
        assertEquals(listOf("child"), signedIn())
        assertEquals("child", active)
    }

    @Test
    fun `16_5_6 delete account for an account signed out before it is sent sends nothing`() {
        runBlocking { store.setActive("parent") }
        val vm = DeleteAccountViewModel.factory(container).create(DeleteAccountViewModel::class.java)
        parentDeleted()
        runBlocking { store.setActive("child") }
        vm.delete()
        waitFor("answered") { vm.state.value is DeleteAccountState.NotDeleted }
        assertEquals(listOf("PUT /api/v1/me/likes/title-1 parent-token"), server.seen.toList())
        assertEquals("child", active)
    }

    @Test
    fun `16_5_6 two answers for the same account sign it out once`() {
        runBlocking { store.setActive("child") }
        // Neither like is answered before both have gone out under the parent.
        likesHeld = CountDownLatch(2)
        val results = runBlocking {
            listOf(
                async(Dispatchers.IO) { runCatching { container.chinoApi.addLike("title-1", RequestAccount("parent")) } },
                async(Dispatchers.IO) { runCatching { container.chinoApi.removeLike("title-2", RequestAccount("parent")) } },
            ).awaitAll()
        }
        assertEquals(
            listOf("DELETE /api/v1/me/likes/title-2 parent-token", "PUT /api/v1/me/likes/title-1 parent-token"),
            server.seen.sorted(),
        )
        assertEquals(listOf(401, 401), results.map { (it.exceptionOrNull() as? HttpException)?.code() })
        waitFor("signed out") { signedIn() == listOf("child") }
        Thread.sleep(200)
        assertEquals(listOf("parent"), signedOut.toList())
    }

    @Test
    fun `16_5_6 a write that waited out an open deletion, 503 account busy, signs nobody out`() {
        deleted.clear()
        busy += "parent-token"
        runBlocking { store.setActive("parent") }
        val e = assertThrows(HttpException::class.java) {
            runBlocking { container.chinoApi.addLike("title-1", RequestAccount("parent")) }
        }
        assertEquals(503, e.code())
        assertEquals("5", e.response()?.headers()?.get("Retry-After"))
        Thread.sleep(200)
        assertEquals(listOf("child", "parent"), signedIn())
        assertEquals("parent", active)
        assertTrue(signedOut.isEmpty())
        assertEquals(emptyList<String>(), refreshes)
    }

    @Test
    fun `16_5_6 an addon's action answered account_deleted signs nobody out`() {
        runBlocking { store.setActive("parent") }
        val took = runBlocking { container.slotActions.send("${server.origin}/addons/first/act", RequestAccount("parent")) }
        assertFalse(took)
        Thread.sleep(200)
        assertEquals(listOf("child", "parent"), signedIn())
        assertEquals("parent", active)
        assertTrue(signedOut.isEmpty())
        // Not sent again, and no fresh token asked for.
        assertEquals(listOf("POST /addons/first/act parent-token"), server.seen.toList())
    }

    @Test
    fun `a 401 without account_deleted is renewed once and sent again, as before`() {
        runBlocking {
            store.addOrUpdate(account("child", token = "stale-token"))
            store.setActive("child")
            container.chinoApi.addLike("title-1", RequestAccount("child"))
        }
        assertEquals(
            listOf(
                "PUT /api/v1/me/likes/title-1 stale-token",
                "POST /realms/test/protocol/openid-connect/token -",
                "PUT /api/v1/me/likes/title-1 renewed-token",
            ),
            server.seen.toList(),
        )
        assertEquals(listOf("child", "parent"), signedIn())
        assertTrue(signedOut.isEmpty())
    }

    @Test
    fun `a JSON 401 with another code is renewed once and sent again, and signs nobody out`() {
        runBlocking {
            store.addOrUpdate(account("child", token = "expired-token"))
            store.setActive("child")
            container.chinoApi.addLike("title-1", RequestAccount("child"))
        }
        assertEquals(
            listOf(
                "PUT /api/v1/me/likes/title-1 expired-token",
                "POST /realms/test/protocol/openid-connect/token -",
                "PUT /api/v1/me/likes/title-1 renewed-token",
            ),
            server.seen.toList(),
        )
        Thread.sleep(100)
        assertEquals(listOf("child", "parent"), signedIn())
        assertEquals("child", active)
        assertTrue(signedOut.isEmpty())
    }
}
