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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.GenresResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * Movies and Shows with the remote: opened from the rail, the focus stays on
 * the rail's item; once "try again" brought the page, the focus is on its
 * first control; and a chip pressed keeps it while the page loads what the
 * chip asked for - never the rail, where the next OK would leave the page.
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

    private fun show(api: ChinoApi, type: String = "movie"): BrowseViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = BrowseViewModel(
            api = api,
            streamTokens = StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            telemetry = Telemetry(ctx) { null },
            userFlags = UserFlagsRepository(api) { ActiveChoice("viewer", 0) },
            openedFor = { "viewer" },
            baseUrl = "https://media.test/api",
            type = type,
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
                    BrowseScreen(
                        viewModel = vm, pageTitle = if (type == "movie") "Movies" else "Shows", type = type, onItemSelected = {},
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

    /** A server that holds its lists of titles back until [answer]: the
     *  page loading after a chip, as on a TV, where the list takes a moment. */
    private class SlowServer(private val items: List<Item>) {
        var hold = false
        private val waiting = mutableListOf<Continuation<Any?>>()

        val api: ChinoApi = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, method, args ->
            when (method.name) {
                "listItems" -> if (hold) {
                    @Suppress("UNCHECKED_CAST")
                    waiting += args!!.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else {
                    ItemsPage(items)
                }
                "listGenres" -> GenresResponse(listOf("Drama", "Comedy", "Mystery"))
                "mintStreamToken" -> StreamTokenResponse(token = "t")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as ChinoApi

        /** The lists asked for come back. */
        fun answer() {
            val now = waiting.toList()
            waiting.clear()
            for (c in now) c.resumeWith(Result.success(ItemsPage(items)))
        }
    }

    private fun chip(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** OK on the focused chip: the page asks for its titles, and while they
     *  are on their way and once they are there, [label] has the focus. */
    private fun pressKeepsFocus(server: SlowServer, label: String) {
        press(Key.DirectionCenter)
        chip(label).assertIsFocused()
        compose.runOnUiThread { server.answer() }
        settle()
        chip(label).assertIsFocused()
    }

    /** The page loaded from [server], the focus moved to the chip [label],
     *  the server then slow to answer. */
    private fun page(server: SlowServer, type: String, label: String): BrowseViewModel {
        val vm = show(server.api, type)
        compose.waitUntil(5_000) { vm.state.value is BrowseUiState.Ready }
        settle()
        chip(label).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        chip(label).assertIsFocused()
        server.hold = true
        return vm
    }

    @Test
    fun `on Movies a decade pressed keeps the focus as its filter goes on and off, clear filters coming and going`() {
        val server = SlowServer(movies)
        page(server, "movie", "2020s")
        // On: clear filters comes at the end of the sort row.
        pressKeepsFocus(server, "2020s")
        chip("clear filters").assertExists()
        // Off again: clear filters goes.
        pressKeepsFocus(server, "2020s")
        compose.onNode(hasText("clear filters")).assertDoesNotExist()
    }

    @Test
    fun `on Shows a chip of every row keeps the focus as the grid loads - a genre, a rating, a sort`() {
        val server = SlowServer(movies.map { it.copy(kind = "series") })
        page(server, "series", "Comedy")
        pressKeepsFocus(server, "Comedy")
        chip("7.0+").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        pressKeepsFocus(server, "7.0+")
        chip("rating").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        pressKeepsFocus(server, "rating")
        // And the genre again, off: the filters it leaves keep clear filters.
        chip("Comedy").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        pressKeepsFocus(server, "Comedy")
        chip("clear filters").assertExists()
    }

    @Test
    fun `clear filters goes with what it clears - the focus on the chip before it, not the rail`() {
        val server = SlowServer(movies)
        page(server, "movie", "1990s")
        pressKeepsFocus(server, "1990s")
        chip("clear filters").performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
        press(Key.DirectionCenter)
        chip("year (newest)").assertIsFocused()
        compose.runOnUiThread { server.answer() }
        settle()
        chip("year (newest)").assertIsFocused()
        compose.onNode(hasText("clear filters")).assertDoesNotExist()
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
