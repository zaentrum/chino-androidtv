package cloud.nalet.chino.tv.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Home opens with the focus on the hero's play; the rail marks where you
 *  are, and only that. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
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

    /** The remote's input: a TV is in keyboard mode (Robolectric starts in
     *  touch mode). */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
