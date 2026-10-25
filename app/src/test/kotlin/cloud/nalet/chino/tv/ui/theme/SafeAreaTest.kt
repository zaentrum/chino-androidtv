package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.player.PlayerBottomChrome
import cloud.nalet.chino.tv.ui.player.PlayerChromeOverlay
import cloud.nalet.chino.tv.ui.player.PlayerTopChrome
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The TV's safe area (the design system's tv focus): everything that takes
 * focus stays 48 dp from the left and right edges and 27 dp from the top and
 * bottom, measured to its ring at its growth - the shell's rail and top bar,
 * and the player's controls - on the TV's 960 × 540 dp (1920 × 1080 px).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SafeAreaTest {
    @get:Rule val compose = createComposeRule()

    /** The screen in the TV's dp: 2 px each on the 1920 × 1080 screen. */
    private val safe = Rect(48f, 27f, 960f - 48f, 540f - 27f)

    /** Where [node]'s focus style reaches, in the TV's dp: its edge grown by
     *  [growth], and its ring, 3 dp past a 2 dp gap where it is outside. */
    private fun reach(node: SemanticsNodeInteraction, growth: Float, ringOutside: Boolean): Rect {
        val px = node.fetchSemanticsNode().boundsInRoot
        val b = Rect(px.left / 2f, px.top / 2f, px.right / 2f, px.bottom / 2f)
        val grow = (growth - 1f) / 2f
        val ring = if (ringOutside) 5f * growth else 0f
        return Rect(
            b.left - b.width * grow - ring,
            b.top - b.height * grow - ring,
            b.right + b.width * grow + ring,
            b.bottom + b.height * grow + ring,
        )
    }

    private fun assertInside(what: String, r: Rect) {
        assertTrue("$what reaches $r, past the safe area $safe",
            r.left >= safe.left - 0.01f && r.top >= safe.top - 0.01f &&
                r.right <= safe.right + 0.01f && r.bottom <= safe.bottom + 0.01f)
    }

    private fun control(name: String) =
        assertInside(name, reach(compose.onNode(hasContentDescription(name)), 1.05f, ringOutside = true))

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { ChinoTvTheme { content() } }
        compose.waitForIdle()
    }

    @Test
    fun `the shell's rail and top bar, their rings at their growth inside the safe area`() {
        show {
            SafeAreaScreen {
                Row(Modifier.fillMaxSize()) {
                    TvSideRail(activeType = null, onHome = {}, onMovies = {}, onSeries = {}, onSettings = {})
                    Column(Modifier.weight(1f)) {
                        TvTopBar(activeAccount = null, onAccountClick = {})
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
        for (name in listOf("Home", "Movies", "Series", "Watchlist", "Zap", "Settings", "Account")) control(name)
        // The search field, a row: its ring inside its edge, 2 % growth.
        assertInside("search", reach(compose.onNode(hasText("Search movies, shows…")), 1.02f, ringOutside = false))
    }

    @Test
    fun `the player's controls inside the safe area, over a picture to the screen's edges`() {
        show {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                SafeAreaOverlay {
                    PlayerChromeOverlay(
                        visible = true,
                        top = { PlayerTopChrome(title = "Quiet Orchard", onBack = {}, onUserInteraction = {}, onHome = {}) },
                        bottom = {
                            PlayerBottomChrome(
                                isPlaying = true, positionMs = 60_000, durationMs = 600_000, bufferedMs = 120_000,
                                segments = emptyList(), trickplayCues = emptyList(), trickplayBaseUrl = "", streamToken = "",
                                muted = false, volume = 1f, onPlayPause = {}, onToggleMute = {}, onVolumeChange = {},
                                onScrubberSeek = {}, onAudio = {}, onSubtitles = {}, onQuality = {}, onSpeed = {},
                                onInfo = {}, onPrevEpisode = {}, onNextEpisode = {},
                                playPauseFocusRequester = FocusRequester(), onUserInteraction = {},
                            )
                        },
                    )
                }
            }
        }
        for (name in listOf(
            "Back", "Home", "Pause", "Volume", "Audio track", "Subtitles", "Playback speed", "Quality",
            "Previous episode", "Next episode", "Playback info",
        )) control(name)
    }
}
