package cloud.nalet.chino.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings with the remote: a language menu and the report dialog that close
 * - a pick, BACK - give the focus back to the row or the button that opened
 * them, never to the first item on the screen (the rail's Home, where the
 * next OK would leave Settings). The screen as the shell hosts it, in the
 * safe area.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SettingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private lateinit var viewModel: SettingsViewModel
    private var zapOpened = 0

    @Before
    fun settings() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        // No server: nothing here reaches it.
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, _, _ -> throw IllegalStateException("no server") } as ChinoApi
        viewModel = SettingsViewModel(
            store = SettingsStore(ctx),
            telemetry = Telemetry(ctx) { null },
            bugReporter = BugReporter(api, emptyMap(), FilePendingReportStore(ctx.cacheDir)),
            serverHost = "media.test",
            takesReports = { true },
            subtitleLanguages = { listOf("en", "de", "zh-Hans") },
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
                        onZapNav = { zapOpened++ },
                    )
                }
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

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /** The audio language's row, then the subtitle language's. */
    private fun languageRow(index: Int) =
        compose.onAllNodes(hasText("Language", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))[index]

    @Test
    fun `a language picked in the menu leaves the focus on its row`() {
        languageRow(1).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        // The menu opens on the language set (Off); one down is the next.
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        compose.onNode(hasText("subtitle language")).assertDoesNotExist()
        languageRow(1).assertIsFocused()
    }

    @Test
    fun `BACK from a language menu leaves the focus on its row`() {
        languageRow(0).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        compose.onNode(hasText("audio language")).assertExists()
        back()
        compose.onNode(hasText("audio language")).assertDoesNotExist()
        languageRow(0).assertIsFocused()
    }

    @Test
    fun `the rail's Zap opens Zap from Settings`() {
        compose.onNode(hasContentDescription("Zap")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        assertEquals(1, zapOpened)
    }

    private fun button(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, role)

    @Test
    fun `opened from the rail, the focus is on the rail's Settings, never Home`() {
        compose.onNode(hasContentDescription("Settings")).assertIsFocused()
    }

    @Test
    fun `RIGHT from the rail lands on the first control, the binge-watching toggle at the top`() {
        compose.onNode(hasContentDescription("Settings")).assertIsFocused()
        press(Key.DirectionRight)
        // The toggles' on/off buttons in the page's order, the top one first.
        val toggles = compose.onAllNodes((hasText("on") or hasText("off")) and hasClickAction() and hasRole(Role.Button))
            .fetchSemanticsNodes()
        val focused = compose.onNode(isFocused()).fetchSemanticsNode()
        assertEquals(toggles.first().id, focused.id)
        // RIGHT from a toggle at a row's end: the next one, below it.
        press(Key.DirectionRight)
        assertEquals(toggles[1].id, compose.onNode(isFocused()).fetchSemanticsNode().id)
    }

    @Test
    fun `RIGHT goes to the next control in reading order, nothing past the last, and DOWN from the last stays on the page`() {
        button("delete account").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionRight)
        button("change server").assertIsFocused()
        press(Key.DirectionRight)
        button("report a problem").assertIsFocused()
        press(Key.DirectionRight)
        button("report a problem").assertIsFocused()
        press(Key.DirectionDown)
        button("report a problem").assertIsFocused()
        // Within a row, the one beside it.
        button("see your account").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionRight)
        button("switch account").assertIsFocused()
    }

    @Test
    fun `BACK from the report dialog leaves the focus on its button`() {
        val button = compose.onNode(hasText("report a problem") and hasClickAction())
        button.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        compose.onNode(hasText("Something else")).assertExists()
        back()
        compose.onNode(hasText("Something else")).assertDoesNotExist()
        button.assertIsFocused()
    }
}
