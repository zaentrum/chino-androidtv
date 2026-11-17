package cloud.nalet.chino.tv.ui.browse

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.AccountBearers
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A title marked watched on Movies goes to the account the page was opened
 * for, through the client every chino-api call goes through, also where
 * another account is active by the time it is sent (change P.C16 of the
 * specification).
 */
@RunWith(RobolectricTestRunner::class)
class BrowseWatchedAccountTest {
    /** A chino-api with one movie, which keeps the path and bearer of every
     *  write it is sent. */
    private class Server {
        val writes = CopyOnWriteArrayList<String>()
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
                    repeat(length) { reader.read() }
                    val body = when {
                        method == "GET" && path.startsWith("/api/v1/items") ->
                            """{"items":[{"id":"m1","title":"Harbour Light","type":"movie"}]}"""
                        method == "GET" && path.startsWith("/api/v1/genres") -> """{"genres":[]}"""
                        method == "POST" && path.startsWith("/api/v1/me/stream-token") ->
                            """{"stream_token":"t","expires_at":"${Instant.now().plusSeconds(3600)}"}"""
                        method == "GET" -> """{"lists":[],"items":[]}"""
                        else -> { writes += "$method $path $bearer"; "" }
                    }
                    val head = if (body.isEmpty()) "HTTP/1.1 204 No Content\r\n" else "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    c.getOutputStream().apply {
                        write("${head}Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
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

    /** The child is active by the time anything is sent. */
    private val api: ChinoApi = RetrofitFactory.create(
        baseUrl = server.baseUrl,
        tokenProvider = { tokens["child"] },
        forceRefresh = { null },
        accounts = object : AccountBearers {
            override fun tokenOf(accountId: String): String? = tokens[accountId]
            override fun forceRefreshOf(accountId: String): String? = null
        },
    ).create(ChinoApi::class.java)

    private fun eventually(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            check(System.currentTimeMillis() < until) { "never got there" }
            Thread.sleep(10)
        }
    }

    private fun page(openedFor: () -> String?): BrowseViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return BrowseViewModel(
            api = api,
            streamTokens = StreamTokenManager(api) { ActiveChoice("child", 1) },
            telemetry = Telemetry(ctx) { null },
            userFlags = UserFlagsRepository(api) { ActiveChoice("child", 1) },
            baseUrl = "https://media.test/api",
            type = "movie",
            openedFor = openedFor,
        )
    }

    @Test
    fun `P_C16 a title marked watched on a page opened for the parent goes to the parent while the child is active`() {
        val vm = page(openedFor = { "parent" })
        eventually { vm.state.value is BrowseUiState.Ready }
        vm.toggleWatched("m1", markWatched = true)
        eventually { server.writes.isNotEmpty() }
        vm.toggleWatched("m1", markWatched = false)
        eventually { server.writes.size == 2 }
        assertEquals(
            listOf("POST /api/v1/me/items/m1/watched parent-token", "DELETE /api/v1/me/items/m1/watched parent-token"),
            server.writes.toList(),
        )
    }

    @Test
    fun `a title marked watched on a page whose account could not be read as it opened goes to the account active then`() {
        var reads = 0
        val vm = page(openedFor = { if (reads++ == 0) throw java.io.IOException("the account store can't be read") else "child" })
        eventually { vm.state.value is BrowseUiState.Ready }
        vm.toggleWatched("m1", markWatched = true)
        eventually { server.writes.isNotEmpty() }
        assertEquals(listOf("POST /api/v1/me/items/m1/watched child-token"), server.writes.toList())
    }
}
