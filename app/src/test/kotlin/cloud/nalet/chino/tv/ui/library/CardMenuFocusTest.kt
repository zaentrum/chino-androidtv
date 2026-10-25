package cloud.nalet.chino.tv.ui.library

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A card's menu with the remote: the first BACK closes it - the scrim behind
 * it is no stop of the focus - and closing it gives the focus back to the
 * card that opened it, never to the rail's Home.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class CardMenuFocusTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private val hero = Item(id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021)
    private val partway = ContinueWatchingItem(id = "m3", title = "Lanterns", positionSec = 600, durationSec = 5400, type = "movie")

    /** Home as its view model keeps it: marking a card as watched takes it
     *  off the unwatched shelves, removing one takes it off continue watching. */
    private var movies by mutableStateOf(
        listOf(hero, Item(id = "m2", title = "Paper Station", kind = "movie", year = 2019), Item(id = "m4", title = "Salt Road", kind = "movie", year = 2018)),
    )
    private var continueWatching by mutableStateOf(listOf(partway))

    private fun home() {
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen {
                    LibraryContent(
                        movies = movies,
                        series = emptyList(), continueWatching = continueWatching,
                        watchlist = emptySet(), baseUrl = "https://media.test", streamToken = "t",
                        genres = emptyList(), filter = BrowseFilter(), heroItem = hero, heroCount = 1,
                        onItemSelected = {}, onHeroPlay = {}, onSearch = {}, onSettings = {}, onWatchlist = {},
                        onAccountClick = {}, onMoviesNav = {}, onSeriesNav = {}, noticesBell = null,
                        activeAccount = null, onFilterChange = {}, onZap = {},
                        onRemoveFromContinueWatching = { id -> continueWatching = continueWatching.filterNot { it.id == id } },
                        onToggleWatched = { id, watched -> if (watched) movies = movies.filterNot { it.id == id } },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** The remote's BACK, as a TV sends it: down and up, through the
     *  activity - the focused item first, then the screen's BACK. */
    private fun back() {
        compose.runOnUiThread {
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            compose.activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun card(title: String) = compose.onNode(hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))

    private fun openMenu(title: String) {
        card(title).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        card(title).performSemanticsAction(SemanticsActions.OnLongClick)
        settle()
    }

    @Test
    fun `the first BACK closes a card's menu and the focus is back on its card`() {
        home()
        openMenu("Lanterns")
        compose.onNode(hasText(REMOVE_FROM_CONTINUE_WATCHING)).assertExists()
        compose.onNode(isFocused()).assert(hasText("resume"))
        back()
        compose.onNode(hasText(REMOVE_FROM_CONTINUE_WATCHING)).assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Lanterns"))
        // A shelf's card the same way.
        openMenu("Paper Station")
        compose.onNode(hasText("mark as watched")).assertExists()
        back()
        compose.onNode(hasText("mark as watched")).assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Paper Station"))
    }

    @Test
    fun `the remote stays in the menu while it is open`() {
        home()
        openMenu("Lanterns")
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("resume"))
        repeat(4) { press(Key.DirectionDown) }
        compose.onNode(isFocused()).assert(hasText(REMOVE_FROM_CONTINUE_WATCHING))
    }

    @Test
    fun `a pick that takes the card off its shelf gives the focus to the card beside it`() {
        home()
        openMenu("Paper Station")
        compose.onNode(hasText("mark as watched")).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        settle()
        compose.onNode(hasText("Paper Station") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)).assertDoesNotExist()
        compose.onNode(isFocused()).assert(hasText("Salt Road"))
        // The only card of continue watching removed: the hero's play, never
        // the rail's Home.
        openMenu("Lanterns")
        compose.onNode(hasText(REMOVE_FROM_CONTINUE_WATCHING)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        settle()
        compose.onNode(isFocused()).assert(hasText("play"))
    }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
