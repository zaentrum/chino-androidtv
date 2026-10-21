package cloud.nalet.chino.tv.ui.search

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.RetrofitFactory
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a search asks chino-api for and what it finds, against a fake chino-api
 * (the app's own client — its bearer, its JSON — with an interceptor that
 * answers in place of the network): one GET v1/search, its titles and people
 * in the order chino-api ranks them; and against a chino-api older than it,
 * which answers 404, the three requests the app made before, films then
 * series.
 */
class CatalogSearchTest {
    private class FakeChino(
        private val searchStatus: Int = 200,
        private val moviesStatus: Int = 200,
        private val peopleStatus: Int = 200,
        private val unreachable: Boolean = false,
    ) {
        // The three requests of a search as before come in parallel, on
        // OkHttp's threads.
        val requests = CopyOnWriteArrayList<Request>()

        val api: ChinoApi = RetrofitFactory.retrofit(
            BASE_URL,
            RetrofitFactory.httpClient(tokenProvider = { "tok-1" }, forceRefresh = { null })
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    if (unreachable) throw IOException("connect timed out")
                    val (status, body) = when (request.url.encodedPath) {
                        "/api/v1/search" -> searchStatus to SEARCH
                        "/api/v1/items" -> when (request.url.queryParameter("type")) {
                            "movie" -> moviesStatus to MOVIES
                            "series" -> 200 to SERIES
                            else -> 400 to ERROR
                        }
                        "/api/v1/people" -> peopleStatus to PEOPLE
                        else -> 404 to NOT_FOUND
                    }
                    // A route the server has not (an older chino-api's
                    // v1/search) is its router's plain 404.
                    val (answer, type) = when (status) {
                        200 -> body to "application/json"
                        404 -> NOT_FOUND to "text/plain; charset=utf-8"
                        else -> ERROR to "application/json"
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fake")
                        .body(answer.toResponseBody(type.toMediaType()))
                        .build()
                }
                .build(),
        ).create(ChinoApi::class.java)

        fun search(q: String = "sintel") = runBlocking { searchCatalog(api, q) }

        /** The requests after the first, as "path?query": the three of a
         *  search as before, in whatever order they went. */
        fun asBefore(): Set<String> = requests.drop(1).map { "${it.method} ${it.url.encodedPath}?${it.url.encodedQuery}" }.toSet()
    }

    @Test
    fun `one request - GET v1 search with the query, films and series, 60 titles and 12 people, the bearer`() {
        val chino = FakeChino()
        chino.search("léon the pro")

        val request = chino.requests.single()
        assertEquals("GET", request.method)
        assertEquals("https://media.example.org/api/v1/search", request.url.newBuilder().query(null).build().toString())
        assertEquals(setOf("q", "types", "limit", "people"), request.url.queryParameterNames)
        assertEquals("léon the pro", request.url.queryParameter("q"))
        assertEquals("movie,series", request.url.queryParameter("types"))
        assertEquals("60", request.url.queryParameter("limit"))
        assertEquals("12", request.url.queryParameter("people"))
        assertEquals("Bearer tok-1", request.header("Authorization"))
    }

    @Test
    fun `the titles come in the order chino-api ranks them - films and series together - and the people`() {
        val found = FakeChino().search()

        assertEquals(listOf("m1", "s1", "m2", "s2"), found.items.map { it.id })
        assertEquals(listOf("movie", "series", "movie", "series"), found.items.map { it.kind })
        assertEquals(listOf("p1", "p2"), found.people.map { it.id })
    }

    @Test
    fun `an older chino-api answers 404 - films, then series, and the people, as before`() {
        val chino = FakeChino(searchStatus = 404)
        val found = chino.search()

        assertEquals(listOf("m1", "m2", "s1", "s2"), found.items.map { it.id })
        assertEquals(listOf("p1"), found.people.map { it.id })
        assertEquals("/api/v1/search", chino.requests.first().url.encodedPath)
        assertEquals(4, chino.requests.size)
        assertEquals(
            setOf(
                "GET /api/v1/items?limit=60&q=sintel&type=movie&unwatched=false",
                "GET /api/v1/items?limit=60&q=sintel&type=series&unwatched=false",
                "GET /api/v1/people?q=sintel&limit=12",
            ),
            chino.asBefore(),
        )
        chino.requests.forEach { assertEquals("Bearer tok-1", it.header("Authorization")) }
    }

    @Test
    fun `as before, the people are best effort - the titles alone when they fail`() {
        for (status in listOf(404, 502)) {
            val found = FakeChino(searchStatus = 404, peopleStatus = status).search()
            assertEquals("$status", listOf("m1", "m2", "s1", "s2"), found.items.map { it.id })
            assertTrue("$status", found.people.isEmpty())
        }
    }

    @Test
    fun `as before, a list that fails fails the search`() {
        val failed = assertThrows(HttpException::class.java) { FakeChino(searchStatus = 404, moviesStatus = 502).search() }
        assertEquals(502, failed.code())
    }

    @Test
    fun `any other failure of v1 search fails the search, and nothing more is asked`() {
        for (status in listOf(400, 401, 500, 502, 503)) {
            val chino = FakeChino(searchStatus = status)
            val failed = assertThrows("$status", HttpException::class.java) { chino.search() }
            assertEquals(status, failed.code())
            assertEquals("$status", listOf("/api/v1/search"), chino.requests.map { it.url.encodedPath })
        }
        val unreachable = FakeChino(unreachable = true)
        assertThrows(IOException::class.java) { unreachable.search() }
        assertEquals(1, unreachable.requests.size)
    }

    private companion object {
        /** As ServerBootstrap stores it: the server's /api, Retrofit resolving "v1/..." on it. */
        const val BASE_URL = "https://media.example.org/api/"

        /** Films and series ranked together, best first — not films, then series. */
        const val SEARCH = """{"items":[
            {"id":"m1","type":"movie","title":"Sintel","year":2010,"poster_url":"/api/v1/items/m1/poster","match":"exact"},
            {"id":"s1","type":"series","title":"Sintel Tales","poster_url":"/api/v1/items/s1/poster","match":"prefix"},
            {"id":"m2","type":"movie","title":"Sintel Returns","poster_url":"/api/v1/items/m2/poster","match":"prefix"},
            {"id":"s2","type":"series","title":"Tales of Sintel","poster_url":"/api/v1/items/s2/poster","match":"words"}],
          "people":[
            {"id":"p1","name":"Colin Levy","credits":3,"has_profile":true,"profile_url":"/api/v1/people/p1/profile","match":"prefix"},
            {"id":"p2","name":"Halina Reijn","credits":1,"has_profile":false,"match":"words"}],
          "source":"katalog"}"""

        /** v1/items of each type and v1/people, each ranked by the server. */
        const val MOVIES = """{"items":[{"id":"m1","type":"movie","title":"Sintel"},
            {"id":"m2","type":"movie","title":"Sintel Returns"}],"source":"katalog"}"""
        const val SERIES = """{"items":[{"id":"s1","type":"series","title":"Sintel Tales"},
            {"id":"s2","type":"series","title":"Tales of Sintel"}],"source":"katalog"}"""
        const val PEOPLE = """{"people":[{"id":"p1","name":"Colin Levy","credits":3,"has_profile":false}],"total":1}"""

        const val ERROR = """{"error":"catalog unavailable"}"""
        const val NOT_FOUND = "404 page not found\n"
    }
}
