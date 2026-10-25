package cloud.nalet.chino.tv.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.GenresResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * Movies with the remote: opened from the rail, the focus stays on the
 * rail's Movies; and once "try again" brought the page, the focus is on its
 * first control - never on the rail's Home, where the next OK would leave.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class BrowseFocusTest {
    @get:Rule val compose = createComposeRule()

    private val movies = (1..8).map { Item(id = "m$it", title = "Harbour Light $it", kind = "movie", year = 2010 + it) }

    /** The server: the first [failures] lists of movies fail. */
    private fun api(failures: Int): ChinoApi {
        val asked = AtomicInteger(0)
        return java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listItems" -> if (asked.getAndIncrement() < failures) throw java.io.IOException("can't reach") else ItemsPage(movies)
                "listGenres" -> GenresResponse(listOf("Drama"))
                "mintStreamToken" -> StreamTokenResponse(token = "t")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as ChinoApi
    }

    private fun show(api: ChinoApi): BrowseViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = BrowseViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            telemetry = Telemetry(ctx) { null },
            userFlags = UserFlagsRepository(api),
            baseUrl = "https://media.test/api",
            type = "movie",
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen {
                    BrowseScreen(
                        viewModel = vm, pageTitle = "Movies", type = "movie", onItemSelected = {},
                        onHomeNav = {}, onMoviesNav = {}, onSeriesNav = {}, onSearch = {}, onSettings = {},
                        onWatchlist = {}, onZapNav = {}, onAccountClick = {},
                    )
                }
            }
        }
        return vm
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    @Test
    fun `opened from the rail, the focus is on the rail's Movies as the page arrives`() {
        val vm = show(api(failures = 0))
        compose.waitUntil(5_000) { vm.state.value is BrowseUiState.Ready }
        settle()
        compose.onNode(hasContentDescription("Movies")).assertIsFocused()
    }

    @Test
    fun `after a try again that brought the page, the focus is on its first control, not the rail's Home`() {
        val vm = show(api(failures = 1))
        compose.waitUntil(5_000) { vm.state.value is BrowseUiState.Error }
        settle()
        compose.onNode(hasText("try again") and hasClickAction()).assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitUntil(5_000) { vm.state.value is BrowseUiState.Ready }
        settle()
        compose.onNode(hasText("all genres") and hasClickAction()).assertIsFocused()
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
