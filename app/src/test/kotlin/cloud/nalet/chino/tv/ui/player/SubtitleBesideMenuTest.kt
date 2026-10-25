package cloud.nalet.chino.tv.ui.player

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.ui.SubtitleView
import androidx.test.core.app.ApplicationProvider
import cloud.nalet.chino.tv.ui.theme.ChinoTvTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The subtitles menu never hides the line it changes (docs/chino, subtitles:
 * out of the controls' way): while a menu of the player is open at the
 * bottom-right, the subtitle is centred in the part of the picture left of
 * it, within 80 % of that, and goes back as the menu closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SubtitleBesideMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the line's box ends left of an open menu, the gap before it, coming and going with it`() {
        val gap = 24f
        // A full-screen picture, the menu's left edge at 1064 px.
        assertEquals(1920 - 1064 + 24, subtitleEndInset(0, 1920, 1064f, gap))
        // Centred within 80 % of what is left, the line ends well left of
        // the menu.
        val box = 1920 - subtitleEndInset(0, 1920, 1064f, gap)
        val lineRight = box * (1f + SubtitleLook.LINE_WIDTH_FRACTION) / 2f
        assertTrue("the line ends at $lineRight px", lineRight + gap <= 1064f)
        // No menu, or one going: none, or part of the way.
        assertEquals(0, subtitleEndInset(0, 1920, null, gap))
        assertEquals(0, subtitleEndInset(0, 1920, 1064f, gap, aside = 0f))
        assertEquals((1920 - 1064 + 24) / 2, subtitleEndInset(0, 1920, 1064f, gap, aside = 0.5f))
        // A picture that ends before the menu: nothing to clear.
        assertEquals(0, subtitleEndInset(0, 1000, 1064f, gap))
        // However wide a menu, the line keeps half the picture.
        assertEquals(960, subtitleEndInset(0, 1920, 200f, gap))
    }

    @Test
    fun `the view's box narrows beside an open menu, the text's size as it was`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = SubtitleView(context)
        val placement = view.applySubtitleLook()
        fun layOut() {
            view.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 1920, 1080)
        }
        layOut()
        val density = context.resources.displayMetrics.density
        placement.menuLeft = 1064f
        placement.aside = 1f
        layOut()
        val inset = subtitleEndInset(0, 1920, 1064f, SubtitleLook.BESIDE_MENU_DP * density)
        assertEquals(inset, view.paddingRight)
        // What draws the cues is laid out left of the menu, as tall as the
        // picture: the text's size, a share of that height, stays.
        val output = view.getChildAt(0)
        assertEquals(1920 - inset, output.width)
        assertEquals(1080, output.height)
        // The menu closes.
        placement.aside = 0f
        layOut()
        assertEquals(0, view.paddingRight)
        assertEquals(1920, view.getChildAt(0).width)
    }

    @Test
    fun `an open menu says where its left edge is, and that it is gone as it closes`() {
        val edges = mutableListOf<Float?>()
        var open by mutableStateOf(true)
        compose.setContent {
            ChinoTvTheme {
                CompositionLocalProvider(LocalMenuEdge provides { edges += it }) {
                    if (open) SubtitlePanel(menu = SubtitleMenu(emptyList(), emptyList()), selected = null, onSelect = {})
                }
            }
        }
        compose.waitForIdle()
        val left = edges.lastOrNull()
        assertNotNull(left)
        assertTrue("the menu's left edge at $left px", left!! > 0f)
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        assertNull(edges.last())
    }
}
