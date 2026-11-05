package cloud.nalet.chino.tv.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.api.WatchlistCounts
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The hub and a whole list with "Separate movies and shows" on (docs/chino,
 *  watchlist): the rows, what is left out, and the remote. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class WatchlistByKindTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val movies = (1..4).map { Item(id = "m$it", title = "Movie $it", kind = "movie") }
    private val shows = (1..4).map { Item(id = "s$it", title = "Show $it", kind = "series") }
    private val list = Watchlist(
        id = "l1", name = "Watchlist", itemCount = 9, isDefault = true,
        counts = WatchlistCounts(movie = 4, series = 4),
    )

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun focus(title: String) {
        compose.onNode(hasText(title) and hasClickAction()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
    }

    private fun assertFocused(title: String) {
        compose.onNode(isFocused()).assert(hasText(title, substring = true) or hasAnyDescendantText(title))
    }

    private fun hasAnyDescendantText(title: String): SemanticsMatcher =
        androidx.compose.ui.test.hasAnyDescendant(hasText(title))

    private fun hub(
        rows: KindRows,
        byKind: Boolean = true,
        opened: MutableList<Pair<String, KindRow?>> = mutableListOf(),
        lists: List<Watchlist> = listOf(list),
    ) {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                HubContent(
                    s = WatchlistUiState.Ready(
                        lists = lists,
                        shelves = lists.associate { it.id to movies + shows },
                        baseUrl = "https://media.test",
                        streamToken = "t",
                        byKind = byKind,
                        kindRows = lists.associate { it.id to rows },
                    ),
                    savedItems = emptySet(),
                    onItemSelected = {},
                    onOpenList = { id, row -> opened += id to row },
                    onNewList = {},
                    newListFocus = remember { FocusRequester() },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `on, a list has its heading, the sum of its rows, and a row per kind`() {
        hub(KindRows(movies, shows))
        compose.onNode(hasText("Watchlist")).assertIsDisplayed()
        compose.onNode(hasText("· 8")).assertIsDisplayed()
        compose.onNode(hasText("movies · 4")).assertIsDisplayed()
        compose.onNode(hasText("shows · 4")).assertExists()
    }

    @Test
    fun `off, the hub is as it was`() {
        hub(KindRows(movies, shows), byKind = false)
        compose.onNode(hasText("· 9")).assertIsDisplayed()
        compose.onNode(hasText("movies · 4")).assertDoesNotExist()
        compose.onNode(hasText("shows · 4")).assertDoesNotExist()
    }

    @Test
    fun `a row with nothing in it is left out`() {
        hub(KindRows(movies, emptyList()))
        compose.onNode(hasText("movies · 4")).assertExists()
        compose.onNode(hasText("shows", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a list with nothing in it shows the hint, and no rows`() {
        hub(KindRows())
        compose.onNode(hasText("Add titles with the bookmark", substring = true)).assertIsDisplayed()
        compose.onNode(hasText("movies", substring = true)).assertDoesNotExist()
    }

    @Test
    fun `a row's see all opens the list at that row`() {
        val opened = mutableListOf<Pair<String, KindRow?>>()
        hub(KindRows(movies.take(1), shows.take(1)), opened = opened)
        val seeAll = compose.onAllNodes(hasClickAction() and hasAnyDescendantOrSelfSeeAll())
        seeAll[1].performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("l1" to KindRow.Shows), opened)
    }

    private fun hasAnyDescendantOrSelfSeeAll(): SemanticsMatcher =
        hasText("see all", substring = true, ignoreCase = true) or
            androidx.compose.ui.test.hasAnyDescendant(hasText("see all", substring = true, ignoreCase = true))

    @Test
    fun `each row keeps the card it was left on, and its label is no stop`() {
        hub(KindRows(movies, shows))
        focus("Movie 3")
        // DOWN goes straight to the shows row: past its label.
        press(Key.DirectionDown)
        assertFocused("Show")
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        assertFocused("Movie 3")
    }

    @Test
    fun `a row's see all opens the grid at that row's heading, its first card focused`() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                MoreContent(
                    list = list,
                    items = movies + shows,
                    loading = false,
                    byKind = true,
                    openRow = KindRow.Shows,
                    baseUrl = "https://media.test",
                    streamToken = "t",
                    savedItems = emptySet(),
                    onItemSelected = {},
                    onBack = {},
                    onRename = {},
                    onDelete = {},
                    renameFocus = remember { FocusRequester() },
                    deleteFocus = remember { FocusRequester() },
                )
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(hasText("shows · 4")).assertIsDisplayed()
        assertFocused("Show 1")
    }

    @Test
    fun `off, a whole list has no headings`() {
        compose.setContent {
            ChinoTvTheme {
                MoreContent(
                    list = list,
                    items = movies + shows,
                    loading = false,
                    byKind = false,
                    openRow = null,
                    baseUrl = "https://media.test",
                    streamToken = "t",
                    savedItems = emptySet(),
                    onItemSelected = {},
                    onBack = {},
                    onRename = {},
                    onDelete = {},
                    renameFocus = remember { FocusRequester() },
                    deleteFocus = remember { FocusRequester() },
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("· 9")).assertIsDisplayed()
        compose.onNode(hasText("movies", substring = true)).assertDoesNotExist()
    }
}
