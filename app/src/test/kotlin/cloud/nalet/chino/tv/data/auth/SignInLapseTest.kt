package cloud.nalet.chino.tv.data.auth

import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A 401 is no error screen: the client tries the silent refresh once, and
 * where chino-api refuses the fresh token too, the sign-in lapsed - the app
 * goes to sign-in (SignInLapses). The refresh itself refused is the token
 * manager's lapse; a refresh that worked is none.
 */
class SignInLapseTest {
    /** A chino-api that answers 401 to the tokens in [refused], 200 else: a
     *  socket on the loopback, one request a connection. */
    private class Server(private val refused: Set<String>) {
        val bearers = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    var bearer = ""
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Authorization:", ignoreCase = true)) {
                            bearer = line.substringAfter(':').trim().removePrefix("Bearer ")
                        }
                    }
                    bearers += bearer
                    val status = if (bearer in refused) "401 Unauthorized" else "200 OK"
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".toByteArray())
                        flush()
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${socket.localPort}/api/v1/me"

        fun stop() = socket.close()
    }

    private val servers = mutableListOf<Server>()

    @After
    fun stop() = servers.forEach { it.stop() }

    private fun call(server: Server, fresh: String?, onRefused: () -> Unit): Int {
        val client = RetrofitFactory.httpClient(
            tokenProvider = { "old" },
            forceRefresh = { fresh },
            onRefusedAfterRefresh = onRefused,
        ).build()
        return client.newCall(Request.Builder().url(server.url).build()).execute().use { it.code }
    }

    @Test
    fun `a fresh token chino-api refuses too is a lapse`() {
        val server = Server(refused = setOf("old", "fresh")).also { servers += it }
        var lapses = 0
        assertEquals(401, call(server, fresh = "fresh") { lapses += 1 })
        assertEquals(listOf("old", "fresh"), server.bearers.toList())
        assertEquals(1, lapses)
    }

    @Test
    fun `a refresh that mends the sign-in is no lapse`() {
        val server = Server(refused = setOf("old")).also { servers += it }
        var lapses = 0
        assertEquals(200, call(server, fresh = "fresh") { lapses += 1 })
        assertEquals(0, lapses)
    }

    @Test
    fun `a refresh refused is the token manager's to tell, the client asks no more`() {
        val server = Server(refused = setOf("old")).also { servers += it }
        var lapses = 0
        assertEquals(401, call(server, fresh = null) { lapses += 1 })
        assertEquals(listOf("old"), server.bearers.toList())
        assertEquals(0, lapses)
    }

    @Test
    fun `a lapse reaches the one listening`() = runBlocking {
        val lapses = SignInLapses()
        val heard = launch { assertEquals("account-1", withTimeout(5_000) { lapses.lapses.first() }) }
        // The listener is there before the lapse, as the NavHost is.
        kotlinx.coroutines.yield()
        lapses.lapsed("account-1")
        heard.join()
    }
}
