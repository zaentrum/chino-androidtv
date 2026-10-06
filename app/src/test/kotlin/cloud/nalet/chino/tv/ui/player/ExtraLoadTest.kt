package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import cloud.nalet.chino.tv.data.model.Trailer
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import retrofit2.HttpException

/**
 * What an extra plays in the player, against a fake chino-api (the app's own
 * client with an interceptor that answers in place of the network): the
 * extra's play_path with the stream token and the caps, from the title's
 * detail — and that detail is the one request an extra makes. No progress,
 * watched, segments, trickplay, subtitles, play info, next episode or
 * prewarm.
 */
class ExtraLoadTest {
    private class FakeChino(private val status: Int = 200, private val body: String = DETAIL) {
        val requests = mutableListOf<Request>()

        val api: ChinoApi = RetrofitFactory.retrofit(
            "https://media.example.org/api/",
            RetrofitFactory.httpClient(tokenProvider = { "tok-1" }, forceRefresh = { null })
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val found = request.method == "GET" && request.url.encodedPath == "/api/v1/items/m1"
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (found) status else 404)
                        .message("fake")
                        .body((if (found) body else """{"error":"not found"}""").toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
        ).create(ChinoApi::class.java)

        fun load(extraId: String = "x1") = runBlocking {
            loadExtra(
                api = api,
                baseUrl = "https://media.example.org/api",
                streamToken = "tok",
                caps = "avc:1080,aac,mp3",
                itemId = "m1",
                extraId = extraId,
            )
        }
    }

    @Test
    fun `a listed extra plays its play path with the token and the caps - the detail is the one request`() {
        val chino = FakeChino()

        val loaded = chino.load()

        assertEquals(
            ExtraLoad.Found(
                masterUrl = "https://media.example.org/api/v1/items/m1/extras/x1/play/master.m3u8?stream=tok&caps=avc:1080,aac,mp3",
                title = "A Film · Trailer",
                link = LINK,
            ),
            loaded,
        )
        assertEquals(listOf("GET /api/v1/items/m1"), chino.requests.map { "${it.method} ${it.url.encodedPath}" })
    }

    @Test
    fun `an extra the detail no longer lists is not available - the link is offered`() {
        assertEquals(ExtraLoad.NotAvailable(LINK), FakeChino().load(extraId = "gone"))
    }

    @Test
    fun `a title that answers 404 is not available`() {
        // Gone, or above the viewer's rating cap: chino-api's title gate says 404.
        assertEquals(ExtraLoad.NotAvailable(null), FakeChino(status = 404).load())
    }

    @Test
    fun `any other failure is not taken for a missing extra`() {
        assertThrows(HttpException::class.java) { FakeChino(status = 502, body = """{"error":"catalog unavailable"}""").load() }
    }

    @Test
    fun `the master URL carries the token, then the caps`() {
        assertEquals(
            "https://media.example.org/api/v1/items/s/extras/e/play/master.m3u8?stream=tok",
            extraMasterUrl("https://media.example.org/api/", "/api/v1/items/s/extras/e/play/master.m3u8", "tok", ""),
        )
        assertEquals(
            "https://media.example.org/api/v1/items/s/extras/e/play/master.m3u8?caps=avc",
            extraMasterUrl("https://media.example.org/api", "/api/v1/items/s/extras/e/play/master.m3u8", "", "avc"),
        )
        assertEquals(null, extraMasterUrl("https://media.example.org/api", "", "tok", "avc"))
    }

    @Test
    fun `an extra is called by its title and its own name`() {
        assertEquals("Sintel · Trailer", extraTitle("Sintel", "Trailer"))
        assertEquals("Sintel · Teaser 2", extraTitle("Sintel", "Teaser 2"))
        // One without a name is the title's trailer; a title without one, the extra's name.
        assertEquals("Sintel · Trailer", extraTitle("Sintel", " "))
        assertEquals("Trailer", extraTitle("", "Trailer"))
    }

    private companion object {
        val LINK = Trailer(url = "https://www.youtube.com/watch?v=x1", site = "YouTube", title = "Official Trailer")

        val DETAIL = """{"id":"m1","type":"movie","title":"A Film",
            "trailers":[{"site":"YouTube","external_id":"x1","url":"https://www.youtube.com/watch?v=x1","title":"Official Trailer"}],
            "extras":[{"id":"x1","kind":"trailer","title":"Trailer","language":"en","duration_ms":33000,"local":true,
              "play_path":"/api/v1/items/m1/extras/x1/play/master.m3u8"}]}"""
    }
}
