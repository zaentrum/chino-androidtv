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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Deleting an account against a fake chino-api: the app's own client
 * (RetrofitFactory — its bearer, its JSON) with an interceptor that answers
 * in place of the network, the way chino-api's account.go answers.
 */
class AccountDeletionTest {
    private class FakeServer(
        private val status: Int,
        private val body: String? = null,
        private val contentType: String = "application/json",
        token: String = "tok-1",
        private val unreachable: Boolean = false,
    ) {
        val requests = mutableListOf<Request>()

        val api: ChinoApi = RetrofitFactory.retrofit(
            BASE_URL,
            RetrofitFactory.httpClient(tokenProvider = { token }, forceRefresh = { null })
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    if (unreachable) throw IOException("connect timed out")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(status)
                        .message("fake")
                        .body(body.orEmpty().toResponseBody(contentType.toMediaType()))
                        .build()
                }
                .build(),
        ).create(ChinoApi::class.java)
    }

    private fun answer(server: FakeServer): AccountDeletion = runBlocking { server.api.deleteAccount() }

    @Test
    fun `200 - the account and its data are deleted`() {
        val deleted = FakeServer(200, """{"account":"deleted","deleted":{"progress":12,"watched":30,"watchlists":2,"likes":4}}""")
        assertEquals(AccountDeletion.Deleted, answer(deleted))
        // The account was gone already: now its data is too.
        assertEquals(AccountDeletion.Deleted, answer(FakeServer(200, """{"account":"gone","deleted":{}}""")))
    }

    @Test
    fun `the request - DELETE v1 me, the bearer in the header and never in the URL`() {
        val server = FakeServer(200, """{"account":"deleted"}""", token = "tok-secret")
        answer(server)

        val request = server.requests.single()
        assertEquals("DELETE", request.method)
        assertEquals("https://media.example.org/api/v1/me", request.url.toString())
        assertEquals("Bearer tok-secret", request.header("Authorization"))
        assertNull(request.url.query)
        assertFalse("tok-secret" in request.url.toString())
    }

    @Test
    fun `409 - refused, in the server's words`() {
        val server = FakeServer(
            409,
            """{"error":"refused","message":"You are the last admin of this server: make someone else an admin first."}""",
        )
        val refused = answer(server)
        assertEquals(
            AccountDeletion.Refused("You are the last admin of this server: make someone else an admin first."),
            refused,
        )
        assertEquals("Not Deleted", refused.title)
        assertTrue(refused.isFinal)
        // No message to show: still a refusal, said plainly.
        assertEquals(AccountDeletion.Refused("This server won't delete your account."), answer(FakeServer(409)))
    }

    @Test
    fun `501 - not available on this server, whatever the body says`() {
        val server = FakeServer(
            501,
            """{"error":"account_deletion_unavailable","message":"Accounts are not deleted from the apps on this server: ask whoever runs it."}""",
        )
        val unavailable = answer(server)
        assertEquals(AccountDeletion.Unavailable, unavailable)
        assertEquals("Not Available", unavailable.title)
        assertEquals("Deleting your account isn't available on this server — ask its administrator.", unavailable.text)
        assertTrue(unavailable.isFinal)
    }

    @Test
    fun `502 - nothing deleted, try again later with the server's message`() {
        val server = FakeServer(
            502,
            """{"error":"account_not_deleted","message":"Your account could not be deleted right now, so nothing was. Try again later."}""",
        )
        val failed = answer(server)
        assertEquals(
            AccountDeletion.Failed("Your account could not be deleted right now, so nothing was. Try again later."),
            failed,
        )
        assertEquals("Try Again Later", failed.title)
        assertFalse(failed.isFinal)
    }

    @Test
    fun `any other answer is a failure too, and only a JSON message is shown`() {
        assertEquals(
            AccountDeletion.Failed("Your data could not be deleted, so nothing was. Try again later."),
            answer(FakeServer(500, """{"error":"data_not_deleted","message":"Your data could not be deleted, so nothing was. Try again later."}""")),
        )
        // A proxy's page, a plain-text error, an empty body: no server words.
        assertEquals(
            AccountDeletion.Failed("Your account couldn't be deleted right now (HTTP 502)."),
            answer(FakeServer(502, "<html><body>502 Bad Gateway</body></html>", contentType = "text/html")),
        )
        assertEquals(
            AccountDeletion.Failed("Your account couldn't be deleted right now (HTTP 503)."),
            answer(FakeServer(503, "upstream connect error\n", contentType = "text/plain")),
        )
        // A sign-in the server no longer takes, even after the client's refresh.
        assertEquals(
            AccountDeletion.Failed("This server no longer accepts your sign-in. Sign in again, then try once more."),
            answer(FakeServer(401, "unauthorized\n", contentType = "text/plain")),
        )
    }

    @Test
    fun `only a 200 signs out - another 2xx is no proof the account is gone`() {
        for (status in listOf(201, 202, 204)) {
            assertTrue("$status", answer(FakeServer(status)) is AccountDeletion.Failed)
        }
    }

    @Test
    fun `a request that never reaches the server is a failure, not a throw`() {
        assertEquals(
            AccountDeletion.Failed("The server couldn't be reached."),
            answer(FakeServer(200, unreachable = true)),
        )
    }

    @Test
    fun `the account is signed out only once the server says it is gone`() {
        fun signOuts(server: FakeServer): Int {
            var count = 0
            runBlocking { server.api.deleteAccountThenSignOut { count++ } }
            return count
        }
        assertEquals(1, signOuts(FakeServer(200, """{"account":"deleted"}""")))
        assertEquals(0, signOuts(FakeServer(409, """{"error":"refused","message":"No."}""")))
        assertEquals(0, signOuts(FakeServer(501)))
        assertEquals(0, signOuts(FakeServer(502, """{"error":"account_not_deleted"}""")))
        assertEquals(0, signOuts(FakeServer(500)))
        assertEquals(0, signOuts(FakeServer(200, unreachable = true)))
    }

    @Test
    fun `a long server message is cut to a few sentences`() {
        val refused = answer(FakeServer(409, """{"error":"refused","message":"${"x".repeat(1000)}"}"""))
        assertEquals(300, (refused as AccountDeletion.Refused).message.length)
    }

    private companion object {
        /** As ServerBootstrap stores it: the server's /api, Retrofit resolving "v1/..." on it. */
        const val BASE_URL = "https://media.example.org/api/"
    }
}
