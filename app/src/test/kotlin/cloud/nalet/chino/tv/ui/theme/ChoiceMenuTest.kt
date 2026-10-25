package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import cloud.nalet.chino.tv.ui.player.SPEEDS
import cloud.nalet.chino.tv.ui.player.speedLabel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A menu of one choice: its header stays where the rows scroll, the focus
 *  stays inside, OK picks once; and the speed menu's names. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class ChoiceMenuTest {
    @get:Rule val compose = createComposeRule()

    private val names = (1..30).map { "Choice $it" }

    private fun show(onPick: (String) -> Unit = {}) {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                val first = remember { FocusRequester() }
                LaunchedEffect(Unit) { first.requestFocus() }
                Box(Modifier.height(400.dp)) {
                    ChoiceMenu(header = "subtitles", width = 300.dp, maxHeight = 300.dp) {
                        names.forEachIndexed { i, name ->
                            ChoiceRow(
                                label = name,
                                selected = i == 2,
                                focusRequester = first.takeIf { i == 0 },
                                onClick = { onPick(name) },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `the header stays above the rows as they scroll, the focus stays inside`() {
        show()
        repeat(names.size + 3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) } }
        compose.waitForIdle()
        compose.onNodeWithText("Choice 30").assertIsFocused().assertIsDisplayed()
        compose.onNodeWithText("subtitles").assertIsDisplayed()
        repeat(names.size + 3) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) } }
        compose.waitForIdle()
        compose.onNodeWithText("Choice 1").assertIsFocused()
        compose.onNodeWithText("subtitles").assertIsDisplayed()
    }

    @Test
    fun `OK picks the focused row, once`() {
        val picked = ArrayList<String>()
        show { picked += it }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf("Choice 2"), picked)
    }

    @Test
    fun `the speeds by name - normal for 1x`() {
        assertEquals(listOf("0.5x", "0.75x", "normal", "1.25x", "1.5x", "1.75x", "2x"), SPEEDS.map(::speedLabel))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
