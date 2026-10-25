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
import cloud.nalet.chino.tv.ui.navigation.detailPlayRoute
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A series' row (docs/chino, title actions: on a series): with S01E03
 * partly watched the first button is "resume S01E03", and "start over"
 * plays S01E03 from its beginning - that episode, never the series' first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
@OptIn(ExperimentalTestApi::class)
class SeriesStartOverTest {
    @get:Rule val compose = createComposeRule()

    private val series = Item(id = "s1", title = "Paper Station", kind = "series")

    // S01E01 and S01E02 finished, S01E03 stopped at 10:03, S01E04 not begun.
    private val episodes = (1..4).map { n ->
        Episode(
            id = "e$n",
            title = "Part $n",
            seasonNumber = 1,
            episodeNumber = n,
            parentId = "s1",
            durationMs = 1_500_000L,
            watchedAt = if (n < 3) "2026-10-01T20:00:00Z" else null,
        )
    }

    private val partway = ContinueWatchingItem(
        id = "e3",
        title = "Part 3",
        positionSec = 603,
        durationSec = 1500,
        seriesTitle = "Paper Station",
        seasonNumber = 1,
        episodeNumber = 3,
        parentId = "s1",
        type = "episode",
    )

    /** The server, as far as the title page asks it. */
    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getItem" -> series
            "getProgress" -> ProgressResponse(positionSec = 0)
            "similar" -> ItemsPage()
            "seriesEpisodes" -> SeriesEpisodes(listOf(Season(1, episodes)))
            "continueWatching" -> ContinueWatchingResponse(listOf(partway))
            "nextEpisode" -> NextEpisodeResponse()
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    private val played = mutableListOf<Pair<String, Int>>()

    private fun show(): DetailViewModel {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = "s1",
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent {
            ChinoTvTheme {
                Remote()
                DetailScreen(viewModel = vm, onPlay = { id, sec -> played += id to sec })
            }
        }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        compose.waitForIdle()
        return vm
    }

    private fun press(label: String) {
        compose.onNode(hasText(label)).performSemanticsAction(SemanticsActions.RequestFocus)
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
    }

    @Test
    fun `start over plays the episode the series is in, from its beginning`() {
        val vm = show()
        assertEquals("e3", vm.playTarget())
        assertEquals(603, vm.resumeSecond())
        compose.onNode(hasText("resume S01E03")).assertExists()
        press("start over")
        assertEquals(listOf("e3" to 0), played)
        // Its route asks the player for the beginning, never the saved
        // position.
        assertEquals("player/e3?fromStart=true&fromBinge=false&resume=0", detailPlayRoute("e3", 0))
        press("resume S01E03")
        assertEquals(listOf("e3" to 0, "e3" to 603), played)
        assertEquals("player/e3?fromStart=false&fromBinge=false&resume=603", detailPlayRoute("e3", 603))
    }

    /** The remote's input: a TV is in keyboard mode. */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Remote() {
        val inputMode = LocalInputModeManager.current
        LaunchedEffect(Unit) { inputMode.requestInputMode(InputMode.Keyboard) }
    }
}
