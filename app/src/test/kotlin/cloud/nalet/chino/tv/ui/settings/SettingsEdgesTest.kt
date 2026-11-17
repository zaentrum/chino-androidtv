package cloud.nalet.chino.tv.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.LastKnownInMemory
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.feedback.BugReporter
import cloud.nalet.chino.tv.feedback.FilePendingReportStore
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings scrolls as Home and a title's page do, with no margin round the
 * screen (chino-web's layout): out to the screen's right and bottom edges,
 * so what scrolls by shows down to the bottom edge; the control the remote
 * moves to, its ring at its growth, is brought into view below the top bar
 * and a gutter clear of the bottom and right edges. In the TV's dp: 960 ×
 * 540, 2 px each; the top bar 48 high, the gutter 12.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class SettingsEdgesTest {
    @get:Rule val compose = createComposeRule()

    private fun dp(px: Rect) = Rect(px.left / 2f, px.top / 2f, px.right / 2f, px.bottom / 2f)

    private fun show() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, _, _ -> throw IllegalStateException("no server") } as ChinoApi
        val viewModel = SettingsViewModel(
            store = SettingsStore(ctx),
            telemetry = Telemetry(ctx) { null },
            bugReporter = BugReporter(api, emptyMap(), FilePendingReportStore(ctx.cacheDir)),
            serverHost = "media.test",
            takesReports = { true },
            subtitleLanguages = { listOf("en", "de") },
            // A server without lists by kind: no watchlist group.
            kindsOffered = { false },
            kindsOfferedNow = { false },
            accountSettings = AccountSettings(
                api, CoroutineScope(SupervisorJob() + Dispatchers.IO), activeChanges = { 0 }, lastKnown = LastKnownInMemory(),
            ) { ActiveChoice("viewer", 0) },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
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

    /** The focused control's reach: its bounds, grown, and a button's ring
     *  outside a 2 dp gap; a row's ring is inside its edge. */
    private fun focusedReach(): Rect {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        val b = dp(node.boundsInRoot)
        val button = node.config.getOrNull(SemanticsProperties.Role) == androidx.compose.ui.semantics.Role.Button
        val ring = if (button) 2f + 3f else 0f
        return Rect(b.left - ring, b.top - ring, b.right + ring, b.bottom + ring)
    }

    @Test
    fun `the page scrolls to the screen's bottom edge, the focused control a gutter clear of it`() {
        show()
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the page ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        // Down the page with the remote, to its last control: every focused
        // one below the top bar and a gutter clear of the edges, and what
        // scrolls by below it shown in the gutter, down to the bottom edge -
        // where a focused control brings its setting's text into view, that
        // text rests at the gutter and the next setting's start shows below.
        compose.onAllNodes(hasText("Language", substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.RequestFocus))[0]
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        var crossed = false
        repeat(8) {
            val r = focusedReach()
            assertTrue("focused control reaches $r", r.bottom <= 528.5f && r.top >= 47.5f && r.right <= 948.5f)
            val shown = compose.onAllNodes(hasText("", substring = true)).fetchSemanticsNodes().map { dp(it.boundsInRoot) }
            if (shown.any { it.top < 539.5f && it.bottom > 529f && it.bottom <= 540.5f }) crossed = true
            press(Key.DirectionDown)
        }
        assertTrue("nothing shown in the bottom gutter while scrolled", crossed)
        compose.onNode(isFocused()).assert(hasText("report a problem") and hasClickAction())
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
