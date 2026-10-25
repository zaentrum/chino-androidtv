package cloud.nalet.chino.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.SettingsStore
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import cloud.nalet.chino.tv.feedback.FilePendingReportStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeArea
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings with the remote, down to its last control and back: a focused
 * control brings the text under it into view along with itself - change
 * server its "Connected to media.test.", the page's last line, whole and
 * inside the safe area, never cut at the screen's bottom - and the focus
 * back on the first control scrolls the page back to its top, its
 * "settings" heading shown; UP there shows it too. In the TV's dp: 960 ×
 * 540, 2 px each; the safe area 27 dp at the bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
// The app's faces measured as on a TV: lines wrap where they do there.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class SettingsScrollTest {
    @get:Rule val compose = createComposeRule()

    private fun show(takesReports: Boolean) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, _, _ -> throw IllegalStateException("no server") } as ChinoApi
        val viewModel = SettingsViewModel(
            store = SettingsStore(ctx),
            telemetry = Telemetry(ctx) { null },
            bugReporter = BugReporter(api, emptyMap(), FilePendingReportStore(ctx.cacheDir)),
            serverHost = "media.test",
            takesReports = { takesReports },
            subtitleLanguages = { listOf("en", "de") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen {
                    SettingsScreen(
                        viewModel = viewModel,
                        onSwitchAccount = {},
                        onProfile = {},
                        onDeleteAccount = {},
                        onChangeServer = {},
                        serverHost = "media.test",
                        onZapNav = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** How far the page is scrolled down, in px: 0 at its top. */
    private fun scrolled(): Float =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** Where [text] lies on the screen, in the TV's dp: its top and bottom. */
    private fun place(text: String): Pair<Float, Float> {
        val b = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot
        return b.top / 2f to b.bottom / 2f
    }

    private val safeBottom = 540f - 27f

    /** The page's first control: the first setting's on/off button. */
    private fun firstControl() = compose.onAllNodes((hasText("on") or hasText("off")) and hasClickAction())[0]

    /** Onto the page's first control. */
    private fun toFirstControl() {
        firstControl().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    /** DOWN until [label] has the focus. */
    private fun downTo(label: String) {
        var downs = 0
        while (compose.onAllNodes(isFocused() and hasText(label)).fetchSemanticsNodes().isEmpty()) {
            press(Key.DirectionDown)
            assertTrue("never came down to $label", ++downs < 20)
        }
    }

    @Test
    fun `change server brings the page's last line into view whole, inside the safe area`() {
        show(takesReports = false)
        toFirstControl()
        downTo("change server")
        val (top, bottom) = place("Connected to media.test.")
        assertTrue("the last line at $top..$bottom dp", top >= 0f && bottom <= safeBottom + 0.5f)
        assertTrue("scrolled down to it", scrolled() > 0f)
    }

    @Test
    fun `with feedback after it, change server still shows its own line whole, and the last control its own`() {
        show(takesReports = true)
        toFirstControl()
        downTo("change server")
        val (_, bottom) = place("Connected to media.test.")
        assertTrue("its line ends at $bottom dp", bottom <= safeBottom + 0.5f)
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("report a problem"))
        val (_, last) = place("Tell the people who run this server. The app's and the device's details go with your report.")
        assertTrue("the last line ends at $last dp", last <= safeBottom + 0.5f)
    }

    @Test
    fun `back up to the first control the page shows its top again, and UP there too`() {
        show(takesReports = true)
        toFirstControl()
        assertEquals(0f, scrolled())
        downTo("report a problem")
        assertTrue("scrolled down", scrolled() > 0f)
        // UP, back to the first control.
        val first = firstControl().fetchSemanticsNode().id
        var ups = 0
        while (compose.onNode(isFocused()).fetchSemanticsNode().id != first) {
            press(Key.DirectionUp)
            assertTrue("never came back up to the first control", ++ups < 20)
        }
        assertEquals("the page at its top", 0f, scrolled())
        val (top, _) = place("settings")
        assertTrue("the heading at $top dp", top >= 0f)
        // UP there: the top bar takes the focus, the page stays at its top.
        press(Key.DirectionUp)
        assertEquals(0f, scrolled())
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
