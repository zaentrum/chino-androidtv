package cloud.nalet.chino.tv.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
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
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.model.Subtitle
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
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
 * A title's page under its actions: the overview ends in "…" where it is
 * cut, never stopped mid-sentence as if that were its end; the subtitle
 * languages hide none - they wrap, and past their lines they say how many
 * more, "+12 more", never a name cut by a "…".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
// The app's faces measured as on a TV: lines wrap where they do there.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TitleFooterTest {
    @get:Rule val compose = createComposeRule()

    private var subtitles: List<Subtitle> = emptyList()
    private var overview = "A harbour town waits for its ferry."

    private fun series() = Item(
        id = "s1", title = "Paper Station", kind = "series", year = 2019, rating = 7.4,
        genres = listOf("Drama"), overview = overview, subtitles = subtitles,
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

    @Test
    fun `a cut overview ends in an ellipsis`() {
        overview = List(40) { "The harbour town waits for its ferry, and the lamps are lit along the pier." }.joinToString(" ")
        show("s1")
        val layout = textLayout(overview)
        assertEquals(TextOverflow.Ellipsis, layout.layoutInput.overflow)
        assertEquals(OVERVIEW_LINES, layout.lineCount)
        assertTrue("the last line ends in …", layout.isLineEllipsized(OVERVIEW_LINES - 1))
    }

    @Test
    fun `every subtitle language is shown or counted, never cut`() {
        // Forty languages: more than three lines hold.
        val codes = listOf(
            "ar", "bg", "ca", "cs", "da", "de", "el", "en", "es", "et", "fa", "fi", "fr", "he", "hi", "hr", "hu", "id",
            "is", "it", "ja", "ko", "lt", "lv", "ms", "nl", "no", "pl", "pt", "ro", "ru", "sk", "sl", "sr", "sv", "th",
            "tr", "uk", "vi", "zh",
        )
        subtitles = codes.map { Subtitle(lang = it) }
        show("s1")
        val names = subtitleNameList(subtitles)
        assertEquals(40, names.size)
        val node = compose.onNode(hasText("more", substring = true) and hasText(names.first(), substring = true))
            .fetchSemanticsNode()
        val text = node.config[SemanticsProperties.Text].joinToString("") { it.text }
        val more = Regex(""", \+(\d+) more$""").find(text)
        requireNotNull(more) { "no count in \"$text\"" }
        val listed = text.removeSuffix(more.value).split(", ")
        // Every language is in the list or in its count, in order.
        assertEquals(names.take(listed.size), listed)
        assertEquals(names.size, listed.size + more.groupValues[1].toInt())
        val layout = textLayout(text)
        assertTrue(layout.lineCount <= SUBTITLE_LINES)
        assertFalse("nothing cut by an ellipsis", (0 until layout.lineCount).any { layout.isLineEllipsized(it) })
    }

    @Test
    fun `a few subtitle languages are listed whole`() {
        subtitles = listOf(Subtitle(lang = "de"), Subtitle(lang = "en"), Subtitle(lang = "zh"))
        show("s1")
        compose.onNode(hasText("Chinese, English, German")).assertExists()
    }

    private fun textLayout(text: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
