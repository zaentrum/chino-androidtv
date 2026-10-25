package cloud.nalet.chino.tv.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import androidx.tv.material3.Text
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ContinueWatchingResponse
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.api.SeriesEpisodes
import cloud.nalet.chino.tv.data.api.ProgressResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.CastMember
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The title page's rows with the remote: DOWN from a starring card goes down
 * to the next row - the crew, else the episodes - even beside a taller card,
 * never sideways onto it; and BACK from what a card opened (a person's page,
 * another title's) gives the focus back to that card, its row scrolled
 * exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TitleShelvesTest {
    @get:Rule val compose = createComposeRule()

    // Twelve actors, every other one with a long name and a long part -
    // a taller card - beside one with a short name and no part; a director.
    private val cast = (1..12).map { n ->
        if (n % 2 == 0) {
            CastMember(name = "Player Number $n With A Long Name", role = "actor", personId = "p$n", character = "The keeper of the far lighthouse, and the boatman $n")
        } else {
            CastMember(name = "Player $n", role = "actor", personId = "p$n")
        }
    } + CastMember(name = "Ben Ash", role = "director", personId = "d1")

    private val movie = Item(id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021, cast = cast)
    private val similar = (1..10).map { Item(id = "o$it", title = "Harbour Light $it", kind = "movie", year = 2000 + it) }

    // A series with its actors and no crew, opened on its third episode.
    private val series = Item(id = "s1", title = "Paper Station", kind = "series", cast = cast.filter { it.role == "actor" })
    private val episode = Item(id = "e3", title = "Part 3", kind = "episode", parentId = "s1", seasonNumber = 1, episodeNumber = 3)
    private val episodes = (1..4).map { n ->
        Episode(id = "e$n", title = "Part $n", seasonNumber = 1, episodeNumber = n, parentId = "s1", durationMs = 1_500_000L)
    }

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getItem" -> when (args[0]) {
                "m1" -> movie
                "e3" -> episode
                else -> series
            }
            "getProgress" -> ProgressResponse(positionSec = 0)
            "similar" -> ItemsPage(if (args[0] == "m1") similar else emptyList())
            "seriesEpisodes" -> SeriesEpisodes(listOf(Season(1, episodes)))
            "continueWatching" -> ContinueWatchingResponse(emptyList())
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    /** The page as the shell hosts it, its saved state kept while what a
     *  card opened is on top, as the navigation keeps it. */
    private var opened by mutableStateOf<String?>(null)

    private fun show(id: String = "m1"): DetailViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = id,
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                val holder = rememberSaveableStateHolder()
                SafeAreaScreen {
                    if (opened == null) {
                        holder.SaveableStateProvider("title") {
                            DetailScreen(
                                viewModel = vm,
                                onPlay = { _, _ -> },
                                onPersonSelected = { opened = it },
                                onSimilarSelected = { opened = it },
                            )
                        }
                    } else {
                        Text("what the card opened", modifier = Modifier.focusable())
                    }
                }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        settle()
        return vm
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        settle()
    }

    private fun card(text: String) = compose.onNode(hasText(text, substring = true) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))

    private fun focusCard(text: String) {
        card(text).performSemanticsAction(SemanticsActions.RequestFocus)
        settle()
    }

    @Test
    fun `DOWN from a starring card goes to the next row, even beside a taller card`() {
        show()
        for (n in listOf(1, 3)) {
            focusCard("Player $n")
            compose.onNode(isFocused()).assert(hasText("Player $n"))
            press(Key.DirectionDown)
            compose.onNode(isFocused()).assert(hasText("Ben Ash"))
        }
        // From a taller card the same.
        focusCard("Player Number 2 ")
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Ben Ash"))
        // And back UP into the starring row.
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("Player", substring = true))
    }

    @Test
    fun `BACK from a person's page gives the focus back to the starring card, its row scrolled exactly as it was`() {
        show()
        // Along the starring row with the remote, to its ninth card.
        focusCard("Player 1")
        repeat(8) { press(Key.DirectionRight) }
        compose.onNode(isFocused()).assert(hasText("Player 9"))
        val before = card("Player 9").fetchSemanticsNode().boundsInRoot
        val neighbour = card("Player Number 8 ").fetchSemanticsNode().boundsInRoot
        press(Key.DirectionCenter)
        assertEquals("p9", opened)
        // BACK.
        compose.runOnIdle { opened = null }
        settle()
        compose.onNode(isFocused()).assert(hasText("Player 9"))
        assertEquals(before, card("Player 9").fetchSemanticsNode().boundsInRoot)
        assertEquals(neighbour, card("Player Number 8 ").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun `BACK from another title's page gives the focus back to its card in more like this, the row as it was`() {
        show()
        focusCard("Harbour Light 1")
        repeat(5) { press(Key.DirectionRight) }
        compose.onNode(isFocused()).assert(hasText("Harbour Light 6"))
        val before = card("Harbour Light 6").fetchSemanticsNode().boundsInRoot
        press(Key.DirectionCenter)
        assertEquals("o6", opened)
        compose.runOnIdle { opened = null }
        settle()
        compose.onNode(isFocused()).assert(hasText("Harbour Light 6"))
        assertEquals(before, card("Harbour Light 6").fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun `on a series without crew, DOWN from a starring card goes to its episodes, and BACK from a person's page to the card, not the episode it was opened on`() {
        show("e3")
        // Opened on S01E03: the focus on its row.
        compose.onNode(isFocused()).assert(hasText("Part 3", substring = true))
        focusCard("Player 3")
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Season 1", substring = true))
        focusCard("Player 5")
        press(Key.DirectionCenter)
        assertEquals("p5", opened)
        compose.runOnIdle { opened = null }
        settle()
        compose.onNode(isFocused()).assert(hasText("Player 5"))
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
