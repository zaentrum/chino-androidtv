package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A screen whose content did not load: its one way on, try again. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class LoadErrorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `try again has the focus first, beside the screen's other focusables`() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                // As on a screen with the rail and the top bar beside it.
                Row {
                    ChinoButton(onClick = {}) { Text("Home") }
                    LoadError(title = "couldn't load the library", reason = "timeout", onRetry = {})
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
                LoadError(title = "couldn't load the library", reason = "timeout", onRetry = { tries += 1 })
            }
        }
        compose.waitForIdle()
        // OK on the remote, where the focus is.
        compose.onNodeWithText("try again").assertIsFocused().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(1, tries)
    }

    @Test
    fun `without a reason there are no details`() {
        compose.setContent {
            ChinoTvTheme { LoadError(title = "couldn't load this title", reason = null, onRetry = {}) }
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
