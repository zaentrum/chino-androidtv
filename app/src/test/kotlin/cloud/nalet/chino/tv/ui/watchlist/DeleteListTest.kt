package cloud.nalet.chino.tv.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleting a list asks first (the design system's confirm): "delete list",
 * a body that names what goes, "cancel" with the focus - one OK too many
 * keeps the list - and the danger button that deletes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class DeleteListTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val weekend = Watchlist(id = "l2", name = "Weekend", itemCount = 12)
    private var confirmed = 0
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ConfirmDeleteListDialog(list = weekend, onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
            }
        }
        compose.waitForIdle()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `cancel has the focus, and OK on it keeps the list`() {
        show()
        compose.onNode(hasText("cancel") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `the danger button beside it deletes the list`() {
        show()
        press(Key.DirectionRight)
        compose.onNode(hasText("delete list") and hasClickAction()).assertIsFocused()
        press(Key.DirectionCenter)
        assertEquals(1, confirmed)
        assertEquals(0, dismissed)
    }

    @Test
    fun `BACK keeps the list`() {
        show()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun `the body names the list and what goes with it`() {
        assertEquals(
            "“Weekend” is deleted, with the 12 titles saved in it. They stay in the library and in your other lists.",
            deleteListBody(weekend),
        )
        assertEquals(
            "“Weekend” is deleted, with the title saved in it. The title stays in the library and in your other lists.",
            deleteListBody(weekend.copy(itemCount = 1)),
        )
        assertEquals("“Weekend” is deleted. Nothing is saved in it.", deleteListBody(weekend.copy(itemCount = 0)))
    }
}
