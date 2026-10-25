package cloud.nalet.chino.tv.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.ProgressSaves
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.ContinueWatchingResponse
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.NextEpisodeResponse
import cloud.nalet.chino.tv.data.api.ProgressResponse
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.api.SeriesEpisodes
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.CastMember
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A title's page with the remote: the focus coming back up to its first row
 * - play and the others - scrolls the page back to its top, its name, year
 * and meta and the poster's top shown again, never the row at the screen's
 * top edge with the title scrolled away; UP there shows the top too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
// The app's faces measured as on a TV: lines wrap where they do there.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TitleTopTest {
    @get:Rule val compose = createComposeRule()

    private val cast = listOf(
        CastMember(name = "Ada Lind", character = "The keeper", order = 0),
        CastMember(name = "Ben Ora", character = "The ferryman", order = 1),
        CastMember(name = "Cleo Marsh", role = "director"),
    )


    private fun series() = Item(
        id = "s1", title = "Paper Station", kind = "series", year = 2019, rating = 7.4,
        genres = listOf("Drama"), overview = "A harbour town waits for its ferry.", cast = cast,
    )

    private fun episode(n: Int) = Item(
        id = "e$n", title = "Part $n", kind = "episode", parentId = "s1", seasonNumber = 1, episodeNumber = n,
        durationMs = 1_500_000L,
    )

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getItem" -> if (args[0] == "s1") series() else episode((args[0] as String).removePrefix("e").toInt())
            "getProgress" -> ProgressResponse(positionSec = if (args[0] == "e3") 603 else 0)
            "similar" -> ItemsPage()
            "seriesEpisodes" -> SeriesEpisodes(
                listOf(
                    Season(
                        1,
                        (1..8).map { n ->
                            Episode(id = "e$n", title = "Part $n", seasonNumber = 1, episodeNumber = n, parentId = "s1", durationMs = 1_500_000L)
                        },
                    ),
                ),
            )
            "continueWatching" -> ContinueWatchingResponse(
                listOf(
                    ContinueWatchingItem(
                        id = "e3", title = "Part 3", positionSec = 603, durationSec = 1500,
                        seriesTitle = "Paper Station", seasonNumber = 1, episodeNumber = 3, parentId = "s1", type = "episode",
                    ),
                ),
            )
            "nextEpisode" -> NextEpisodeResponse()
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    /** The page of [id] as the shell shows it, in the safe area. */
    private fun show(id: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = id,
            playCaps = { throw IllegalStateException("no caps here") },
            progressSaves = ProgressSaves(),
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen { DetailScreen(viewModel = vm, onPlay = { _, _ -> }) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        settle()
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

    /** How far the page is scrolled down, in px: 0 at its top. */
    private fun scrolled(): Float =
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun focusedText(): String =
        compose.onNode(isFocused()).fetchSemanticsNode().config.getOrElse(SemanticsProperties.Text) { emptyList() }
            .joinToString(" ") { it.text }

    /** The node of [text] lies inside the screen, below its top edge. */
    private fun shown(text: String): Boolean {
        val b = compose.onNode(hasText(text)).fetchSemanticsNode().boundsInRoot
        return b.top >= 0f && b.bottom <= 1080f
    }

    /** The focus is on a control of the page's first row: play, start
     *  over, add to list, watched, liked. */
    private fun inFirstRow(): Boolean {
        val node = compose.onNode(isFocused()).fetchSemanticsNode()
        val names = node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        return focusedText().let { it.contains("resume S01E03") || it.contains("start over") } ||
            names.any { it in setOf("add to Watchlist", "in your Watchlist", "choose lists", "mark as watched", "watched", "like", "liked") }
    }

    @Test
    fun `back up from the episodes to the first row the page shows its top again`() {
        // Opened on S01E03, its continue-watching card: the page lands on
        // the episode, scrolled down to it.
        show("e3")
        assertTrue("opened on the episode", focusedText().contains("S01E03"))
        assertTrue("scrolled down to the episode", scrolled() > 0f)
        // UP, through the credits, back to the first row: wherever the
        // focus lands in it, the page is back at its top.
        var ups = 0
        while (!inFirstRow()) {
            press(Key.DirectionUp)
            assertTrue("never came back up to the first row", ++ups < 20)
        }
        assertEquals("the page at its top", 0f, scrolled())
        assertTrue(shown("Paper Station"))
        assertTrue(shown("2019"))
        // On up to play, and UP on play: nothing above it, the top shown.
        while (!focusedText().contains("resume S01E03")) {
            press(Key.DirectionUp)
            assertTrue("never came up to play", ++ups < 20)
        }
        assertEquals(0f, scrolled())
        press(Key.DirectionUp)
        assertTrue(focusedText().contains("resume S01E03"))
        assertEquals(0f, scrolled())
        assertTrue(shown("Paper Station"))
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
