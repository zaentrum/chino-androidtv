package cloud.nalet.chino.tv.data.api

import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/** What a failed load is, by the HTTP status the server answered or that
 *  there was no answer - never by the words of the error. */
class LoadFailureTest {

    private fun http(code: Int) = HttpException(Response.error<Any>(code, "".toResponseBody()))

    @Test
    fun `404 is not found`() {
        val failure = loadFailureOf(http(404))
        assertEquals(LoadFailure.Kind.NotFound, failure.kind)
        assertEquals("HTTP 404 Response.error()", failure.reason)
    }

    @Test
    fun `an error of the server is the server's`() {
        for (code in listOf(500, 502, 503, 504)) {
            assertEquals("$code", LoadFailure.Kind.Server, loadFailureOf(http(code)).kind)
        }
        // Another answer the app cannot go on with is the server's too.
        for (code in listOf(400, 401, 403, 409, 429)) {
            assertEquals("$code", LoadFailure.Kind.Server, loadFailureOf(http(code)).kind)
        }
    }

    @Test
    fun `no answer is the connection's - a timeout, no route, no name, no TLS`() {
        for (e in listOf(
            SocketTimeoutException("timeout"),
            ConnectException("failed to connect to the server after 10000ms"),
            UnknownHostException("Unable to resolve host"),
            SSLHandshakeException("handshake"),
            IOException("unexpected end of stream"),
        )) {
            assertEquals(e.toString(), LoadFailure.Kind.Connection, loadFailureOf(e).kind)
        }
        assertEquals("timeout", loadFailureOf(SocketTimeoutException("timeout")).reason)
    }

    @Test
    fun `an answer the app cannot read is the server's, the words of an error count for nothing`() {
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(SerializationException("Unexpected JSON token")).kind)
        // A message that reads like a 404, or a timeout, is not one.
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(IllegalStateException("HTTP 404 Not Found")).kind)
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(IllegalStateException("timeout")).kind)
    }

    @Test
    fun `the reason is the error's message, else its class`() {
        assertEquals("IllegalStateException", loadFailureOf(IllegalStateException()).reason)
        assertEquals("IllegalStateException", loadFailureOf(IllegalStateException("  ")).reason)
    }
}
