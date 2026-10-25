package cloud.nalet.chino.tv.ui.auth

import cloud.nalet.chino.tv.data.auth.DeviceAuthException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sign-in that failed says its reason, beside the "try again" button: not
 * the button's words again, no "please", no "could not start" where it had
 * started, and the server's code or status under details, never as the
 * message.
 */
class SignInFailureTest {
    @Test
    fun `the code expired and a cancelled sign-in say only that`() {
        assertEquals(AuthUiState.Error("The code expired."), signInFailure(DeviceAuthException("expired_token")))
        assertEquals(AuthUiState.Error("Sign-in was cancelled."), signInFailure(DeviceAuthException("access_denied")))
    }

    @Test
    fun `the server's refusal in words, its code under details`() {
        assertEquals(
            AuthUiState.Error("The server didn't accept the sign-in.", "invalid_client"),
            signInFailure(DeviceAuthException("invalid_client")),
        )
        assertEquals(
            AuthUiState.Error("The server didn't accept the sign-in.", "HTTP 400"),
            signInFailure(DeviceAuthException("HTTP 400")),
        )
        assertEquals(
            AuthUiState.Error("The server ran into a problem.", "HTTP 502"),
            signInFailure(DeviceAuthException("HTTP 502")),
        )
    }

    @Test
    fun `no connection says so, what the device said under details`() {
        assertEquals(
            AuthUiState.Error("Can't reach the server.", "timeout"),
            signInFailure(java.net.SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `no message names an error code`() {
        for (code in listOf("expired_token", "access_denied", "invalid_client", "HTTP 400", "HTTP 503")) {
            val failure = signInFailure(DeviceAuthException(code))
            assertTrue(failure.message, code !in failure.message)
            assertTrue(failure.message, "try again" !in failure.message.lowercase())
        }
    }
}
