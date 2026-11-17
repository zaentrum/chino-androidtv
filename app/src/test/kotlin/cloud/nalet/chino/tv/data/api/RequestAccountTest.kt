package cloud.nalet.chino.tv.data.api

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A write made under an account goes out with that account's bearer, through
 * the client every chino-api call goes through, whoever is active when it is
 * sent (change P.C16 of the specification): a press lands on the lists, likes
 * or progress of the account it was made on, never on another's.
 */
class RequestAccountTest {
    /** A chino-api that answers 401 to the bearers in [refused] and 204 to
     *  every other, and keeps each request's path and bearer. */
    private class Server(private val refused: Set<String>) {
        val seen = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    val requestLine = reader.readLine() ?: return@use
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
                    repeat(length) { reader.read() }
                    seen += "${requestLine.split(' ')[1]} $bearer"
                    val status = if (bearer in refused) "401 Unauthorized" else "204 No Content"
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
        }
        val baseUrl = "http://127.0.0.1:${socket.localPort}/api/"

        fun stop() = socket.close()
    }

    /** The accounts signed in, by id, with their access tokens. */
    private val tokens = mutableMapOf("parent" to "parent-token", "child" to "child-token")
    private var active = "child"
    private val refreshed = CopyOnWriteArrayList<String>()

    private val bearers = object : AccountBearers {
        override fun tokenOf(accountId: String): String? = tokens[accountId]
        override fun forceRefreshOf(accountId: String): String? {
            refreshed += accountId
            return tokens[accountId]?.let { "$it-renewed" }?.also { tokens[accountId] = it }
        }
    }

    private var server = Server(refused = emptySet())

    private fun api(): ChinoApi = RetrofitFactory.create(
        baseUrl = server.baseUrl,
        tokenProvider = { tokens[active] },
        forceRefresh = { tokens[active]?.let { "$it-renewed" }?.also { tokens[active] = it } },
        accounts = bearers,
    ).create(ChinoApi::class.java)

    @After
    fun stop() = server.stop()

    @Test
    fun `P_C16 a like made as the parent goes out with the parent's bearer while the child is active`() {
        runBlocking { api().addLike("title-1", RequestAccount("parent")) }
        assertEquals(listOf("/api/v1/me/likes/title-1 parent-token"), server.seen.toList())
    }

    @Test
    fun `P_C16 each write of the lists, the player's saves and the notices goes out under the account it was made under`() {
        val api = api()
        runBlocking {
            api.addToWatchlist("title-1", RequestAccount("parent"))
            api.removeFromWatchlist("title-1", RequestAccount("parent"))
            api.addItemToWatchlist("list-1", "title-1", RequestAccount("parent"))
            api.removeItemFromWatchlist("list-1", "title-1", RequestAccount("parent"))
            api.removeLike("title-1", RequestAccount("parent"))
            api.postProgress("title-1", ProgressBody(positionSec = 60, durationSec = 600), RequestAccount("parent"))
            api.postWatched("title-1", RequestAccount("parent"))
            api.deleteWatched("title-1", RequestAccount("parent"))
            api.readNotice("00000001-0000-4000-8000-000000000001", RequestAccount("parent"))
            api.readAllNotices(RequestAccount("parent"))
            api.deleteNotice("00000001-0000-4000-8000-000000000001", RequestAccount("parent"))
        }
        assertEquals(11, server.seen.size)
        assertTrue(server.seen.toString(), server.seen.all { it.endsWith(" parent-token") })
    }

    @Test
    fun `a request that carries no account goes out with the active account's bearer`() {
        runBlocking { api().deleteWatchlist("list-1") }
        assertEquals(listOf("/api/v1/me/watchlists/list-1 child-token"), server.seen.toList())
    }

    @Test
    fun `P_C16 a write made under an account no longer signed in is not sent at all`() {
        tokens.remove("parent")
        assertThrows(AccountSignedOutException::class.java) {
            runBlocking { api().addLike("title-1", RequestAccount("parent")) }
        }
        assertEquals(emptyList<String>(), server.seen.toList())
    }

    @Test
    fun `P_C16 a write refused with the parent's token is sent again with the parent's renewed one, not the active account's`() {
        server.stop()
        server = Server(refused = setOf("parent-token"))
        runBlocking { api().addLike("title-1", RequestAccount("parent")) }
        assertEquals(listOf("parent"), refreshed.toList())
        assertEquals(
            listOf("/api/v1/me/likes/title-1 parent-token", "/api/v1/me/likes/title-1 parent-token-renewed"),
            server.seen.toList(),
        )
    }
}
