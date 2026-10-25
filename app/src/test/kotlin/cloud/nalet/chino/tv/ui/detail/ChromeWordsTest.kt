package cloud.nalet.chino.tv.ui.detail

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.api.ProgressResponse
import cloud.nalet.chino.tv.data.api.StreamTokenResponse
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.CastMember
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.model.ItemsPage
import cloud.nalet.chino.tv.data.model.Subtitle
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import cloud.nalet.chino.tv.ui.onboarding.serverSetupHeading
import cloud.nalet.chino.tv.ui.theme.ChinoMonoFamily
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import cloud.nalet.chino.tv.ui.zap.zapKeyHints
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Chrome in the mono face, lowercase (the design system's voice): the title
 * page's "subtitles" label and its crew headings, Zap's key hints, the
 * server screen's heading. Names and codes inside keep their case.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class ChromeWordsTest {
    @get:Rule val compose = createComposeRule()

    private val movie = Item(
        id = "m1",
        title = "Quiet Orchard",
        kind = "movie",
        year = 2021,
        subtitles = listOf(Subtitle(lang = "ger"), Subtitle(lang = "eng"), Subtitle(lang = "zho")),
        cast = listOf(
            CastMember(name = "Ada Vale", role = "actor", character = "The keeper"),
            CastMember(name = "Ben Ash", role = "director"),
            CastMember(name = "Cleo Mor", role = "writer"),
            CastMember(name = "Dov Lin", role = "writer"),
            CastMember(name = "Eli Park", role = "producer"),
            CastMember(name = "Fay Orr", role = "composer"),
        ),
    )

    private val api = java.lang.reflect.Proxy.newProxyInstance(
        ChinoApi::class.java.classLoader, arrayOf(ChinoApi::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getItem" -> movie
            "getProgress" -> ProgressResponse(positionSec = 0)
            "similar" -> ItemsPage()
            "mintStreamToken" -> StreamTokenResponse(token = "t")
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "the server"
            else -> throw java.io.IOException("not asked here")
        }
    } as ChinoApi

    private fun fontOf(text: String) = mutableListOf<TextLayoutResult>().also { results ->
        compose.onNode(hasText(text)).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
    }.single().layoutInput.style.fontFamily

    @Test
    fun `the title page's subtitles label and crew headings in the mono face, lowercase`() {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        val vm = DetailViewModel(
            api = api,
            streamTokens = StreamTokenManager(api),
            userFlags = UserFlagsRepository(api),
            telemetry = Telemetry(ctx) { null },
            baseUrl = "https://media.test/api",
            itemId = "m1",
            playCaps = { throw IllegalStateException("no caps here") },
        )
        compose.setContent { ChinoTvTheme { DetailScreen(viewModel = vm, onPlay = { _, _ -> }) } }
        compose.waitUntil(5_000) { vm.state.value is DetailUiState.Ready }
        compose.waitForIdle()
        for (label in listOf("subtitles", "starring", "director", "writers", "producer", "music")) {
            assertEquals(label, ChinoMonoFamily, fontOf(label))
        }
        // What they name stays in the body face, in its case - the names
        // in alphabetical order, never their codes'.
        compose.onNode(hasText("Chinese, English, German")).assertExists()
        assertEquals(listOf("director", "writers", "producer", "music"), groupCredits(movie.cast).crew.map(::crewHeading))
    }

    @Test
    fun `Zap's key hints and the server screen's heading - the words lowercase, the keys by their names`() {
        assertEquals(
            listOf("OK · watch from here", "▼ / CH+ next", "CH- back", "▲ info", "◀ mute", "▶ Watchlist"),
            zapKeyHints(muted = false),
        )
        assertEquals("◀ unmute", zapKeyHints(muted = true)[4])
        assertEquals("change server", serverSetupHeading(changeServer = true))
        assertEquals("connect to your server", serverSetupHeading(changeServer = false))
    }
}
