package cloud.nalet.chino.tv.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
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
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The title page's first button as playing leaves it (docs/chino, playback;
 * title actions): back from the player, a movie says its new position,
 * "resume 3:10", never the one it said before - and "play" with the watched
 * mark once the server counts it finished; a series opened on an episode -
 * its continue-watching card - names that episode by its code, "resume
 * S01E03", as from the series' own card, and start over plays S01E03 from
 * its beginning - until the viewer plays on: S01E03 finished, it says "play
 * S01E04", the series' next one, and another episode played, that one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class TitleResumeTest {
    @get:Rule val compose = createComposeRule()

    private val movie = Item(id = "m1", title = "Quiet Orchard", kind = "movie", year = 2021, durationMs = 5_400_000L)
    private val series = Item(id = "s1", title = "Paper Station", kind = "series")

    /** S01E0[n] as its own page asks for it: a continue-watching card's, a
     *  search hit's. */
    private fun episode(n: Int) = Item(
        id = "e$n", title = "Part $n", kind = "episode", parentId = "s1", seasonNumber = 1, episodeNumber = n,
        durationMs = 1_500_000L,
    )

    /** The episode list as the server marks it now. */
    private fun episodes() = (1..4).map { n ->
        Episode(
            id = "e$n", title = "Part $n", seasonNumber = 1, episodeNumber = n, parentId = "s1",
            durationMs = 1_500_000L, watchedAt = if ("e$n" in watched) "2026-10-01T20:00:00Z" else null,
        )
    }

    /** A continue-watching row of S01E0[n]. */
    private fun row(n: Int, positionSec: Int, upNext: Boolean = false) = ContinueWatchingItem(
        id = "e$n", title = "Part $n", positionSec = positionSec, durationSec = 1500, upNext = upNext,
        seriesTitle = "Paper Station", seasonNumber = 1, episodeNumber = n, parentId = "s1", type = "episode",
    )

    /** What the server says now: the saved positions, a movie's watched
     *  mark; S01E01 and S01E02 finished, S01E03 part way, S01E04 not begun,
     *  and the feed's row of the series, newest first. */
    private val position = mutableMapOf("m1" to 600, "e3" to 603)
    private var movieWatched: String? = null
    private val watched = mutableSetOf("e1", "e2")
    private var feed: () -> List<ContinueWatchingItem> = { listOf(row(3, position.getValue("e3"))) }

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getItem" -> when (args[0]) {
                "m1" -> movie.copy(watchedAt = movieWatched)
                "s1" -> series
                else -> episode((args[0] as String).removePrefix("e").toInt())
            }
            "getProgress" -> ProgressResponse(positionSec = position[args[0]] ?: 0)
            "similar" -> ItemsPage()
            "seriesEpisodes" -> SeriesEpisodes(listOf(Season(1, episodes())))
            "continueWatching" -> ContinueWatchingResponse(feed())
            "nextEpisode" -> NextEpisodeResponse()
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    private val played = mutableListOf<Pair<String, Int>>()

    private fun show(id: String, saves: ProgressSaves = ProgressSaves()): DetailViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = id,
            playCaps = { throw IllegalStateException("no caps here") },
            progressSaves = saves,
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                DetailScreen(viewModel = vm, onPlay = { target, sec -> played += target to sec })
            }
        }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        compose.waitForIdle()
        return vm
    }

    /** Back from the player: the page's screen resumes. */
    private fun backFromPlayer(vm: DetailViewModel) {
        compose.runOnIdle { vm.onScreenResumed() }
        compose.waitForIdle()
    }

    private fun press(label: String) {
        compose.onNode(hasText(label)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    @Test
    fun `back from the player a movie says its new position, never the stale one`() {
        val vm = show("m1")
        compose.onNode(hasText("resume 10:00")).assertExists()
        // Played on to 3:10 after starting over, then BACK.
        position["m1"] = 190
        backFromPlayer(vm)
        compose.onNode(hasText("resume 3:10")).assertExists()
        compose.onNode(hasText("resume 10:00")).assertDoesNotExist()
        assertEquals(190, vm.resumeSecond())
        press("resume 3:10")
        assertEquals(listOf("m1" to 190), played)
        // Played to its end: the server counts it finished - no position, watched.
        position["m1"] = 0
        movieWatched = "2026-10-25T21:00:00Z"
        backFromPlayer(vm)
        compose.onNode(hasText("play")).assertExists()
        compose.onNode(hasText("resume", substring = true)).assertDoesNotExist()
        assertEquals(movieWatched, (vm.state.value as DetailUiState.Ready).item.watchedAt)
    }

    @Test
    fun `back from the player the page reads the position once the player's last save is answered`() {
        val saves = ProgressSaves()
        val vm = show("m1", saves)
        compose.onNode(hasText("resume 10:00")).assertExists()
        // The player is up; the page resumes as it goes, before its last
        // save has left.
        val player = saves.hold()
        backFromPlayer(vm)
        compose.onNode(hasText("resume 10:00")).assertExists()
        // The player's last save, 3:10, is answered: the page reads it.
        position["m1"] = 190
        compose.runOnIdle { player.closeAfter(null) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("resume 3:10")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasText("resume 10:00")).assertDoesNotExist()
        assertEquals(190, vm.resumeSecond())
    }

    @Test
    fun `a series opened from an episode's card names the episode by its code, start over plays it from its beginning`() {
        val vm = show("e3")
        compose.onNode(hasText("resume S01E03")).assertExists()
        compose.onNode(hasText("resume 10:03")).assertDoesNotExist()
        assertEquals("e3", vm.playTarget())
        assertEquals(603, vm.resumeSecond())
        press("start over")
        press("resume S01E03")
        assertEquals(listOf("e3" to 0, "e3" to 603), played)
        // Back from the player at 20:03: the button still names the
        // episode, and resumes where it was left now.
        position["e3"] = 1203
        backFromPlayer(vm)
        compose.onNode(hasText("resume S01E03")).assertExists()
        assertEquals(1203, vm.resumeSecond())
    }

    @Test
    fun `back from finishing the episode it was opened on, the series' next one`() {
        val vm = show("e3")
        compose.onNode(hasText("resume S01E03")).assertExists()
        press("resume S01E03")
        // S01E03 watched to its end, the player on into S01E04 and BACK
        // before it saved any: S01E03 finished - no position, watched - and
        // the feed has S01E04 up next.
        position["e3"] = 0
        watched += "e3"
        feed = { listOf(row(4, 0, upNext = true)) }
        backFromPlayer(vm)
        compose.onNode(hasText("play S01E04")).assertExists()
        compose.onNode(hasText("play S01E03")).assertDoesNotExist()
        compose.onNode(hasText("resume S01E03")).assertDoesNotExist()
        assertEquals("e4", vm.playTarget())
        assertEquals(0, vm.resumeSecond())
        press("play S01E04")
        assertEquals(listOf("e3" to 603, "e4" to 0), played)
        // From then on the series' state: S01E04 stopped at 5:00.
        position["e4"] = 300
        feed = { listOf(row(4, 300)) }
        backFromPlayer(vm)
        compose.onNode(hasText("resume S01E04")).assertExists()
        assertEquals(300, vm.resumeSecond())
    }

    @Test
    fun `back from another episode, played from its row, that episode`() {
        val vm = show("e3")
        compose.onNode(hasText("resume S01E03")).assertExists()
        // S01E04 played from its row, stopped at 5:00; S01E03 as it was.
        position["e4"] = 300
        feed = { listOf(row(4, 300), row(3, 603)) }
        backFromPlayer(vm)
        compose.onNode(hasText("resume S01E04")).assertExists()
        compose.onNode(hasText("resume S01E03")).assertDoesNotExist()
        assertEquals("e4", vm.playTarget())
        assertEquals(300, vm.resumeSecond())
    }

    @Test
    fun `a page opened on an episode names it as long as nothing was played`() {
        // A search hit: S01E01, finished long ago, while S01E03 is part way.
        val vm = show("e1")
        compose.onNode(hasText("play S01E01")).assertExists()
        // Back from a page of its cast: the page resumes, nothing played.
        backFromPlayer(vm)
        compose.onNode(hasText("play S01E01")).assertExists()
        compose.onNode(hasText("resume S01E03")).assertDoesNotExist()
        assertEquals("e1", vm.playTarget())
    }

    @Test
    fun `an episode's card with nothing saved plays the episode by its code`() {
        position["e3"] = 0
        val vm = show("e3")
        compose.onNode(hasText("play S01E03")).assertExists()
        compose.onNode(hasText("start over")).assertDoesNotExist()
        assertEquals("e3", vm.playTarget())
        assertEquals(0, vm.resumeSecond())
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
