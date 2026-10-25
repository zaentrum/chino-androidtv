package cloud.nalet.chino.tv.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.nalet.chino.tv.data.api.QualityRung
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The player's menus of one choice open on their picked row, so an OK at
 *  once changes nothing: the speed set, the quality on, the audio playing. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class PlayerMenusFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ChinoTvTheme { Remote(); content() } }
        compose.waitForIdle()
    }

    private fun focusedRow(label: String) = compose.onNode(isFocused()).assert(hasText(label))

    @Test
    fun `the speed menu opens on normal, and on a speed set`() {
        show { SpeedPanel(current = 1.0f, onPick = {}, onDismiss = {}) }
        focusedRow("normal")
    }

    @Test
    fun `the speed menu opens on the speed set`() {
        show { SpeedPanel(current = 1.5f, onPick = {}, onDismiss = {}) }
        focusedRow("1.5x")
    }

    @Test
    fun `the quality menu opens on the quality on`() {
        val rungs = listOf(
            QualityRung(name = "auto", label = "Auto"),
            QualityRung(name = "v1", label = "High (1080p)"),
            QualityRung(name = "v2", label = "Medium (720p)"),
        )
        show { QualityPanel(rungs = rungs, active = "v2", playing = null, onPick = {}, onDismiss = {}) }
        focusedRow("Medium (720p)")
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
