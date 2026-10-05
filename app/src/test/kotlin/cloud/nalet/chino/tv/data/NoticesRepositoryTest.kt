package cloud.nalet.chino.tv.data

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The signed-in person's notices against a fake chino-api: the app's own
 * client (RetrofitFactory — its bearer, its JSON) with an interceptor that
 * answers in place of the network, as chino-api's notices.go answers.
 */
class NoticesRepositoryTest {
    private class FakeChino {
        var list = LIST
        var changeStatus = 200
        var unreachable = false
        val requests = mutableListOf<Request>()

        val api: ChinoApi = RetrofitFactory.retrofit(
            BASE_URL,
            RetrofitFactory.httpClient(tokenProvider = { "tok-1" }, forceRefresh = { null })
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    if (unreachable) throw IOException("connect timed out")
                    val path = request.url.encodedPath
                    val (status, body) = when {
                        request.method == "GET" && path == "/api/v1/notices" -> 200 to list
                        request.method == "POST" && path == "/api/v1/notices/read-all" ->
                            changeStatus to """{"read":1,"unread":0}"""
                        request.method == "POST" && path.endsWith("/read") -> changeStatus to """{"unread":0}"""
                        request.method == "DELETE" -> (if (changeStatus == 200) 204 else changeStatus) to ""
                        else -> 404 to """{"error":"not_found"}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fake")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
        ).create(ChinoApi::class.java)
    }

    /** The repository as the container builds it: the API only once a
     *  server is connected ([server]), the account active now ([active]). */
    private class Board {
        val chino = FakeChino()
        var server = true
        var active: String? = "u-1"
        var clock = 1_000_000L
        var apiAsked = 0
        var accountAsked = 0

        val notices = NoticesRepository(
            api = { apiAsked++; if (server) chino.api else null },
            activeAccount = { accountAsked++; active },
            now = { clock },
        )

        val state get() = notices.state.value
        fun refresh(account: String = "u-1", force: Boolean = false) = runBlocking { notices.refresh(account, force) }
        fun first() = state.list.notices.first()
    }

    @Test
    fun `building it asks for nothing - no API client, no account`() {
        val board = Board()
        assertEquals(0, board.apiAsked)
        assertEquals(0, board.accountAsked)
        assertEquals(NoticesState(), board.state)
    }

    @Test
    fun `before a server is connected nothing is asked, and nothing shows`() {
        val board = Board().apply { server = false; active = null }
        board.refresh()
        val n = noticeListOf(kotlinx.serialization.json.Json.parseToJsonElement(LIST)).notices.first()
        runBlocking {
            assertFalse(board.notices.read("u-1", n))
            assertFalse(board.notices.readAll("u-1"))
            assertFalse(board.notices.delete("u-1", n))
        }
        assertTrue(board.chino.requests.isEmpty())
        // The API was asked for — and was not there — before anything else.
        assertEquals(0, board.accountAsked)
        assertEquals(NoticesState(), board.state)
        assertFalse(board.state.showsFor("u-1"))
    }

    @Test
    fun `the list as chino-api answers it - GET v1 notices, the bearer in the header`() {
        val board = Board()
        board.refresh()

        val request = board.chino.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://media.example.org/api/v1/notices", request.url.toString())
        assertEquals("Bearer tok-1", request.header("Authorization"))
        assertEquals("u-1", board.state.account)
        assertEquals(listOf(ID_1, ID_2), board.state.list.notices.map { it.id })
        assertEquals(1, board.state.list.unread)
        assertTrue(board.state.showsFor("u-1"))
        assertFalse(board.state.showsFor("u-2"))
        assertFalse(board.state.showsFor(null))
    }

    @Test
    fun `available false shows nothing, and what was listed stays as it was`() {
        val board = Board()
        board.chino.list = UNAVAILABLE
        board.refresh()
        assertTrue(board.state.isFor("u-1"))
        assertFalse(board.state.showsFor("u-1"))
        assertTrue(board.state.list.notices.isEmpty())

        board.chino.list = LIST
        board.refresh(force = true)
        assertTrue(board.state.showsFor("u-1"))
        board.chino.list = UNAVAILABLE
        board.refresh(force = true)
        assertFalse(board.state.showsFor("u-1"))
        assertEquals(2, board.state.list.notices.size)
    }

    @Test
    fun `no answer at all changes nothing`() {
        val board = Board()
        board.refresh()
        val before = board.state
        board.chino.unreachable = true
        board.refresh(force = true)
        assertEquals(before, board.state)
        // An answer that is no JSON object is no answer from portal-api.
        board.chino.unreachable = false
        board.chino.list = "<html>502 Bad Gateway</html>"
        board.refresh(force = true)
        assertEquals(before, board.state)
    }

    @Test
    fun `asks at most every ten seconds, unless forced`() {
        val board = Board()
        board.refresh()
        board.refresh()
        board.clock += NoticesRepository.MIN_REFRESH_MS - 1
        board.refresh()
        assertEquals(1, board.chino.requests.size)
        board.refresh(force = true)
        assertEquals(2, board.chino.requests.size)
        board.clock += NoticesRepository.MIN_REFRESH_MS
        board.refresh()
        assertEquals(3, board.chino.requests.size)
    }

    @Test
    fun `another account starts from nothing, and one no longer active is not asked for`() {
        val board = Board()
        board.refresh()
        board.active = "u-2"
        board.refresh("u-1")
        assertEquals(1, board.chino.requests.size)
        assertFalse(board.state.isFor("u-2"))

        board.chino.list = """{"notices":[],"unread":0,"available":true}"""
        board.refresh("u-2")
        assertEquals(2, board.chino.requests.size)
        assertTrue(board.state.showsFor("u-2"))
        assertTrue(board.state.list.notices.isEmpty())
        assertFalse(board.state.isFor("u-1"))
    }

    @Test
    fun `opening an unread notice reads it - POST v1 notices id read, once`() {
        val board = Board()
        board.refresh()
        assertTrue(runBlocking { board.notices.read("u-1", board.first()) })

        val request = board.chino.requests.last()
        assertEquals("POST", request.method)
        assertEquals("https://media.example.org/api/v1/notices/$ID_1/read", request.url.toString())
        assertEquals("Bearer tok-1", request.header("Authorization"))
        assertEquals(0, board.state.list.unread)
        assertNotNull(board.first().readAt)
        // Read already: nothing more to send.
        assertFalse(runBlocking { board.notices.read("u-1", board.first()) })
        assertEquals(2, board.chino.requests.size)
    }

    @Test
    fun `read all and delete - POST v1 notices read-all, DELETE v1 notices id`() {
        val board = Board()
        board.refresh()
        assertTrue(runBlocking { board.notices.readAll("u-1") })
        val readAll = board.chino.requests.last()
        assertEquals("POST", readAll.method)
        assertEquals("https://media.example.org/api/v1/notices/read-all", readAll.url.toString())
        assertEquals(0, board.state.list.unread)
        assertTrue(board.state.list.notices.none { it.isUnread })

        assertTrue(runBlocking { board.notices.delete("u-1", board.first()) })
        val delete = board.chino.requests.last()
        assertEquals("DELETE", delete.method)
        assertEquals("https://media.example.org/api/v1/notices/$ID_1", delete.url.toString())
        assertEquals("Bearer tok-1", delete.header("Authorization"))
        assertEquals(listOf(ID_2), board.state.list.notices.map { it.id })
    }

    @Test
    fun `a change chino-api could not make leaves the notice as it was, and asks again`() {
        for (status in listOf(502, 503, 404, 401)) {
            val board = Board()
            board.refresh()
            board.chino.changeStatus = status
            val before = board.state.list
            assertFalse("$status", runBlocking { board.notices.read("u-1", board.first()) })
            assertFalse("$status", runBlocking { board.notices.delete("u-1", board.first()) })
            assertFalse("$status", runBlocking { board.notices.readAll("u-1") })
            assertEquals("$status", before, board.state.list)
            // Each failed change asked for the list again, forced.
            assertEquals("$status", listOf("GET", "POST", "GET", "DELETE", "GET", "POST", "GET"), board.chino.requests.map { it.method })
        }
        val board = Board()
        board.refresh()
        board.chino.unreachable = true
        val before = board.state.list
        assertFalse(runBlocking { board.notices.delete("u-1", board.first()) })
        assertEquals(before, board.state.list)
    }

    @Test
    fun `nothing is sent for one person's notices with another's bearer`() {
        val board = Board()
        board.refresh()
        val notice = board.first()
        board.active = "u-2"
        runBlocking {
            assertFalse(board.notices.read("u-1", notice))
            assertFalse(board.notices.readAll("u-1"))
            assertFalse(board.notices.delete("u-1", notice))
        }
        assertEquals(1, board.chino.requests.size)
    }

    @Test
    fun `an id chino-api would not forward is not sent`() {
        val board = Board()
        board.refresh()
        val odd = board.first().copy(id = "../me")
        runBlocking {
            assertFalse(board.notices.read("u-1", odd))
            assertFalse(board.notices.delete("u-1", odd))
        }
        assertEquals(1, board.chino.requests.size)
    }

    @Test
    fun `signing out forgets them`() {
        val board = Board()
        board.refresh()
        board.notices.clear()
        assertEquals(NoticesState(), board.state)
        assertNull(board.state.account)
    }

    private companion object {
        /** As ServerBootstrap stores it: the server's /api, Retrofit resolving "v1/..." on it. */
        const val BASE_URL = "https://media.example.org/api/"
        const val ID_1 = "00000001-0000-4000-8000-000000000001"
        const val ID_2 = "00000002-0000-4000-8000-000000000002"
        const val LIST = """{"notices":[
            {"id":"$ID_1","addon":"example","addonTitle":"Example","addonIcon":"puzzle",
             "title":"Your title is ready","body":"It is in your library now.","link":"/portal/app/example",
             "itemId":"m1","createdAt":"2026-10-05T07:58:00Z","readAt":null},
            {"id":"$ID_2","addon":"example","addonTitle":"Example","addonIcon":"puzzle",
             "title":"Saved","body":"Your settings are saved.","link":"","itemId":"",
             "createdAt":"2026-10-04T07:58:00Z","readAt":"2026-10-04T08:00:00Z"}
          ],"unread":1,"available":true}"""
        const val UNAVAILABLE = """{"notices":[],"unread":0,"available":false}"""
    }
}
