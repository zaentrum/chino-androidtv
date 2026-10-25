package cloud.nalet.chino.tv.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.ui.theme.ChinoButton
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings' controls share one left edge, and a section's buttons stand
 *  12 dp apart (the TV's dp), room for a focused one's ring. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SettingsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `a section's buttons start where its rows' names do, 12 dp apart`() {
        compose.setContent {
            ChinoTvTheme {
                Column(Modifier.width(1000.dp)) {
                    Section(title = "audio", subtitle = "What the setting does.") {
                        LanguageSettingRow(choices = audioLanguageChoices("orig"), value = "orig", onOpen = {})
                    }
                    Section(title = "account", subtitle = "Who is watching.") {
                        SettingButtons {
                            ChinoButton(onClick = {}) { Text("see your account") }
                            ChinoButton(onClick = {}) { Text("switch account") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun left(text: String, button: Boolean = false) =
            compose.onNode(hasText(text) and (if (button) hasClickAction() else hasText(text))).fetchSemanticsNode().boundsInRoot
        val name = compose.onNode(hasText("Language"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val first = left("see your account", button = true)
        val second = left("switch account", button = true)
        // One left edge: px are 2 a TV dp.
        assertEquals(name.left, first.left, 1f)
        assertEquals(12f, (second.left - first.right) / 2f, 0.5f)
    }
}
