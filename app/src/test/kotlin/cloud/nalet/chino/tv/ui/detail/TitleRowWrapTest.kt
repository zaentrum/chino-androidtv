package cloud.nalet.chino.tv.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
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
import cloud.nalet.chino.tv.data.model.Trailer
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoFocus
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.SafeAreaScreen
import cloud.nalet.chino.tv.ui.theme.UI_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The title row where its controls do not fit on one line (docs/chino, title
 * actions: the row): "resume 1:40:05", start over and trailer before add to
 * list, watched and liked. The row wraps, a control as a whole - each keeps
 * its full width, none cut or squeezed to nothing - and every control stands
 * the control gap from the one beside it and from the line above; the remote's
 * RIGHT and LEFT follow the row's order across the wrap. The page as the
 * shell hosts it, in the safe area, on a 1920 × 1080 TV, its text measured in
 * the app's own faces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class TitleRowWrapTest {
    @get:Rule val compose = createComposeRule()

    private val trailer = listOf(Trailer(url = "https://www.youtube.com/watch?v=abc123", site = "YouTube"))

    private val movie = Item(
        id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021, durationMs = 7_200_000L,
        genres = listOf("Drama", "Mystery"), overview = "An orchard keeper counts the trees each spring.",
        trailers = trailer,
    )

    private val series = Item(id = "s1", title = "Paper Station", kind = "series", genres = listOf("Drama"), trailers = trailer)

    /** The server, as far as the title page asks it: the movie saved at
     *  1:40:05, the series at S01E03's 10:03. */
    private fun api(item: Item) = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getItem" -> item
            "getProgress" -> ProgressResponse(positionSec = if (item.kind == "movie") 6005 else 0)
            "similar" -> ItemsPage()
            "seriesEpisodes" -> SeriesEpisodes(
                listOf(
                    Season(
                        1,
                        (1..4).map { n ->
                            Episode(
                                id = "e$n", title = "Part $n", seasonNumber = 1, episodeNumber = n, parentId = "s1",
                                durationMs = 1_500_000L, watchedAt = if (n < 3) "2026-10-01T20:00:00Z" else null,
                            )
                        },
                    ),
                ),
            )
            "continueWatching" -> ContinueWatchingResponse(
                listOf(
                    ContinueWatchingItem(
                        id = "e3", title = "Part 3", positionSec = 603, durationSec = 1500, seriesTitle = "Paper Station",
                        seasonNumber = 1, episodeNumber = 3, parentId = "s1", type = "episode",
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

    private fun show(item: Item) {
        val api = api(item)
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = item.id,
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen { DetailScreen(viewModel = vm, onPlay = { _, _ -> }) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        compose.waitForIdle()
    }

    /** A length in the TV's dp: 2 px each on 1920 × 1080. */
    private fun tv(px: Float) = px / 2f

    /** The title row's control: the first on the page (a series' episodes
     *  below have their own watched and add to list). */
    private fun bounds(matcher: SemanticsMatcher): Rect {
        val px = compose.onAllNodes(matcher and hasClickAction()).onFirst().fetchSemanticsNode().boundsInRoot
        return Rect(tv(px.left), tv(px.top), tv(px.right), tv(px.bottom))
    }

    /** The label of a button with text, as laid out inside it. */
    private fun label(text: String): Rect {
        val px = compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return Rect(tv(px.left), tv(px.top), tv(px.right), tv(px.bottom))
    }

    /** The control gap and a control's full height, in the TV's dp. */
    private val gap = ChinoFocus.ControlGap.value * UI_SCALE
    private val height = TitleActionLook.Size.value * UI_SCALE

    private fun assertWrapsWhole(primary: String) {
        val texts = listOf(primary, "start over", "trailer")
        val icons = listOf("add to Watchlist", "choose lists", "mark as watched", "like")
        // Every text button keeps its label whole, on one line, inside it.
        for (t in texts) {
            val b = bounds(hasText(t))
            val l = label(t)
            assertTrue("$t: its label $l outside the button $b", l.left >= b.left - 0.01f && l.right <= b.right + 0.01f)
            assertTrue("$t: its label $l on more than one line in $b", l.top >= b.top - 0.01f && l.bottom <= b.bottom + 0.01f)
            assertEquals("$t: the row's height", height, b.height, 0.5f)
        }
        // Every icon control at its full size: the bookmark, the "⌄", watched
        // and liked - none cut or squeezed to nothing.
        val widths = mapOf(
            "add to Watchlist" to TitleActionLook.Size,
            "choose lists" to TitleActionLook.CaretWidth,
            "mark as watched" to TitleActionLook.Size,
            "like" to TitleActionLook.Size,
        )
        for (name in icons) {
            val b = bounds(hasContentDescription(name))
            assertEquals("$name: its full width", widths.getValue(name).value * UI_SCALE, b.width, 0.5f)
            assertEquals("$name: the row's height", height, b.height, 0.5f)
        }
        // The controls in the row's order, add to list's two parts one
        // control: the control gap from the one before, beside it or - where
        // the row wraps - at the next line's start, the control gap below.
        val controls = texts.map { bounds(hasText(it)) } +
            bounds(hasContentDescription("add to Watchlist")).let { it.copy(right = bounds(hasContentDescription("choose lists")).right) } +
            listOf("mark as watched", "like").map { bounds(hasContentDescription(it)) }
        val start = controls.first().left
        var wrapped = false
        for ((a, b) in controls.zipWithNext()) {
            if (b.top == a.top) {
                assertEquals("the control gap between $a and $b", gap, b.left - a.right, 0.5f)
            } else {
                wrapped = true
                assertEquals("the next line's start", start, b.left, 0.5f)
                assertEquals("the control gap between the lines", gap, b.top - a.bottom, 0.5f)
            }
        }
        assertTrue("the row wrapped", wrapped)
    }

    @Test
    fun `a movie resumed past an hour, with start over and a trailer - the row wraps, every control whole`() {
        show(movie)
        assertWrapsWhole("resume 1:40:05")
    }

    @Test
    fun `a series resumed at S01E03 with a trailer - the row wraps, every control whole`() {
        show(series)
        assertWrapsWhole("resume S01E03")
    }

    @Test
    fun `RIGHT and LEFT follow the row's order across the wrap`() {
        show(movie)
        val order = listOf(
            hasText("resume 1:40:05"), hasText("start over"), hasText("trailer"),
            hasContentDescription("add to Watchlist"), hasContentDescription("choose lists"),
            hasContentDescription("mark as watched"), hasContentDescription("like"),
        ).map { it and hasClickAction() }
        compose.onNode(order.first()).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        for (next in order.drop(1)) {
            press(Key.DirectionRight)
            compose.onNode(next).assertIsFocused()
        }
        // Nothing past the end.
        press(Key.DirectionRight)
        compose.onNode(order.last()).assertIsFocused()
        for (before in order.dropLast(1).reversed()) {
            press(Key.DirectionLeft)
            compose.onNode(before).assertIsFocused()
        }
    }

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
