package cloud.nalet.chino.tv.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.library.BrowseFilter
import cloud.nalet.chino.tv.ui.library.LibraryContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The safe area sets where resting content starts, not a clip (the design
 * system's tv focus): Home scrolls out to the screen's end and bottom edges
 * and its shelves run to the screen's edge, while the card the remote moves
 * to - its ring at its growth - is brought into view inside the safe area.
 * In the TV's dp: 960 × 540, 2 px each; the safe area 48 dp at the sides,
 * 27 at the top and bottom.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class PageEdgesTest {
    @get:Rule val compose = createComposeRule()

    private val movies = (1..14).map { Item(id = "m$it", title = "Harbour Light $it", kind = "movie", year = 2000 + it) }
    private val shows = (1..14).map { Item(id = "s$it", title = "Paper Station $it", kind = "series", year = 2000 + it) }

    private fun dp(px: Rect) = Rect(px.left / 2f, px.top / 2f, px.right / 2f, px.bottom / 2f)

    private fun home() {
        tv()
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen {
                    ChinoPage {
                        LibraryContent(
                            movies = movies, series = shows, continueWatching = emptyList(),
                            watchlist = emptySet(), baseUrl = "https://media.test", streamToken = "t",
                            genres = emptyList(), filter = BrowseFilter(), heroItem = movies.first(), heroCount = 1,
                            topRated = movies.reversed(),
                            onItemSelected = {}, onHeroPlay = {}, onSearch = {}, onSettings = {}, onWatchlist = {},
                            onAccountClick = {}, onMoviesNav = {}, onSeriesNav = {}, noticesBell = null,
                            activeAccount = null, onFilterChange = {}, onZap = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    /** The focused card's reach: its bounds - grown, as the screen shows
     *  them once its growth has settled - and its ring outside a 2 dp gap. */
    private fun focusedReach(): Rect {
        val b = dp(compose.onNode(isFocused()).fetchSemanticsNode().boundsInRoot)
        val ring = 2f + 3f
        return Rect(b.left - ring, b.top - ring, b.right + ring, b.bottom + ring)
    }

    @Test
    fun `Home's shelves run to the screen's edge, the page to its bottom, the focused card inside the safe area`() {
        home()
        // The shelves' rows reach the screen's right edge, past the safe
        // line at 912 dp; so does the page, down to the bottom edge.
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }
            // The ones on the screen; those further down are scrolled out.
            .filter { it.height > 0f }
        assertTrue(rows.isNotEmpty())
        for (row in rows) assertTrue("a shelf ends at ${row.right} dp", row.right >= 959.5f)
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the page ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        // A card at rest is drawn across the safe line, to the screen's edge.
        val shelf = rows.first()
        val cards = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }
            .filter { it.height > 0f && it.top >= shelf.top }
        assertTrue("a card crosses the safe line: $cards", cards.any { it.left < 912f && it.right > 912f })
        // The remote along the first shelf: down from the hero, then right
        // to its last card, then down the shelves - the focused card and its
        // ring always inside the safe area.
        press(Key.DirectionDown)
        repeat(movies.size + 1) {
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 912.5f && r.bottom <= 513.5f && r.left >= 47.5f)
            press(Key.DirectionRight)
        }
        repeat(3) {
            press(Key.DirectionDown)
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 912.5f && r.bottom <= 513.5f && r.top >= 26.5f)
        }
    }

    @Test
    fun `the title page's rows run to the screen's edge, the focused card inside the safe area`() {
        tv()
        val movie = Item(
            id = "m0", title = "Quiet Orchard", kind = "movie", year = 2021,
            cast = (1..12).map { cloud.nalet.chino.tv.data.model.CastMember(name = "Player $it", role = "actor", character = "Part $it") },
        )
        val api = java.lang.reflect.Proxy.newProxyInstance(
            cloud.nalet.chino.tv.data.api.ChinoApi::class.java.classLoader, arrayOf(cloud.nalet.chino.tv.data.api.ChinoApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getItem" -> movie
                "getProgress" -> cloud.nalet.chino.tv.data.api.ProgressResponse(positionSec = 0)
                "similar" -> cloud.nalet.chino.tv.data.model.ItemsPage(movies)
                "mintStreamToken" -> cloud.nalet.chino.tv.data.api.StreamTokenResponse(token = "t")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as cloud.nalet.chino.tv.data.api.ChinoApi
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = cloud.nalet.chino.tv.ui.detail.DetailViewModel(
            api = api,
            streamTokens = cloud.nalet.chino.tv.data.auth.StreamTokenManager(api),
            userFlags = cloud.nalet.chino.tv.data.UserFlagsRepository(api),
            telemetry = cloud.nalet.chino.tv.data.telemetry.Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = "m0",
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                SafeAreaScreen { cloud.nalet.chino.tv.ui.detail.DetailScreen(viewModel = vm, onPlay = { _, _ -> }) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is cloud.nalet.chino.tv.ui.detail.DetailUiState.Ready }
        compose.waitForIdle()
        // Down to "more like this", the page's last row, and along it.
        compose.onNode(androidx.compose.ui.test.hasText("Harbour Light 1") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }.filter { it.height > 0f }
        assertTrue(rows.isNotEmpty())
        for (row in rows) assertTrue("a row ends at ${row.right} dp", row.right >= 959.5f)
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the page ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        repeat(movies.size) {
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 912.5f && r.bottom <= 513.5f && r.left >= 47.5f)
            press(Key.DirectionRight)
        }
    }

    /** A TV: the platform would bring a focused item into view at its pivot. */
    private fun tv() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(ctx.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK, true)
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
