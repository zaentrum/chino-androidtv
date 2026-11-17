package cloud.nalet.chino.tv.data.auth

import cloud.nalet.chino.tv.data.api.AccountBearers
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The stream token through the client every chino-api call goes through: the
 * bearer is read on the HTTP client's thread, when the request goes out, and
 * the account the token is kept for is read by [StreamTokenManager] (change
 * P.C16 of the specification). Another account chosen between the two reads
 * gets no token minted with the bearer of the account before.
 */
class StreamTokenWiringTest {
    /** A chino-api that mints `token-of-<bearer>-<n>` for the bearer the
     *  request carries: a socket on the loopback, one request a connection. */
    private class Server {
        val bearers = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    var bearer = ""
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
                    bearers += bearer
                    val body = """{"stream_token":"token-of-$bearer-${bearers.size}",""" +
                        """"expires_at":"${Instant.now().plusSeconds(6 * 3600)}"}"""
                    c.getOutputStream().apply {
                        write(
                            ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
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

    private var active = "parent"
    private var changes = 0L

    private fun choose(account: String) {
        if (account != active) changes += 1
        active = account
    }

    /** Switches to [next] the next time the client reads the bearer. */
    private var switchTo: String? = null

    private val api: ChinoApi = RetrofitFactory.create(
        baseUrl = server.baseUrl,
        tokenProvider = {
            switchTo?.let { choose(it); switchTo = null }
            active
        },
        forceRefresh = { null },
        accounts = AccountBearers.None,
    ).create(ChinoApi::class.java)

    private val manager = StreamTokenManager(api) { ActiveChoice(active, changes) }

    @Test
    fun `P_C16 another account chosen while the client reads the bearer gets a token minted with its own`() {
        assertEquals("token-of-parent-1", manager.valid())
        // The parent's token runs out of use: the child is chosen while the
        // client reads the bearer of the next request, which the manager asked
        // for as the parent's... after a clear, so a request goes out.
        kotlinx.coroutines.runBlocking { manager.clear() }
        switchTo = "child"
        assertEquals("token-of-child-3", manager.valid())
        assertEquals(listOf("parent", "child", "child"), server.bearers.toList())
        choose("parent")
        assertEquals("token-of-parent-4", manager.valid())
    }

    @Test
    fun `P_C16 each account's token is minted with that account's bearer`() {
        assertEquals("token-of-parent-1", manager.valid())
        choose("child")
        assertEquals("token-of-child-2", manager.valid())
        assertEquals("token-of-child-2", manager.valid())
        assertEquals(listOf("parent", "child"), server.bearers.toList())
    }
}
