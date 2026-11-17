package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.AccountBearers
import cloud.nalet.chino.tv.data.api.RequestAccount
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * An addon that refuses a slot action, also with the renewed token, has
 * refused that action: the press fails, and the person stays signed in.
 */
class SlotActionRefusalTest {
    /** An addon that answers 401 to every action, keeping each bearer. */
    private class Addon {
        val bearers = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val thread = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val conn = runCatching { socket.accept() }.getOrNull() ?: break
                conn.use { c ->
                    val reader = BufferedReader(InputStreamReader(c.getInputStream()))
                    reader.readLine() ?: return@use
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
                    bearers += bearer
                    c.getOutputStream().apply {
                        write("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            }
        }
        val url = "http://127.0.0.1:${socket.localPort}/addons/example/act"

        fun stop() = socket.close()
    }

    private val addon = Addon()

    @After
    fun stop() = addon.stop()

    private val renewed = CopyOnWriteArrayList<String>()
    private val lapsed = CopyOnWriteArrayList<String>()

    private val bearers = object : AccountBearers {
        override fun tokenOf(accountId: String): String = "$accountId-token"
        override fun forceRefreshOf(accountId: String): String {
            renewed += accountId
            return "$accountId-token-renewed"
        }
        override fun refusedAfterRefresh(accountId: String) {
            lapsed += accountId
        }
    }

    @Test
    fun `an addon that refuses an action twice fails the press and sends nobody to sign in`() {
        val actions = SlotActions(RetrofitFactory.actionClient(tokenProvider = { null }, forceRefresh = { null }, accounts = bearers))
        val sent = runBlocking { actions.send(addon.url, RequestAccount("parent")) }
        assertFalse(sent)
        assertEquals(listOf("parent-token", "parent-token-renewed"), addon.bearers.toList())
        assertEquals(listOf("parent"), renewed.toList())
        assertEquals(emptyList<String>(), lapsed.toList())
    }
}
