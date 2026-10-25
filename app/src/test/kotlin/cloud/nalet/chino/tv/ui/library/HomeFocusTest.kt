package cloud.nalet.chino.tv.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.Text
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home opens with the focus on the hero's play; the rail marks where you
 *  are, and only that. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class HomeFocusTest {
    @get:Rule val compose = createComposeRule()

    private val hero = Item(id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021, rating = 7.4)

    private val selected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)
    private val notSelected = SemanticsMatcher.expectValue(SemanticsProperties.Selected, false)

    @Test
    fun `home opens with the focus on the hero's play, and marks Home`() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                LibraryContent(
                    movies = listOf(hero), series = emptyList(), continueWatching = emptyList(),
                    watchlist = emptySet(), baseUrl = "https://media.test", streamToken = "t",
                    genres = emptyList(), filter = BrowseFilter(), heroItem = hero, heroCount = 1,
                    onItemSelected = {}, onHeroPlay = {}, onSearch = {}, onSettings = {}, onWatchlist = {},
                    onAccountClick = {}, onMoviesNav = {}, onSeriesNav = {}, noticesBell = null,
                    activeAccount = null, onFilterChange = {}, onZap = {},
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasText("play")).assertIsFocused()
        compose.onNode(hasContentDescription("Home")).assert(selected)
        compose.onNode(hasContentDescription("Settings")).assert(notSelected)
    }

    @Test
    fun `in Settings the rail marks Settings, not Home`() {
        compose.setContent {
            ChinoTvTheme {
                TvSideRail(activeType = null, settingsActive = true, onHome = {}, onMovies = {}, onSeries = {}, onSettings = {}, onZap = {}, onWatchlist = {})
            }
        }
        compose.onNode(hasContentDescription("Settings")).assert(selected)
        for (other in listOf("Home", "Movies", "Series", "Watchlist", "Zap")) {
            compose.onNode(hasContentDescription(other)).assert(notSelected)
        }
    }

    @Test
    fun `on a screen of no rail item, none is marked`() {
        compose.setContent {
            ChinoTvTheme {
                TvSideRail(activeType = "search", onHome = {}, onMovies = {}, onSeries = {}, onSettings = {}, onZap = {}, onWatchlist = {})
            }
        }
        for (item in listOf("Home", "Movies", "Series", "Watchlist", "Zap", "Settings")) {
            compose.onNode(hasContentDescription(item)).assert(notSelected)
        }
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Composable
    private fun Home(continueWatching: List<ContinueWatchingItem> = emptyList(), onItemSelected: (String) -> Unit = {}) {
        LibraryContent(
            movies = listOf(hero, Item(id = "m2", title = "Paper Station", kind = "movie", year = 2019)),
            series = emptyList(), continueWatching = continueWatching,
            watchlist = emptySet(), baseUrl = "https://media.test", streamToken = "t",
            genres = emptyList(), filter = BrowseFilter(), heroItem = hero, heroCount = 1,
            onItemSelected = onItemSelected, onHeroPlay = {}, onSearch = {}, onSettings = {}, onWatchlist = {},
            onAccountClick = {}, onMoviesNav = {}, onSeriesNav = {}, noticesBell = null,
            activeAccount = null, onFilterChange = {}, onZap = {},
        )
    }

    @Test
    fun `LEFT from the hero enters the rail at Home, the screen you are on`() {
        compose.setContent { ChinoTvTheme { Remote(); Home() } }
        compose.waitForIdle()
        compose.onNode(hasText("play")).assertIsFocused()
        press(Key.DirectionLeft)
        compose.onNode(hasContentDescription("Home")).assertIsFocused()
    }

    @Test
    fun `BACK from a title page gives the focus back to the card that opened it`() {
        // The shell's way: Home's saved state kept while a title's page is
        // on top, as the navigation keeps it.
        val partway = ContinueWatchingItem(id = "m3", title = "Lanterns", positionSec = 600, durationSec = 5400, type = "movie")
        var detail by mutableStateOf<String?>(null)
        compose.setContent {
            ChinoTvTheme {
                Remote()
                val holder = rememberSaveableStateHolder()
                val open = detail
                if (open == null) {
                    holder.SaveableStateProvider("home") { Home(continueWatching = listOf(partway), onItemSelected = { detail = it }) }
                } else {
                    Text("the title's page", modifier = Modifier.focusable())
                }
            }
        }
        compose.waitForIdle()
        // The continue-watching card, then OK: its title's page.
        val card = compose.onNode(hasText("Lanterns") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
        card.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals("m3", detail)
        // BACK: Home again, the card focused - not the hero's play.
        compose.runOnIdle { detail = null }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasText("Lanterns"))
    }

    /** The remote's input: a TV is in keyboard mode (Robolectric starts in
     *  touch mode). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
