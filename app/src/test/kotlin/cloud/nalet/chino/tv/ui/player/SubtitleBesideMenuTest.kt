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
import org.junit.Assert.assertFalse
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
 * it, within 80 % of that - above the menu where that part is too narrow for
 * a line - and goes back as the menu closes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-xhdpi")
class SubtitleBesideMenuTest {
    @get:Rule val compose = createComposeRule()

    /** Where the line ends on the right, in window pixels, centred within
     *  80 % of the box of the picture at [pictureLeft], [pictureWidth] wide,
     *  that ends [inset] before the picture's right edge. */
    private fun lineRight(pictureLeft: Int, pictureWidth: Int, inset: Int) =
        pictureLeft + (pictureWidth - inset) * (1f + SubtitleLook.LINE_WIDTH_FRACTION) / 2f

    @Test
    fun `the line's box ends left of an open menu, the gap before it, coming and going with it`() {
        val gap = 24f
        // A full-screen picture, the menu's left edge at 1064 px.
        assertEquals(1920 - 1064 + 24, subtitleEndInset(0, 1920, 1080, 1064f, gap))
        // Centred within 80 % of what is left, the line ends well left of
        // the menu.
        val right = lineRight(0, 1920, subtitleEndInset(0, 1920, 1080, 1064f, gap))
        assertTrue("the line ends at $right px", right + gap <= 1064f)
        assertFalse(subtitleAboveMenu(0, 1920, 1080, 1064f, gap))
        // No menu, or one going: none, or part of the way.
        assertEquals(0, subtitleEndInset(0, 1920, 1080, null, gap))
        assertEquals(0, subtitleEndInset(0, 1920, 1080, 1064f, gap, aside = 0f))
        assertEquals((1920 - 1064 + 24) / 2, subtitleEndInset(0, 1920, 1080, 1064f, gap, aside = 0.5f))
        // A picture that ends before the menu: nothing to clear.
        assertEquals(0, subtitleEndInset(0, 1000, 1080, 1064f, gap))
        assertFalse(subtitleAboveMenu(0, 1000, 1080, 1064f, gap))
    }

    @Test
    fun `beside the widest menu the line keeps clear of it, on a 4 by 3 picture as on a full one`() {
        val gap = 24f
        // The menu at its widest, 480 dp, its right edge at the safe area's
        // 912 dp: its left edge at 864 px.
        val menuLeft = (912 - 480) * 2f
        // A 4:3 picture, 1440 px wide from 240 px: the whole clearance, the
        // line the gap before the menu and more.
        val pillarboxed = subtitleEndInset(240, 1440, 1080, menuLeft, gap)
        assertEquals(240 + 1440 - 864 + 24, pillarboxed)
        val right = lineRight(240, 1440, pillarboxed)
        assertTrue("the line ends at $right px", right + gap <= menuLeft)
        assertFalse(subtitleAboveMenu(240, 1440, 1080, menuLeft, gap))
        // A full-screen picture: more than half of it to the menu, the line
        // still clear of it, not touching its edge.
        val full = subtitleEndInset(0, 1920, 1080, menuLeft, gap)
        assertEquals(1920 - 864 + 24, full)
        assertTrue(lineRight(0, 1920, full) + gap <= menuLeft)
    }

    @Test
    fun `where left of the menu is too narrow for a line, the line goes above the menu`() {
        val gap = 24f
        val safe = SubtitleLook.SAFE_BOTTOM_DP * 2f
        // An upright picture, 608 px wide from 656 px, beside the widest
        // menu: 184 px left of it, under ten times the text's size.
        assertTrue(subtitleAboveMenu(656, 608, 1080, 864f, gap))
        assertEquals(0, subtitleEndInset(656, 608, 1080, 864f, gap))
        // Its last line ends the gap above the menu's top, at 400 px.
        val fraction = subtitleBottomFraction(1080, 1080, 1080, safe, controlsTop = 400f, aboveControls = gap, lift = 1f)
        assertEquals(400f - gap, 1080f - fraction * 1080f, 0.5f)
        // A full-screen picture beside a menu from 200 px: above it too.
        assertTrue(subtitleAboveMenu(0, 1920, 1080, 200f, gap))
        assertEquals(0, subtitleEndInset(0, 1920, 1080, 200f, gap))
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
        val inset = subtitleEndInset(0, 1920, 1080, 1064f, SubtitleLook.BESIDE_MENU_DP * density)
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
    fun `the view's line goes above an open menu where left of it is too narrow, and back as it closes`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val view = SubtitleView(context)
        val placement = view.applySubtitleLook()
        fun layOut() {
            view.measure(View.MeasureSpec.makeMeasureSpec(608, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, 608, 1080)
        }
        layOut()
        val resting = placement.bottomFraction
        val gap = SubtitleLook.BESIDE_MENU_DP * context.resources.displayMetrics.density
        // A menu from 400 px, its top at 400 px: 376 px left of it.
        placement.menuLeft = 400f
        placement.menuTop = 400f
        placement.aside = 1f
        layOut()
        assertEquals(0, view.paddingRight)
        assertEquals(400f - gap, 1080f - placement.bottomFraction * 1080f, 0.5f)
        // The menu closes.
        placement.aside = 0f
        layOut()
        assertEquals(resting, placement.bottomFraction, 1e-6f)
    }

    @Test
    fun `an open menu says where its corner is, and that it is gone as it closes`() {
        val corners = mutableListOf<androidx.compose.ui.geometry.Offset?>()
        var open by mutableStateOf(true)
        compose.setContent {
            ChinoTvTheme {
                CompositionLocalProvider(LocalMenuCorner provides { corners += it }) {
                    if (open) SubtitlePanel(menu = SubtitleMenu(emptyList(), emptyList()), selected = null, onSelect = {})
                }
            }
        }
        compose.waitForIdle()
        val corner = corners.lastOrNull()
        assertNotNull(corner)
        assertTrue("the menu's left edge at ${corner!!.x} px", corner.x > 0f)
        assertTrue("the menu's top edge at ${corner.y} px", corner.y > 0f)
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        assertNull(corners.last())
    }
}
