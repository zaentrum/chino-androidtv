package cloud.nalet.chino.tv.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.GenresResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.browse.BrowseScreen
import cloud.nalet.chino.tv.ui.browse.BrowseUiState
import cloud.nalet.chino.tv.ui.browse.BrowseViewModel
import cloud.nalet.chino.tv.ui.theme.ChinoPage
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The hint on a focused card says what holding OK does, whole, on every card
 * that has a menu: Home's continue watching, a movie's and a show's card on
 * its shelves, and a card of the Movies and the Shows grid, the narrowest.
 * It is in a line every card keeps for it, empty at rest, so the focused
 * card is as tall as the cards beside it and the rows under it stay where
 * they are. Measured with the app's fonts at the TV's 960 x 540 dp.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardHintTest {
    @get:Rule val compose = createComposeRule()

    private val movies = listOf(
        Item(id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021, rating = 7.4),
        // Neither a year nor a rating: its card as tall as the others all the same.
        Item(id = "m2", title = "Paper Station", kind = "movie"),
        Item(id = "m3", title = "Salt Road", kind = "movie", year = 2018, rating = 6.2),
        Item(id = "m4", title = "Low Tide", kind = "movie", year = 2016, rating = 7.0),
    )
    private val shows = listOf(
        Item(id = "s1", title = "Harbour Light", kind = "series", year = 2019, rating = 8.1),
        Item(id = "s2", title = "Lanterns", kind = "series", year = 2022, rating = 7.7, watchedAt = "2026-10-01T20:00:00Z"),
        Item(id = "s3", title = "North Pier", kind = "series", year = 2015, rating = 6.9),
    )
    private val continueWatching = listOf(
        ContinueWatchingItem(id = "c1", title = "Glass Valley", positionSec = 600, durationSec = 5400, type = "movie", year = 2020, rating = 7.1),
        ContinueWatchingItem(
            id = "e1", title = "The Harbour", positionSec = 300, durationSec = 2700, type = "episode",
            seriesTitle = "Tidewater", seasonNumber = 1, episodeNumber = 7,
        ),
        ContinueWatchingItem(id = "c2", title = "Ember Field", positionSec = 900, durationSec = 6000, type = "movie"),
    )

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    /** The card that reads [title]. */
    private fun card(title: String): SemanticsNodeInteraction =
        compose.onNode(hasText(title, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    private fun focus(title: String) {
        card(title).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    /** The hint on the focused card, one line and whole, in the words every
     *  card uses. */
    private fun assertHintWhole(where: String) {
        val hints = compose.onAllNodes(hasText(CARD_OPTIONS_HINT), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("$where: the focused card's hint, and no other", 1, hints.size)
        val results = mutableListOf<TextLayoutResult>()
        hints.single().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val text = results.single()
        assertEquals("$where: \"${text.layoutInput.text}\" on one line", 1, text.lineCount)
        assertFalse("$where: \"${text.layoutInput.text}\" cut", text.hasVisualOverflow)
        assertFalse("$where: \"${text.layoutInput.text}\" cut", text.isLineEllipsized(0))
    }

    /** Every shelf's height as laid out, scrolled into view or not. */
    private fun shelves(): List<Int> =
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { it.size.height }

    /** [title]'s card's height as laid out, in px: a focused one's growth is
     *  drawn, not laid out. */
    private fun height(title: String): Float = card(title).fetchSemanticsNode().size.height.toFloat()

    @Test
    fun `on Home every card's hint is whole, the focused card as tall as the others, the shelves where they were`() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
                    ChinoPage {
                        LibraryContent(
                            movies = movies, series = shows, continueWatching = continueWatching,
                            watchlist = emptySet(), baseUrl = "https://media.test", streamToken = "t",
                            genres = emptyList(), filter = BrowseFilter(), heroItem = null,
                            onItemSelected = {}, onHeroPlay = {}, onSearch = {}, onSettings = {}, onWatchlist = {},
                            onAccountClick = {}, onMoviesNav = {}, onSeriesNav = {}, noticesBell = null,
                            activeAccount = null, onFilterChange = {}, onZap = {},
                        )
                    }
                }
            }
        }
        settle()
        val atRest = shelves()
        // Continue watching (a movie, an episode, one without a year), a
        // movie's shelf (one without a year or a rating), a show's.
        for ((title, besides) in listOf(
            "Glass Valley" to "Tidewater", "Tidewater" to "Ember Field", "Ember Field" to "Glass Valley",
            "Quiet Orchard" to "Paper Station", "Paper Station" to "Salt Road",
            "Harbour Light" to "Lanterns", "Lanterns" to "North Pier",
        )) {
            focus(title)
            assertHintWhole(title)
            assertEquals("$title as tall as $besides", height(besides), height(title), 1f)
            assertEquals("the shelves' heights with $title focused", atRest, shelves())
        }
    }

    /** The Movies or the Shows page of [type], its titles loaded: a show,
     *  a watched one, one with a long title, one without a year or a rating
     *  on its first row. */
    private fun grid(type: String) {
        val items = (shows + movies.map { it.copy(id = "x" + it.id) }).map { it.copy(kind = type) }
        val api = java.lang.reflect.Proxy.newProxyInstance(
            ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listItems" -> ItemsPage(items)
                "listGenres" -> GenresResponse(listOf("Drama"))
                "mintStreamToken" -> StreamTokenResponse(token = "t")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as ChinoApi
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = BrowseViewModel(
            api = api, streamTokens = StreamTokenManager(api) { ActiveChoice("viewer", 0) }, telemetry = Telemetry(ctx) { null },
            userFlags = UserFlagsRepository(api) { ActiveChoice("viewer", 0) }, baseUrl = "https://media.test/api", type = type,
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
        compose.waitUntil(5_000) { vm.state.value is BrowseUiState.Ready }
        settle()
    }

    /** Along the grid's first row: each card's hint whole, the focused card
     *  as tall as the one beside it, a card at rest as tall as before. */
    private fun assertGridHints() {
        val restHeight = height("Lanterns")
        assertTrue(restHeight > 0f)
        for ((title, besides) in listOf(
            "Harbour Light" to "Lanterns", "Lanterns" to "North Pier", "Paper Station" to "Quiet Orchard",
        )) {
            focus(title)
            assertHintWhole(title)
            assertEquals("$title as tall as $besides", height(besides), height(title), 1f)
        }
        assertEquals("a card at rest as tall as before", restHeight, height("Lanterns"), 1f)
    }

    @Test
    fun `on Shows a grid card's hint is whole, the focused card as tall as the others`() {
        grid("series")
        assertGridHints()
    }

    @Test
    fun `on Movies a grid card's hint is whole, the focused card as tall as the others`() {
        grid("movie")
        assertGridHints()
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
