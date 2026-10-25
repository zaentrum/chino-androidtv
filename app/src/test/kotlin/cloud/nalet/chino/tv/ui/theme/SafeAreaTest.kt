package cloud.nalet.chino.tv.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import cloud.nalet.chino.tv.ui.library.TvSideRail
import cloud.nalet.chino.tv.ui.library.TvTopBar
import cloud.nalet.chino.tv.ui.player.PlayerBottomChrome
import cloud.nalet.chino.tv.ui.player.PlayerChromeOverlay
import cloud.nalet.chino.tv.ui.player.PlayerTopChrome
import cloud.nalet.chino.tv.ui.player.PlayerSafeArea
import cloud.nalet.chino.tv.ui.player.SkipSegmentButton
import cloud.nalet.chino.tv.data.api.Segment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasAnyChild
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The TV's safe area (the design system's tv focus): everything that takes
 * focus stays 48 dp from the left and right edges and 27 dp from the top and
 * bottom, measured to its ring at its growth - the shell's rail and top bar,
 * and the player's controls - on the TV's 960 × 540 dp (1920 × 1080 px).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SafeAreaTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

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
                    TvSideRail(activeType = null, onHome = {}, onMovies = {}, onSeries = {}, onSettings = {}, onZap = {}, onWatchlist = {})
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
    fun `a page's scrim and its backdrop reach past the safe area to the screen's edges`() {
        show {
            SafeAreaScreen {
                ChinoPage {
                    Box(Modifier.fillMaxWidth().height(100.dp).bleedToScreenEdges(bottom = false).background(Color.Red))
                    Box(Modifier.fillMaxSize().padding(top = 200.dp).scrim())
                }
            }
        }
        // The window as drawn (the screenshot tests' way under Robolectric).
        val view = compose.activity.window.decorView
        val shot = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(android.graphics.Canvas(shot)) }
        // The backdrop at the screen's top-left corner, past the safe area.
        assertEquals(Color.Red.toArgb(), shot.getPixel(2, 2))
        assertEquals(Color.Red.toArgb(), shot.getPixel(shot.width - 3, 2))
        // The scrim at the bottom corners: the canvas dimmed, not the canvas.
        val canvas = ChinoBg.toArgb()
        val corner = shot.getPixel(2, shot.height - 3)
        assertTrue("the scrim reaches the corner", corner != canvas)
        assertEquals(ScrimColor.compositeOverBg().toArgb(), corner, 0x030303)
        assertTrue(shot.getPixel(shot.width - 3, shot.height - 3) != canvas)
    }

    private fun Color.compositeOverBg(): Color = compositeOver(ChinoBg)

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        for (s in listOf(16, 8, 0)) {
            assertTrue("channel ${s / 8}: ${Integer.toHexString(actual)} vs ${Integer.toHexString(expected)}",
                kotlin.math.abs(ch(expected, s) - ch(actual, s)) <= (tolerance and 0xFF))
        }
    }

    @Test
    fun `the player's controls inside the safe area, over a picture to the screen's edges`() {
        show {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                SafeAreaOverlay {
                    PlayerChromeOverlay(
                        visible = true,
                        top = { PlayerTopChrome(title = "Quiet Orchard", onBack = {}, onUserInteraction = {}, onHome = {}) },
                        bottom = { Bar() },
                    )
                }
            }
        }
        for (name in barButtons + listOf("Back", "Home")) control(name)
    }

    /** The control bar of an episode, every button on it. */
    @Composable
    private fun Bar() {
        PlayerBottomChrome(
            isPlaying = true, positionMs = 60_000, durationMs = 600_000, bufferedMs = 120_000,
            segments = emptyList(), trickplayCues = emptyList(), trickplayBaseUrl = "", streamToken = "",
            muted = false, volume = 1f, onPlayPause = {}, onToggleMute = {}, onVolumeChange = {},
            onScrubberSeek = {}, onAudio = {}, onSubtitles = {}, onQuality = {}, onSpeed = {},
            onInfo = {}, onPrevEpisode = {}, onNextEpisode = {},
            playPauseFocusRequester = FocusRequester(), onUserInteraction = {},
        )
    }

    private val barButtons = listOf(
        "Pause", "Volume", "Audio track", "Subtitles", "Playback speed", "Quality",
        "Previous episode", "Next episode", "Playback info",
    )

    @Test
    fun `the skip pill stands above the control bar while it shows, on none of its buttons, and at the bottom when it hides`() {
        var controls by mutableStateOf(true)
        show {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                PlayerSafeArea(
                    controlsVisible = controls,
                    top = { PlayerTopChrome(title = "Quiet Orchard", onBack = {}, onUserInteraction = {}, onHome = {}) },
                    bottom = { Bar() },
                ) {
                    SkipSegmentButton(segment = Segment(kind = "intro", startMs = 0, endMs = 90_000), onSkip = {})
                }
            }
        }
        // The pill: the focusable row around its label.
        val pill = compose.onNode(hasAnyChild(hasText("skip intro")), useUnmergedTree = true)
        val lifted = reach(pill, 1.05f, ringOutside = true)
        assertInside("skip intro", lifted)
        // Its ring at its growth clear of every button's, and of the seek
        // row's time label below it in the corner.
        val covered = (barButtons.map { it to compose.onNode(hasContentDescription(it)) } +
            ("10:00" to compose.onNode(hasText("10:00"))))
            .map { (name, node) -> name to reach(node, 1.05f, ringOutside = true) }
            .filter { (_, r) -> r.overlaps(lifted) }
        assertTrue("skip intro at $lifted on ${covered.map { it.first }}", covered.isEmpty())
        // The bar hidden: back down in the safe area's corner, its ring's room below it.
        controls = false
        compose.waitForIdle()
        val down = pill.fetchSemanticsNode().boundsInRoot
        assertEquals(safe.bottom - 12f, down.bottom / 2f, 0.5f)
        assertEquals(safe.right - 12f, down.right / 2f, 0.5f)
        assertInside("skip intro", reach(pill, 1.05f, ringOutside = true))
    }
}
