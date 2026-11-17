package cloud.nalet.chino.tv.ui.watchlist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.AccountSettings
import cloud.nalet.chino.tv.data.LastKnownInMemory
import cloud.nalet.chino.tv.data.ListsByKind
import cloud.nalet.chino.tv.data.ServerFeatures
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.AccountSettingsChange
import cloud.nalet.chino.tv.data.api.AccountSettingsResponse
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ServerConfigResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.api.WatchlistDetail
import cloud.nalet.chino.tv.data.api.WatchlistsResponse
import cloud.nalet.chino.tv.data.auth.ActiveChoice
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.theme.ShellScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * The watchlist with Separate movies and shows on (section 8.8.1 of the
 * specification, change 8.C18): every list under its heading as the row of
 * its movies, then the row of its shows, each drawn from its own answer -
 * `?kind=movie`, then `?kind=series` - as it came, a row that couldn't load
 * failing alone. Off, or where the server doesn't fill lists by kind, every
 * list is one row, as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class WatchlistByKindTest {
    @get:Rule val compose = createComposeRule()

    private val titles = listOf(
        Item(id = "m1", title = "Harbour Light", kind = "movie", year = 2019),
        Item(id = "m2", title = "Paper Station", kind = "movie", year = 2021),
        Item(id = "m3", title = "Lantern Bay", kind = "movie", year = 2016),
        Item(id = "s1", title = "Quiet Orchard", kind = "series", year = 2020),
        Item(id = "x1", title = "Drifting Signal", kind = "movie", year = 2022),
        Item(id = "y1", title = "Copper Tide", kind = "movie", year = 2018),
    ).associateBy { it.id }

    private var lists = listOf(
        Watchlist(id = "l1", name = "Watchlist", itemCount = 3, isDefault = true),
        Watchlist(id = "l2", name = "Weekend", itemCount = 1),
    )

    /** listId → kind → the ids its answer holds, or null where it fails. */
    private val byKind = mutableMapOf(
        "l1" to mutableMapOf<String, List<String>?>("movie" to listOf("m1", "m2"), "series" to listOf("s1")),
        "l2" to mutableMapOf<String, List<String>?>("movie" to listOf("m3"), "series" to emptyList()),
    )

    /** Every list request, as it was made: "l1 movie", "l1 whole". */
    private val asked = CopyOnWriteArrayList<String>()

    /** The titles asked for. */
    private val itemsAsked = CopyOnWriteArrayList<String>()

    /** Holds the next answer to "l1 movie" until it is let go. */
    private var holdMovies = false
    private var heldMovies: Continuation<Any?>? = null

    /** The account's Separate movies and shows, as the server keeps it. */
    @Volatile private var serverOn = true

    /** Holds the next read of the setting until it is let go. */
    @Volatile private var holdRead = false
    @Volatile private var heldRead: Continuation<Any?>? = null

    /** Holds the next answer to the lists until it is let go. */
    @Volatile private var holdLists = false
    @Volatile private var heldLists: Continuation<Any?>? = null

    @Suppress("UNCHECKED_CAST")
    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getWatchlists" -> if (holdLists) {
                holdLists = false
                heldLists = args!!.last() as Continuation<Any?>
                COROUTINE_SUSPENDED
            } else {
                WatchlistsResponse(lists)
            }
            "accountSettings" -> if (holdRead) {
                holdRead = false
                heldRead = args!!.last() as Continuation<Any?>
                COROUTINE_SUSPENDED
            } else {
                AccountSettingsResponse(serverOn)
            }
            "changeAccountSettings" -> AccountSettingsResponse((args!![0] as AccountSettingsChange).watchlistByKind)
            "getWatchlistOfKind" -> {
                val listId = args!![0] as String
                val kind = args[1] as String
                asked += "$listId $kind"
                if (holdMovies && listId == "l1" && kind == "movie") {
                    holdMovies = false
                    heldMovies = args.last() as Continuation<Any?>
                    COROUTINE_SUSPENDED
                } else {
                    byKind.getValue(listId)[kind]?.let { WatchlistDetail(id = listId, name = listId, items = it) }
                        ?: unreachable(args)
                }
            }
            "getWatchlistDetail" -> {
                val listId = args!![0] as String
                asked += "$listId whole"
                val all = byKind.getValue(listId).values.flatMap { it.orEmpty() }.distinct()
                WatchlistDetail(id = listId, name = listId, items = all)
            }
            "getItem" -> {
                val id = args!![0] as String
                itemsAsked += id
                titles.getValue(id)
            }
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    /** Answers the request whose arguments are [args] with no answer, as a
     *  server that can't be reached: an IOException, as the client throws. */
    private fun unreachable(args: Array<Any?>): Any {
        @Suppress("UNCHECKED_CAST")
        (args.last() as Continuation<Any?>).resumeWith(Result.failure(java.io.IOException("the server can't be reached")))
        return COROUTINE_SUSPENDED
    }

    /** The cards' bookmarks read their lists from a server of their own: the
     *  lists' requests counted are the watchlist's. */
    private val bookmarksApi = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the bookmarks' server"
            else -> throw IllegalStateException("not asked here")
        }
    } as ChinoApi

    /** What /api/config's features answer, once they do. */
    private var features = CompletableDeferred(ServerConfigResponse.Features(watchlistKinds = true))

    /** The account's choice as last known on this TV. */
    private val lastKnown = LastKnownInMemory()

    private val accountSettings = AccountSettings(
        api, CoroutineScope(SupervisorJob() + Dispatchers.IO), activeChanges = { 0 }, lastKnown = lastKnown,
    ) { ActiveChoice("viewer", 0) }

    private val listsByKind by lazy { ListsByKind(ServerFeatures { features.await() }, accountSettings) }

    private lateinit var vm: WatchlistViewModel

    /** The watchlist open, the account's choice [on] on the server; [warm]:
     *  asked as home opened, before the watchlist was. */
    private fun show(on: Boolean = true, ready: Boolean = true, warm: Boolean = true) {
        serverOn = on
        if (warm) runBlocking { listsByKind.ask(fresh = false) }
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        vm = WatchlistViewModel(
            api = api,
            streamTokens = StreamTokenManager(api) { ActiveChoice("viewer", 0) },
            userFlags = UserFlagsRepository(bookmarksApi, activeChanges = { 0 }) { ActiveChoice("viewer", 0) },
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            listsByKind = listsByKind,
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                ShellScreen {
                    WatchlistScreen(
                        viewModel = vm,
                        onItemSelected = {},
                        onHomeNav = {},
                        onMoviesNav = {},
                        onSeriesNav = {},
                        onSearch = {},
                        onSettings = {},
                        onWatchlist = {},
                        onAccountClick = {},
                        onZapNav = {},
                    )
                }
            }
        }
        if (ready) waitFor { vm.state.value is WatchlistUiState.Ready }
    }

    private fun waitFor(done: () -> Boolean) {
        // What other threads hand back to the main thread runs only when
        // the main thread is idled; waiting alone doesn't run it.
        compose.waitUntil(timeoutMillis = 5_000) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            done()
        }
        compose.waitForIdle()
    }

    private fun count(text: String, substring: Boolean = false) =
        compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().size

    private fun card(title: String) = compose.onAllNodes(hasText(title) and hasClickAction())

    /** Where [text] starts down the page, scrolled out of view too. */
    private fun top(text: String) = compose.onNode(hasText(text)).fetchSemanticsNode().positionInRoot.y

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test
    fun `8_C18 with the setting on each list shows its movies, then its shows, each from its own answer`() {
        show()
        assertEquals(1, count("movies · 2"))
        assertEquals(1, count("shows · 1"))
        assertTrue("movies first", top("movies · 2") < top("shows · 1"))
        assertEquals(1, card("Harbour Light").fetchSemanticsNodes().size)
        assertEquals(1, card("Quiet Orchard").fetchSemanticsNodes().size)
        // Weekend: its movies alone - a row with nothing in it is left out.
        assertEquals(1, count("movies · 1"))
        assertEquals(1, count("shows · ", substring = true))
        assertTrue("Weekend's movies under Weekend", top("Weekend") < top("movies · 1"))
        // Asked by kind, never whole.
        assertTrue(asked.toString(), asked.none { it.endsWith("whole") })
    }

    @Test
    fun `8_C18 a list's two rows are asked one after the other, movies first`() {
        holdMovies = true
        show(ready = false)
        waitFor { heldMovies != null }
        assertEquals("shows not asked while the movies' answer is out", listOf("l1 movie"), asked.filter { it.startsWith("l1") })
        compose.runOnIdle { heldMovies!!.resumeWith(Result.success(WatchlistDetail(id = "l1", name = "l1", items = listOf("m1")))) }
        waitFor { asked.contains("l1 series") }
        assertEquals(listOf("l1 movie", "l1 series"), asked.filter { it.startsWith("l1") })
    }

    @Test
    fun `8_C18 row by row - a title both answers hold is drawn in both rows, one neither holds in none, nothing mended`() {
        lists = listOf(Watchlist(id = "l1", name = "Watchlist", itemCount = 4, isDefault = true))
        byKind["l1"] = mutableMapOf("movie" to listOf("m1", "x1"), "series" to listOf("x1"))
        show()
        // The heading keeps the list's count; each row the count its answer said.
        assertEquals(1, count("· 4"))
        assertEquals(1, count("movies · 2"))
        assertEquals(1, count("shows · 1"))
        assertEquals(2, card("Drifting Signal").fetchSemanticsNodes().size)
        assertEquals(0, card("Copper Tide").fetchSemanticsNodes().size)
        assertTrue(itemsAsked.toString(), "y1" !in itemsAsked)
        assertTrue(asked.toString(), asked.none { it.endsWith("whole") })
        // Opened, the same: a card in each part.
        compose.runOnIdle { vm.openList("l1") }
        waitFor { vm.openKinds.value != null }
        assertEquals(2, card("Drifting Signal").fetchSemanticsNodes().size)
        assertEquals(1, count("movies · 2"))
        assertEquals(1, count("shows · 1"))
    }

    @Test
    fun `8_C18 a row whose answer failed says so on its own, and try again asks for that row alone`() {
        byKind.getValue("l1")["series"] = null
        show()
        assertEquals(1, count("movies · 2"))
        assertEquals(1, card("Paper Station").fetchSemanticsNodes().size)
        assertEquals(1, count("shows"))
        assertEquals(1, count("couldn't load this list"))
        assertEquals(1, count("Can't reach the server."))
        assertTrue("the other list shows", count("movies · 1") == 1)
        byKind.getValue("l1")["series"] = listOf("s1")
        val before = asked.size
        compose.onNode(hasText("try again") and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)
        waitFor { count("shows · 1") == 1 }
        assertEquals(listOf("l1 series"), asked.drop(before))
        assertEquals(0, count("couldn't load this list"))
        assertEquals(1, card("Quiet Orchard").fetchSemanticsNodes().size)
    }

    @Test
    fun `8_C18 a list with nothing in it shows its empty line, as with the setting off`() {
        lists = lists + Watchlist(id = "l3", name = "Sunday nights", itemCount = 0)
        byKind["l3"] = mutableMapOf("movie" to emptyList(), "series" to emptyList())
        show()
        assertEquals(1, count("Add titles with the arrow beside the bookmark on a movie's or a show's page."))
        assertTrue(top("Sunday nights") < top("Add titles with the arrow beside the bookmark on a movie's or a show's page."))
    }

    @Test
    fun `8_C18 an opened list is split the same way, its count and its buttons where they are`() {
        show()
        compose.runOnIdle { vm.openList("l1") }
        waitFor { vm.openKinds.value != null }
        assertEquals(1, count("movies · 2"))
        assertEquals(1, count("shows · 1"))
        assertTrue(top("movies · 2") < top("shows · 1"))
        assertTrue(top("Harbour Light") < top("shows · 1"))
        assertTrue(top("shows · 1") < top("Quiet Orchard"))
        assertEquals(1, count("· 3"))
        assertEquals(1, count("rename list"))
        assertEquals(listOf("l1 movie", "l1 series"), asked.filter { it.startsWith("l1") }.takeLast(2))
    }

    @Test
    fun `8_C18 with the setting off every list is one row, as before`() {
        show(on = false)
        assertEquals(0, count("movies · ", substring = true))
        assertEquals(0, count("shows · ", substring = true))
        assertEquals(1, card("Harbour Light").fetchSemanticsNodes().size)
        assertEquals(1, card("Quiet Orchard").fetchSemanticsNodes().size)
        assertTrue(asked.toString(), asked.isNotEmpty() && asked.all { it.endsWith("whole") })
    }

    @Test
    fun `8_C18 the setting changed while the lists show draws them anew`() {
        show(on = false)
        assertEquals(0, count("movies · 2"))
        // Changed on another device, and read again.
        serverOn = true
        runBlocking { accountSettings.watchlistByKind(fresh = true) }
        waitFor { count("movies · 2") == 1 }
        assertEquals(1, count("shows · 1"))
    }

    @Test
    fun `8_8_1 the lists are asked beside the setting, drawn with the choice last known while it is read, and again once it answers otherwise`() {
        lastKnown.keep("viewer", false)
        holdRead = true
        show(on = true, warm = false)
        // Drawn whole, as last known, while the read is still out.
        waitFor { heldRead != null }
        assertTrue(asked.toString(), asked.isNotEmpty() && asked.all { it.endsWith("whole") })
        assertEquals(0, count("movies · 2"))
        compose.runOnIdle { heldRead!!.resumeWith(Result.success(AccountSettingsResponse(true))) }
        waitFor { count("movies · 2") == 1 }
        assertEquals(1, count("shows · 1"))
        assertEquals(true, lastKnown.of("viewer"))
    }

    @Test
    fun `8_8_1 a setting answered before the lists is drawn with them at once, though asked beside them`() {
        lastKnown.keep("viewer", false)
        holdRead = true
        holdLists = true
        show(on = true, ready = false, warm = false)
        // Both out at once: the lists didn't wait for the setting.
        waitFor { heldRead != null && heldLists != null }
        compose.runOnIdle { heldRead!!.resumeWith(Result.success(AccountSettingsResponse(true))) }
        waitFor { accountSettings.watchlistByKind.value == true }
        compose.runOnIdle { heldLists!!.resumeWith(Result.success(WatchlistsResponse(lists))) }
        waitFor { vm.state.value is WatchlistUiState.Ready }
        assertNotNull((vm.state.value as WatchlistUiState.Ready).byKind)
        assertEquals(1, count("movies · 2"))
        assertTrue(asked.toString(), asked.none { it.endsWith("whole") })
    }

    @Test
    fun `8_8_1 before the server's features have answered every list is one row, then split once they say so`() {
        lastKnown.keep("viewer", true)
        features = CompletableDeferred()
        show(on = true, warm = false)
        assertEquals(0, count("movies · 2"))
        assertTrue(asked.toString(), asked.isNotEmpty() && asked.all { it.endsWith("whole") })
        features.complete(ServerConfigResponse.Features(watchlistKinds = true))
        waitFor { count("movies · 2") == 1 }
    }

    @Test
    fun `8_C18 try again that loads the row sends the focus to the row's first card`() {
        byKind.getValue("l1")["series"] = null
        show()
        val retry = compose.onNode(hasText("try again") and hasClickAction())
        retry.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onNode(isFocused()).assert(hasText("try again"))
        byKind.getValue("l1")["series"] = listOf("s1")
        retry.performSemanticsAction(SemanticsActions.OnClick)
        waitFor { count("shows · 1") == 1 }
        compose.onNode(isFocused()).assert(hasText("Quiet Orchard"))
    }

    @Test
    fun `8_C18 in an opened list too, try again that loads the part sends the focus to its first card`() {
        byKind.getValue("l1")["series"] = null
        show()
        compose.runOnIdle { vm.openList("l1") }
        waitFor { vm.openKinds.value != null }
        val retry = compose.onNode(hasText("try again") and hasClickAction())
        retry.performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        byKind.getValue("l1")["series"] = listOf("s1")
        retry.performSemanticsAction(SemanticsActions.OnClick)
        waitFor { count("shows · 1") == 1 }
        compose.onNode(isFocused()).assert(hasText("Quiet Orchard"))
    }

    @Test
    fun `8_C18 an opened list follows the setting changed while it is open`() {
        show(on = false)
        compose.runOnIdle { vm.openList("l1") }
        waitFor { !vm.openItemsLoading.value }
        assertNull(vm.openKinds.value)
        assertEquals(0, count("movies · 2"))
        serverOn = true
        runBlocking { accountSettings.watchlistByKind(fresh = true) }
        waitFor { vm.openKinds.value != null && count("movies · 2") == 1 }
        assertEquals("l1", vm.openListId.value)
        assertEquals(1, count("shows · 1"))
    }

    @Test
    fun `8_C18 a list opened before, answered once another is open, is not drawn under that one`() {
        show()
        holdMovies = true
        compose.runOnIdle { vm.openList("l1") }
        waitFor { heldMovies != null }
        val seriesBefore = asked.count { it == "l1 series" }
        compose.runOnIdle {
            vm.closeList()
            vm.openList("l2")
        }
        waitFor { vm.openKinds.value != null }
        assertEquals(1, count("movies · 1"))
        compose.runOnIdle { heldMovies!!.resumeWith(Result.success(WatchlistDetail(id = "l1", name = "l1", items = listOf("m1", "m2")))) }
        // The list opened before is answered to the end, and not drawn.
        waitFor { asked.count { it == "l1 series" } > seriesBefore + 1 }
        assertEquals("l2", vm.openListId.value)
        assertEquals(1, count("movies · 1"))
        assertEquals(0, count("movies · 2"))
        assertEquals(0, card("Harbour Light").fetchSemanticsNodes().size)
    }

    @Test
    fun `8_C18 an opened list by kind counts nothing under its parts`() {
        show()
        compose.runOnIdle { vm.openList("l1") }
        waitFor { vm.openKinds.value != null }
        assertEquals(1, count("movies · 2"))
        assertEquals(0, count(" saved", substring = true))
    }

    @Test
    fun `8_8_1 a row with no card to draw is left out, and a list with none shows its empty line`() {
        byKind["l1"] = mutableMapOf("movie" to listOf("m1", "gone1"), "series" to listOf("gone2"))
        byKind["l2"] = mutableMapOf("movie" to listOf("gone3"), "series" to emptyList())
        show()
        // The row keeps its answer's count over the one card it can draw.
        assertEquals(1, count("movies · 2"))
        assertEquals(1, card("Harbour Light").fetchSemanticsNodes().size)
        assertEquals(0, count("shows · 1"))
        assertEquals(0, count("shows"))
        // Weekend has no card to draw: its empty line, no row's name.
        assertEquals(0, count("movies · 1"))
        assertEquals(1, count("Add titles with the arrow beside the bookmark on a movie's or a show's page."))
        // Opened, the same.
        compose.runOnIdle { vm.openList("l1") }
        waitFor { vm.openKinds.value != null }
        assertEquals(1, count("movies · 2"))
        assertEquals(0, count("shows · 1"))
    }

    @Test
    fun `8_C18 on a TV up and down go from row to row, each row keeping the card it was left on`() {
        show()
        card("Paper Station").onFirst().performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        press(Key.DirectionDown)
        if (compose.onAllNodes(isFocused() and hasText("Quiet Orchard")).fetchSemanticsNodes().isEmpty()) press(Key.DirectionLeft)
        compose.onNode(isFocused()).assert(hasText("Quiet Orchard"))
        // Up from the shows' first card: back on the movie it was left on,
        // the second, not the one above it.
        press(Key.DirectionUp)
        compose.onNode(isFocused()).assert(hasText("Paper Station"))
        press(Key.DirectionDown)
        compose.onNode(isFocused()).assert(hasText("Quiet Orchard"))
        // The rows' names take no focus: up from the movies goes to the
        // list's heading, above "movies · 2".
        press(Key.DirectionUp)
        press(Key.DirectionUp)
        val focused = compose.onNode(isFocused()).fetchSemanticsNode().positionInRoot.y
        assertTrue("focus at $focused", focused < top("movies · 2") && focused > top("new list"))
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.onFirst() = this[0]
