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

    private fun kind(code: Int) = loadFailureOf(http(code)).kind

    @Test
    fun `401 is signed out`() = assertEquals(LoadFailure.Kind.SignedOut, kind(401))

    @Test
    fun `403 is no access`() = assertEquals(LoadFailure.Kind.Forbidden, kind(403))

    @Test
    fun `404 and 410 are not in the library any more`() {
        assertEquals(LoadFailure.Kind.NotFound, kind(404))
        assertEquals(LoadFailure.Kind.NotFound, kind(410))
        assertEquals("HTTP 404 Response.error()", loadFailureOf(http(404)).reason)
    }

    @Test
    fun `408 is the connection's, as a timeout is`() = assertEquals(LoadFailure.Kind.Connection, kind(408))

    @Test
    fun `429 is the server busy`() = assertEquals(LoadFailure.Kind.Busy, kind(429))

    @Test
    fun `any other 4xx is a request the server did not take`() {
        for (code in listOf(400, 402, 405, 409, 412, 413, 415, 418, 422, 451, 499)) {
            assertEquals("$code", LoadFailure.Kind.Rejected, kind(code))
        }
    }

    @Test
    fun `an error of the server is the server's`() {
        for (code in listOf(500, 502, 503, 504, 599)) {
            assertEquals("$code", LoadFailure.Kind.Server, kind(code))
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
        // A message that reads like a status, or a timeout, is not one.
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(IllegalStateException("HTTP 404 Not Found")).kind)
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(IllegalStateException("HTTP 401 Unauthorized")).kind)
        assertEquals(LoadFailure.Kind.Server, loadFailureOf(IllegalStateException("timeout")).kind)
    }

    @Test
    fun `the reason is the error's message, else its class`() {
        assertEquals("IllegalStateException", loadFailureOf(IllegalStateException()).reason)
        assertEquals("IllegalStateException", loadFailureOf(IllegalStateException("  ")).reason)
    }
}
