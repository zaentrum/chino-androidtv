package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.api.LoadFailure
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A screen whose content did not load: what it says for each failure, and
 *  its one way on - try again, back, or sign in. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class LoadErrorTest {
    @get:Rule val compose = createComposeRule()

    private fun failure(kind: LoadFailure.Kind, reason: String) = LoadFailure(kind, reason)

    private val connection = "Check the connection to the server, then try again."
    private val server = "The server ran into a problem. Try again in a moment."
    private val busy = "The server is busy. Try again in a moment."
    private val rejected = "The server didn't accept the request. Check for an app update, then try again."

    /** Shows the error for [failure] beside another focusable, as a screen
     *  with the rail; [what] and [onBack] as the screen gives them. Counts
     *  the tries, the backs and the sign-ins. */
    private class Pressed {
        var tries = 0
        var backs = 0
        var signIns = 0
    }

    private fun show(failure: LoadFailure, what: String? = null, withBack: Boolean = true): Pressed {
        val pressed = Pressed()
        compose.setContent {
            ChinoTvTheme {
                Remote()
                CompositionLocalProvider(LocalSignIn provides { pressed.signIns += 1 }) {
                    Row {
                        ChinoButton(onClick = {}) { Text("Home") }
                        LoadError(
                            title = "couldn't load the library",
                            failure = failure,
                            onRetry = { pressed.tries += 1 },
                            what = what,
                            onBack = if (withBack) ({ pressed.backs += 1 }) else null,
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return pressed
    }

    /** OK on the remote on [label], which has the focus first. */
    private fun ok(label: String) {
        compose.onNodeWithText(label).assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        compose.onNodeWithText("Home").assertIsNotFocused()
    }

    private fun says(vararg texts: String) = texts.forEach { compose.onNodeWithText(it).assertExists() }

    private fun saysNot(vararg texts: String) = texts.forEach { compose.onNodeWithText(it).assertDoesNotExist() }

    @Test
    fun `no answer, or 408 - check the connection, try again`() {
        val pressed = show(failure(LoadFailure.Kind.Connection, "timeout"))
        says("couldn't load the library", connection, "details", "timeout")
        ok("try again")
        assertEquals(1, pressed.tries)
    }

    @Test
    fun `5xx - the server ran into a problem, try again`() {
        val pressed = show(failure(LoadFailure.Kind.Server, "HTTP 503 Service Unavailable"))
        says("couldn't load the library", server, "HTTP 503 Service Unavailable")
        saysNot(connection)
        ok("try again")
        assertEquals(1, pressed.tries)
    }

    @Test
    fun `429 - the server is busy, try again`() {
        val pressed = show(failure(LoadFailure.Kind.Busy, "HTTP 429 Too Many Requests"))
        says("couldn't load the library", busy, "HTTP 429 Too Many Requests")
        ok("try again")
        assertEquals(1, pressed.tries)
    }

    @Test
    fun `another 4xx - the server did not accept the request, check for an app update`() {
        val pressed = show(failure(LoadFailure.Kind.Rejected, "HTTP 422 Unprocessable Entity"))
        says("couldn't load the library", rejected, "HTTP 422 Unprocessable Entity")
        ok("try again")
        assertEquals(1, pressed.tries)
    }

    @Test
    fun `404 or 410 where the screen knows what it shows - gone, back, nothing more to read`() {
        val pressed = show(failure(LoadFailure.Kind.NotFound, "HTTP 410 Gone"), what = "this title")
        says("this title isn't in the library anymore", "HTTP 410 Gone")
        saysNot("try again", connection, server, busy, rejected)
        ok("back")
        assertEquals(1, pressed.backs)
        assertEquals(0, pressed.tries)
    }

    @Test
    fun `404 where the screen does not know what it shows - a request not accepted, try again`() {
        val pressed = show(failure(LoadFailure.Kind.NotFound, "HTTP 404 Not Found"))
        says("couldn't load the library", rejected)
        ok("try again")
        assertEquals(1, pressed.tries)
    }

    @Test
    fun `403 - no access to this title, back, nothing more to read`() {
        val pressed = show(failure(LoadFailure.Kind.Forbidden, "HTTP 403 Forbidden"), what = "this title")
        says("you don't have access to this title", "HTTP 403 Forbidden")
        saysNot("try again", connection, server)
        ok("back")
        assertEquals(1, pressed.backs)
    }

    @Test
    fun `403 where the screen does not know what it shows - no access to this`() {
        show(failure(LoadFailure.Kind.Forbidden, "HTTP 403 Forbidden"), withBack = false)
        says("you don't have access to this")
        // Back without one of the screen's own is the remote's BACK.
        compose.onNodeWithText("back").assertIsFocused()
    }

    @Test
    fun `401 where a screen could not be spared - signed out, sign in`() {
        val pressed = show(failure(LoadFailure.Kind.SignedOut, "HTTP 401 Unauthorized"))
        says("you're signed out", "Sign in again to keep watching.", "HTTP 401 Unauthorized")
        saysNot("try again", "couldn't load the library")
        ok("sign in")
        assertEquals(1, pressed.signIns)
        assertEquals(0, pressed.tries)
    }

    @Test
    fun `without a reason there are no details`() {
        show(LoadFailure(LoadFailure.Kind.Server, ""))
        saysNot("details")
    }

    /** The remote's input: a TV is in keyboard mode (Robolectric starts in
     *  touch mode). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
