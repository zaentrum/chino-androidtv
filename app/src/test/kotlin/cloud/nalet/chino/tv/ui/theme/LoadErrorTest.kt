package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
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

/** A screen whose content did not load: what it says for the failure, and
 *  its one way on - try again, or back where the title is gone. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class LoadErrorTest {
    @get:Rule val compose = createComposeRule()

    private val timeout = LoadFailure(LoadFailure.Kind.Connection, "timeout")

    @Test
    fun `try again has the focus first, beside the screen's other focusables`() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                // As on a screen with the rail and the top bar beside it.
                Row {
                    ChinoButton(onClick = {}) { Text("Home") }
                    LoadError(title = "couldn't load the library", failure = timeout, onRetry = {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("try again").assertIsFocused()
        compose.onNodeWithText("Home").assertIsNotFocused()
        // The technical reason under details.
        compose.onNodeWithText("details").assertExists()
        compose.onNodeWithText("timeout").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `try again tries again`() {
        var tries = 0
        compose.setContent {
            ChinoTvTheme {
                Remote()
                LoadError(title = "couldn't load the library", failure = timeout, onRetry = { tries += 1 })
            }
        }
        compose.waitForIdle()
        // OK on the remote, where the focus is.
        compose.onNodeWithText("try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, tries)
    }

    @Test
    fun `no answer says check the connection, an error of the server try again in a moment`() {
        val failure = mutableStateOf(timeout)
        compose.setContent {
            ChinoTvTheme { LoadError(title = "couldn't load the library", failure = failure.value, onRetry = {}) }
        }
        compose.onNodeWithText("Check the connection to the server, then try again.").assertExists()
        failure.value = LoadFailure(LoadFailure.Kind.Server, "HTTP 503 Service Unavailable")
        compose.waitForIdle()
        compose.onNodeWithText("The server ran into a problem. Try again in a moment.").assertExists()
        compose.onNodeWithText("Check the connection to the server, then try again.").assertDoesNotExist()
        compose.onNodeWithText("HTTP 503 Service Unavailable").assertExists()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `gone is back, focused first, and nothing to read but the reason`() {
        var backs = 0
        compose.setContent {
            ChinoTvTheme {
                Remote()
                Row {
                    ChinoButton(onClick = {}) { Text("Home") }
                    GoneError(title = "this title isn't in the library anymore", reason = "HTTP 404 Not Found", onBack = { backs += 1 })
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("back").assertIsFocused()
        compose.onNodeWithText("try again").assertDoesNotExist()
        compose.onNodeWithText("Check the connection to the server, then try again.").assertDoesNotExist()
        compose.onNodeWithText("The server ran into a problem. Try again in a moment.").assertDoesNotExist()
        compose.onNodeWithText("HTTP 404 Not Found").assertExists()
        compose.onNodeWithText("back").performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    @Test
    fun `without a reason there are no details`() {
        compose.setContent {
            ChinoTvTheme {
                LoadError(title = "couldn't load this title", failure = LoadFailure(LoadFailure.Kind.Server, ""), onRetry = {})
            }
        }
        compose.onNodeWithText("details").assertDoesNotExist()
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
