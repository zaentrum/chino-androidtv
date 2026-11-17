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
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.library.BrowseFilter
import cloud.nalet.chino.tv.ui.library.LibraryContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * No margin round the screen, as chino-web has none: Home, a title's page,
 * Movies and a person's page scroll out to the screen's right and bottom
 * edges and their rows run to the right edge; what rests sits a gutter in
 * from the rail's line, the top bar's and the screen's right edge, and the
 * card the remote moves to - its ring at its growth - is brought into view
 * whole, a gutter clear of the right and bottom edges. In the TV's dp: 960 ×
 * 540, 2 px each; the rail 60 wide, the top bar 48 high, the gutter 12.
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
                ShellScreen {
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
    fun `Home's shelves run to the screen's edge, the page to its bottom, the focused card a gutter clear of them`() {
        home()
        // The shelves' rows reach the screen's right edge, past the gutter
        // at 948 dp; so does the page, down to the bottom edge.
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }
            // The ones on the screen; those further down are scrolled out.
            .filter { it.height > 0f }
        assertTrue(rows.isNotEmpty())
        for (row in rows) assertTrue("a shelf ends at ${row.right} dp", row.right >= 959.5f)
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the page ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        // A card at rest is drawn to the screen's edge, the first one a
        // gutter from the rail's line.
        val shelf = rows.first()
        val cards = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }
            // The shelves', right of the rail's line.
            .filter { it.height > 0f && it.top >= shelf.top && it.left >= 60f }
        assertTrue("a card drawn to the screen's edge: $cards", cards.any { it.left < 960f && it.right >= 959.5f })
        assertEquals(72f, cards.minOf { it.left }, 0.5f)
        // The remote along the first shelf: down from the hero, then right
        // to its last card, then down the shelves - the focused card and its
        // ring always whole, past the rail's line, a gutter clear of the
        // right and bottom edges.
        press(Key.DirectionDown)
        repeat(movies.size + 1) {
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 948.5f && r.bottom <= 528.5f && r.left >= 60f)
            press(Key.DirectionRight)
        }
        repeat(3) {
            press(Key.DirectionDown)
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 948.5f && r.bottom <= 528.5f && r.top >= 47.5f)
        }
    }

    @Test
    fun `the title page's rows run to the screen's edge, the focused card a gutter clear of it`() {
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
            streamTokens = cloud.nalet.chino.tv.data.auth.StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            userFlags = cloud.nalet.chino.tv.data.UserFlagsRepository(api) { ActiveChoice("viewer", 0) },
            openedFor = { "viewer" },
            telemetry = cloud.nalet.chino.tv.data.telemetry.Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = "m0",
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen { cloud.nalet.chino.tv.ui.detail.DetailScreen(viewModel = vm, onPlay = { _, _ -> }) }
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
            assertTrue("focused card reaches $r", r.right <= 948.5f && r.bottom <= 528.5f && r.left >= 60f)
            press(Key.DirectionRight)
        }
    }

    /** The server of the pages below: 30 titles, a few genres, a person. */
    private fun server(): cloud.nalet.chino.tv.data.api.ChinoApi {
        val many = (1..30).map { Item(id = "m$it", title = "Harbour Light $it", kind = "movie", year = 1990 + it) }
        return java.lang.reflect.Proxy.newProxyInstance(
            cloud.nalet.chino.tv.data.api.ChinoApi::class.java.classLoader, arrayOf(cloud.nalet.chino.tv.data.api.ChinoApi::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "listItems" -> cloud.nalet.chino.tv.data.model.ItemsPage(many)
                "listGenres" -> cloud.nalet.chino.tv.data.api.GenresResponse(
                    listOf("Action", "Action & Adventure", "Adventure", "Animation", "Comedy", "Crime", "Documentary", "Drama", "Family", "Fantasy"),
                )
                "getPerson" -> cloud.nalet.chino.tv.data.api.PersonDetail(id = "p1", name = "Alex Marlow", items = many)
                "mintStreamToken" -> cloud.nalet.chino.tv.data.api.StreamTokenResponse(token = "t")
                "hashCode" -> 0
                "equals" -> false
                "toString" -> "the server"
                else -> throw java.io.IOException("not asked here")
            }
        } as cloud.nalet.chino.tv.data.api.ChinoApi
    }

    /** Movies on a TV, its 30 titles loaded. */
    private fun movies() {
        tv()
        val api = server()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = cloud.nalet.chino.tv.ui.browse.BrowseViewModel(
            api = api,
            streamTokens = cloud.nalet.chino.tv.data.auth.StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            telemetry = cloud.nalet.chino.tv.data.telemetry.Telemetry(ctx) { null },
            userFlags = cloud.nalet.chino.tv.data.UserFlagsRepository(api) { ActiveChoice("viewer", 0) },
            openedFor = { "viewer" },
            baseUrl = "https://media.test/api",
            type = "movie",
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
                    cloud.nalet.chino.tv.ui.browse.BrowseScreen(
                        viewModel = vm, pageTitle = "Movies", type = "movie", onItemSelected = {},
                        onHomeNav = {}, onMoviesNav = {}, onSeriesNav = {}, onSearch = {}, onSettings = {},
                        onWatchlist = {}, onZapNav = {}, onAccountClick = {},
                    )
                }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is cloud.nalet.chino.tv.ui.browse.BrowseUiState.Ready }
        compose.waitForIdle()
    }

    /** The card that reads [title], in the TV's dp. */
    private fun cardAt(title: String): Rect = dp(
        compose.onNode(androidx.compose.ui.test.hasText(title) and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .fetchSemanticsNode().boundsInRoot,
    )

    @Test
    fun `Movies scrolls to the screen's right and bottom edges and its chip rows run to the edge, resting a gutter in`() {
        movies()
        // The chip rows reach the screen's right edge; the grid reaches the
        // right and the bottom edges.
        val rows = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNodes().map { dp(it.boundsInRoot) }.filter { it.height > 0f }
        assertTrue("three chip rows: $rows", rows.size == 3)
        for (row in rows) assertTrue("a chip row ends at ${row.right} dp", row.right >= 959.5f)
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the grid ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        // At rest the page's title and its cards a gutter in: from the rail's
        // line, the top bar's and the screen's right edge.
        val heading = dp(compose.onNode(androidx.compose.ui.test.hasText("movies")).fetchSemanticsNode().boundsInRoot)
        assertEquals(72f, heading.left, 0.5f)
        assertEquals(60f, heading.top, 0.5f)
        assertEquals(72f, cardAt("Harbour Light 1").left, 0.5f)
        assertEquals(948f, cardAt("Harbour Light 6").right, 0.5f)
    }

    @Test
    fun `on a TV the card the remote moves to on Movies comes to rest at the pivot, whole and a gutter clear of the edges, down the grid`() {
        movies()
        // Before, the grid chased the TV's pivot a pixel to and fro for as
        // long as a card had the focus, and the page never came to rest.
        compose.onNode(androidx.compose.ui.test.hasText("Harbour Light 1") and SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))
            .performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        repeat(4) {
            val r = focusedReach()
            assertTrue("focused card reaches $r", r.right <= 948.5f && r.bottom <= 528.5f && r.top >= 47.5f && r.left >= 60f)
            press(Key.DirectionDown)
        }
    }

    @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
    @Test
    fun `a pivot less than a pixel away is where the item is, one farther is scrolled to`() {
        val pivot = object : androidx.compose.foundation.gestures.BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - 0.3f * containerSize
        }
        val settled = SettledBringIntoView(pivot)
        assertEquals(0f, settled.calculateScrollDistance(300.4f, 100f, 1000f), 0f)
        assertEquals(0f, settled.calculateScrollDistance(299.2f, 100f, 1000f), 0f)
        assertEquals(12.5f, settled.calculateScrollDistance(312.5f, 100f, 1000f), 0.001f)
        assertEquals(-40f, settled.calculateScrollDistance(260f, 100f, 1000f), 0.001f)
        assertEquals(pivot.scrollAnimationSpec, settled.scrollAnimationSpec)
    }

    @Test
    fun `a person's titles scroll to the screen's bottom edge, resting a gutter in`() {
        tv()
        val api = server()
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = cloud.nalet.chino.tv.ui.person.PersonViewModel(
            api = api,
            streamTokens = cloud.nalet.chino.tv.data.auth.StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            telemetry = cloud.nalet.chino.tv.data.telemetry.Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            personId = "p1",
            userFlags = cloud.nalet.chino.tv.data.UserFlagsRepository(api) { ActiveChoice("viewer", 0) },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen { cloud.nalet.chino.tv.ui.person.PersonScreen(viewModel = vm, onItemSelected = {}, onZapNav = {}) }
            }
        }
        compose.waitUntil(5_000) { vm.state.value is cloud.nalet.chino.tv.ui.person.PersonUiState.Ready }
        compose.waitForIdle()
        val page = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot.let(::dp)
        assertTrue("the grid ends at ${page.right}, ${page.bottom} dp", page.right >= 959.5f && page.bottom >= 539.5f)
        assertEquals(72f, cardAt("Harbour Light 1").left, 0.5f)
        assertEquals(948f, cardAt("Harbour Light 6").right, 0.5f)
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
