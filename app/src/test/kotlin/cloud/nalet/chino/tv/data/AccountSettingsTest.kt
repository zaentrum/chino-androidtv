package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.AccountBearers
import cloud.nalet.chino.tv.data.api.AccountSettingsChange
import cloud.nalet.chino.tv.data.api.AccountSettingsResponse
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.LoadFailure
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Separate movies and shows is kept by the account on the server (change
 * 16.C25 of the specification) and read and saved through
 * /api/v1/me/settings alone, through the client every chino-api call goes
 * through. What is read for one account is shown for that account alone
 * (change P.C16): another account chosen takes it away at once, and a save
 * goes out with the bearer of the account whose setting the switch showed.
 * While it is read, and where that read failed, the choice last known on this
 * device for the account is the one held (section 16.4.7).
 */
class AccountSettingsTest {
    /** A chino-api that keeps each account's setting by its bearer, and every
     *  request's method, path, bearer and body. */
    private class Server {
        val seen = CopyOnWriteArrayList<String>()
        val kept = ConcurrentHashMap(mapOf("parent-token" to true, "child-token" to false))

        /** A refusal the next PATCH is answered with, as chino-api writes it. */
        @Volatile var refusal: String? = null

        /** Runs as a GET comes in, before it is answered. */
        @Volatile var onRead: () -> Unit = {}

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    val (method, path) = (reader.readLine() ?: return@use).split(' ').let { it[0] to it[1] }
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
                    val body = CharArray(length).also { reader.read(it, 0, length) }.concatToString()
                    seen += listOf(method, path, bearer, body).filter { it.isNotEmpty() }.joinToString(" ")
                    var status = "200 OK"
                    val answer = when (method) {
                        "GET" -> {
                            onRead()
                            """{"watchlist_by_kind":${kept[bearer] == true}}"""
                        }
                        else -> refusal?.let { status = "400 Bad Request"; refusal = null; it } ?: run {
                            kept[bearer] = body.contains("true")
                            """{"watchlist_by_kind":${kept[bearer] == true}}"""
                        }
                    }
                    c.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${answer.toByteArray().size}\r\nConnection: close\r\n\r\n$answer").toByteArray(),
                        )
                        flush()
                    }
                }
            }
        }
        val baseUrl = "http://127.0.0.1:${socket.localPort}/api/"

        fun stop() = socket.close()
    }

    private val server = Server()

    @After
    fun stop() = server.stop()

    private val tokens = mapOf("parent" to "parent-token", "child" to "child-token")
    @Volatile private var active: String? = "parent"
    @Volatile private var changes = 0L

    /** Another account chosen on who's watching. */
    private fun choose(account: String) {
        changes += 1
        active = account
    }

    private val api: ChinoApi = RetrofitFactory.create(
        baseUrl = server.baseUrl,
        tokenProvider = { tokens[active] },
        forceRefresh = { null },
        accounts = object : AccountBearers {
            override fun tokenOf(accountId: String): String? = tokens[accountId]
            override fun forceRefreshOf(accountId: String): String? = null
        },
    ).create(ChinoApi::class.java)

    private val job = SupervisorJob()
    private val lastKnown = LastKnownInMemory()
    private val settings = AccountSettings(
        api,
        CoroutineScope(job + Dispatchers.IO),
        activeChanges = { changes },
        lastKnown = lastKnown,
    ) { ActiveChoice(active, changes) }

    private fun read(fresh: Boolean = false) = runBlocking { settings.watchlistByKind(fresh) }

    private fun settle() = runBlocking { job.children.toList().joinAll() }

    @Test
    fun `16_C25 the setting shown is the active account's, read with its bearer`() {
        assertEquals(true, read())
        assertEquals(true, settings.watchlistByKind.value)
        // Held: asked once.
        assertEquals(true, read())
        assertEquals(listOf("GET /api/v1/me/settings parent-token"), server.seen.toList())
    }

    @Test
    fun `P_C16 another account chosen - the setting before is gone at once, and the next read is the new account's`() {
        read()
        choose("child")
        settings.activeIs("child")
        assertNull(settings.watchlistByKind.value)
        settle()
        assertEquals(false, read())
        assertEquals(false, settings.watchlistByKind.value)
        assertEquals("GET /api/v1/me/settings child-token", server.seen.last())
    }

    @Test
    fun `P_C16 a read answered after another account was chosen is not shown`() {
        server.onRead = { choose("child"); server.onRead = {} }
        assertNull(read())
        assertNull(settings.watchlistByKind.value)
        assertEquals(false, read())
    }

    @Test
    fun `16_C25 a save sends the one field and shows what the server kept`() {
        read()
        val under = assertNotNullAnd(settings.pressedUnder())
        assertNull(runBlocking { settings.saveWatchlistByKind(false, under).await() })
        assertEquals(false, settings.watchlistByKind.value)
        assertEquals("PATCH /api/v1/me/settings parent-token {\"watchlist_by_kind\":false}", server.seen.last())
        assertEquals(false, server.kept["parent-token"])
    }

    @Test
    fun `P_C16 a save goes out under the account whose setting was shown, while another is active`() {
        read()
        val under = assertNotNullAnd(settings.pressedUnder())
        // The child is chosen before the save is sent.
        choose("child")
        assertNull(runBlocking { settings.saveWatchlistByKind(false, under).await() })
        assertEquals("PATCH /api/v1/me/settings parent-token {\"watchlist_by_kind\":false}", server.seen.last())
        assertEquals(false, server.kept["parent-token"])
        assertEquals(false, server.kept["child-token"])
        // The parent's choice is never shown as the child's.
        settings.activeIs("child")
        assertNull(settings.watchlistByKind.value)
    }

    @Test
    fun `P_C16 a press after another account is chosen sends nothing`() {
        read()
        choose("child")
        assertNull(settings.pressedUnder())
    }

    @Test
    fun `16_C25 a refused save changes nothing shown and says why by its status, not by the server's code`() {
        read()
        server.refusal = """{"error":"watchlist_by_kind_invalid","detail":"watchlist_by_kind takes true, false or null"}"""
        val failure = runBlocking { settings.saveWatchlistByKind(false, settings.pressedUnder()!!).await() }
        assertEquals(LoadFailure.Kind.Rejected, failure?.kind)
        assertTrue(failure.toString(), failure?.reason?.contains("watchlist_by_kind") == false)
        assertEquals(true, settings.watchlistByKind.value)
        assertEquals(true, server.kept["parent-token"])
    }

    @Test
    fun `16_C25 read again, it shows what was changed on another device, and a read again that fails keeps the one held`() {
        read()
        server.kept["parent-token"] = false
        assertEquals(false, read(fresh = true))
        assertEquals(false, settings.watchlistByKind.value)
        server.stop()
        assertEquals(false, read(fresh = true))
        assertEquals(false, settings.watchlistByKind.value)
    }

    @Test
    fun `16_4_7 while it is read the choice last known on this device is held, and a press is not taken`() {
        lastKnown.keep("parent", false)
        var during: Triple<Boolean?, Boolean, ActiveChoice?>? = null
        server.onRead = { during = Triple(settings.watchlistByKind.value, settings.reading.value, settings.pressedUnder()) }
        assertEquals(true, read())
        assertEquals(Triple(false, true, null), during)
        assertEquals(false, settings.reading.value)
        assertNotNull(settings.pressedUnder())
    }

    @Test
    fun `16_4_7 a read that failed holds the choice last known on this device, and a press is taken`() {
        lastKnown.keep("parent", true)
        server.stop()
        assertEquals(true, read())
        assertEquals(true, settings.watchlistByKind.value)
        assertEquals(false, settings.reading.value)
        assertEquals(ActiveChoice("parent", 0), settings.pressedUnder())
    }

    @Test
    fun `16_4_7 a read that failed with nothing known on this device holds off`() {
        server.stop()
        assertEquals(false, read())
        assertEquals(false, settings.watchlistByKind.value)
    }

    @Test
    fun `16_4_7 what is read or saved is kept on this device as that account's, each account its own`() {
        read()
        assertEquals(true, lastKnown.of("parent"))
        assertNull(lastKnown.of("child"))
        runBlocking { settings.saveWatchlistByKind(false, settings.pressedUnder()!!).await() }
        assertEquals(false, lastKnown.of("parent"))
        // The child's last known is its own, never the parent's.
        choose("child")
        settings.activeIs("child")
        settle()
        server.stop()
        assertEquals(false, read())
        assertNull(lastKnown.of("child"))
    }

    /** A chino-api whose settings' reads and saves wait until the test
     *  answers them, each as it was asked: the account, and a save's value. */
    private class HeldServer {
        val reads = LinkedBlockingQueue<Pair<Continuation<Any?>, String>>()
        val saves = LinkedBlockingQueue<Pair<Continuation<Any?>, Pair<String, Boolean>>>()

        @Suppress("UNCHECKED_CAST")
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "accountSettings" -> {
                    reads.put((args!!.last() as Continuation<Any?>) to (args[0] as RequestAccount).accountId)
                    COROUTINE_SUSPENDED
                }
                "changeAccountSettings" -> {
                    val change = (args!![1] as RequestAccount).accountId to (args[0] as AccountSettingsChange).watchlistByKind
                    saves.put((args.last() as Continuation<Any?>) to change)
                    COROUTINE_SUSPENDED
                }
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the held server"
                else -> throw IllegalStateException("not asked here")
            }
        } as ChinoApi

        fun nextRead() = checkNotNull(reads.poll(5, TimeUnit.SECONDS)) { "no read asked in 5 s" }
        fun nextSave() = checkNotNull(saves.poll(5, TimeUnit.SECONDS)) { "no save asked in 5 s" }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun heldSettings(held: HeldServer) =
        AccountSettings(held.api, CoroutineScope(job + Dispatchers.IO), activeChanges = { changes }, lastKnown = lastKnown) {
            ActiveChoice(active, changes)
        }

    private fun answer(request: Pair<Continuation<Any?>, *>, on: Boolean) =
        request.first.resumeWith(Result.success(AccountSettingsResponse(on)))

    private fun waitUntil(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!done()) {
            check(System.nanoTime() < end) { "not done in 5 s" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `16_4_7 the next account's choice is held at once, while a read for the account before is still out`() {
        val held = HeldServer()
        val settings = heldSettings(held)
        lastKnown.keep("child", true)
        val parents = scope.async { settings.watchlistByKind(fresh = true) }
        val parentRead = held.nextRead()
        choose("child")
        settings.activeIs("child")
        assertNull(settings.watchlistByKind.value)
        // Home's warm for the child: its last known is held before the
        // parent's read has let go, and not as being read.
        val childs = scope.async { settings.watchlistByKind(fresh = false) }
        waitUntil { settings.watchlistByKind.value == true }
        assertFalse(settings.reading.value)
        assertTrue(held.reads.isEmpty())
        // The parent's answer is not shown; the child's read comes next.
        answer(parentRead, true)
        assertNull(runBlocking { parents.await() })
        val childRead = held.nextRead()
        assertEquals("child", childRead.second)
        assertTrue(settings.reading.value)
        answer(childRead, false)
        assertEquals(false, runBlocking { childs.await() })
        assertEquals(false, settings.watchlistByKind.value)
    }

    @Test
    fun `16_4_7 a read answered with the choice before a save, let go after the save's answer, leaves the save's choice shown and last known`() {
        val held = HeldServer()
        val settings = heldSettings(held)
        val reading = scope.async { settings.watchlistByKind(fresh = true) }
        val read = held.nextRead()
        val save = settings.saveWatchlistByKind(false, ActiveChoice("parent", changes))
        answer(held.nextSave(), false)
        // The save's answer waits for the read out: nothing is kept yet.
        Thread.sleep(200)
        assertNull(lastKnown.of("parent"))
        answer(read, true)
        runBlocking {
            reading.await()
            assertNull(save.await())
        }
        assertEquals(false, settings.watchlistByKind.value)
        assertEquals(false, lastKnown.of("parent"))
    }

    @Test
    fun `16_4_7 a save runs on the account settings' own scope - the caller gone, it is still kept`() {
        val held = HeldServer()
        val settings = heldSettings(held)
        val caller = CoroutineScope(Job() + Dispatchers.IO)
        caller.launch { settings.saveWatchlistByKind(false, ActiveChoice("parent", changes)).await() }
        val save = held.nextSave()
        caller.cancel()
        answer(save, false)
        waitUntil { lastKnown.of("parent") == false }
    }

    @Test
    fun `16_4_7 an account forgotten loses its last known and what is held for it, the others keep theirs`() {
        read()
        lastKnown.keep("child", false)
        settings.forget("parent")
        assertNull(lastKnown.of("parent"))
        assertNull(settings.watchlistByKind.value)
        assertNull(settings.pressedUnder())
        assertEquals(false, lastKnown.of("child"))
    }

    @Test
    fun `16_4_7 a save or a read still out when its account is forgotten keeps nothing for it`() {
        val held = HeldServer()
        val settings = heldSettings(held)
        val reading = scope.async { settings.watchlistByKind(fresh = true) }
        val read = held.nextRead()
        val save = settings.saveWatchlistByKind(true, ActiveChoice("parent", changes))
        val saving = held.nextSave()
        settings.forget("parent")
        answer(saving, true)
        answer(read, true)
        runBlocking {
            reading.await()
            save.await()
        }
        assertNull(lastKnown.of("parent"))
    }

    private fun <T> assertNotNullAnd(value: T?): T {
        assertNotNull(value)
        return value!!
    }
}
