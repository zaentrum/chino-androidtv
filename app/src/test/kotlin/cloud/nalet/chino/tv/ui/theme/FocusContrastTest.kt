package cloud.nalet.chino.tv.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The focus style's contrast, computed from the colour tokens (Color.kt) the
 * way WCAG 2.2 does: the ring at least 3:1 against everything around a
 * focused item (SC 1.4.11, 2.4.13) - the page, the surfaces its neighbours
 * sit on, the focused surface a row's ring is drawn on - and the text on the
 * focused surface at least 4.5:1 (SC 1.4.3). Where the design system's token
 * suite publishes a pair, the number here is its number.
 */
class FocusContrastTest {

    @Test
    fun `the tokens are the design system's`() {
        assertEquals(Color(0xFF00A4DC), ChinoFocusRing) // --focus-ring, cloud-cyan
        assertEquals(Color(0xFF2A3142), ChinoSurfaceFocus) // --surface-focus
        assertEquals(ChinoFocusRing, ChinoFocus.Ring)
        assertEquals(ChinoSurfaceFocus, ChinoFocus.Surface)
        assertEquals(ChinoAccent, ChinoFocus.CameFrom) // --cloud-blue
    }

    @Test
    fun `the ring stands out against the page and every surface beside it`() {
        // A focused item against its unfocused neighbours: the page and the
        // canvas steps they sit on.
        assertPair("ring on bg", ChinoFocusRing, ChinoBg, published = 6.70)
        assertPair("ring on surface", ChinoFocusRing, ChinoSurface, published = 6.34)
        assertPair("ring on surface-2", ChinoFocusRing, ChinoSurfaceHi, published = 6.02)
        // A row's ring is drawn inside its edge, on the focused surface.
        assertPair("ring on surface-focus", ChinoFocusRing, ChinoSurfaceFocus, published = 4.54)
        // The player's controls sit on the chrome's gradient to black.
        assertAtLeast("ring on black", ChinoFocusRing, Color.Black, 3.0)
        assertAtLeast("ring on the chrome's 80 % black over white video", ChinoFocusRing, Color(0xCC000000).compositeOver(Color.White), 3.0)
    }

    @Test
    fun `the text on the focused surface is readable, one step brighter`() {
        assertPair("fg on surface-focus", ChinoFg, ChinoSurfaceFocus, published = 10.41, min = 4.5)
        assertPair("fg-2 on surface-focus", ChinoText, ChinoSurfaceFocus, published = 8.42, min = 4.5)
        assertPair("fg-muted on surface-focus", ChinoMuted, ChinoSurfaceFocus, published = 6.46, min = 4.5)
        // fg-dim would not be readable there; it is brightened to fg-muted.
        assertTrue(contrast(ChinoDim, ChinoSurfaceFocus) < 4.5)
        assertEquals(ChinoMuted, ChinoFocus.text(ChinoDim, focused = true))
        assertEquals(ChinoText, ChinoFocus.text(ChinoMuted, focused = true))
        assertEquals(ChinoText, ChinoFocus.text(ChinoText, focused = true))
        assertEquals(ChinoMuted, ChinoFocus.text(ChinoMuted, focused = false))
        // The title: the brightest text colour; a brighter one stays.
        assertEquals(ChinoFg, ChinoFocus.title(ChinoText, focused = true))
        assertEquals(ChinoFg, ChinoFocus.title(ChinoMuted, focused = true))
        assertEquals(Color.White, ChinoFocus.title(Color.White, focused = true))
        assertEquals(ChinoText, ChinoFocus.title(ChinoText, focused = false))
        // The other colours text keeps on a focused row: white titles, the
        // accent's episode codes and ratings, a destructive entry's red.
        assertAtLeast("white on surface-focus", Color.White, ChinoSurfaceFocus, 4.5)
        assertAtLeast("accent on surface-focus", ChinoAccent, ChinoSurfaceFocus, 4.5)
        assertAtLeast("error on surface-focus", ChinoError, ChinoSurfaceFocus, 4.5)
    }

    @Test
    fun `where you came from is marked apart from the ring`() {
        // A bar in the accent along the left edge, on the row's tint at rest
        // and on the focused surface while focused.
        val tint = ChinoAccent.copy(alpha = 0.18f).compositeOver(ChinoSurface)
        assertAtLeast("bar on its tint", ChinoFocus.CameFrom, tint, 3.0)
        assertAtLeast("bar on surface-focus", ChinoFocus.CameFrom, ChinoSurfaceFocus, 3.0)
        assertTrue(ChinoFocus.CameFrom != ChinoFocus.Ring)
    }

    @Test
    fun `the style's sizes and timing are the design system's`() {
        // In the TV's dp, 1/960 of the screen: this app lays out at 0.75.
        assertEquals(4f, ChinoFocus.RingWidth.value, 0.001f) // 3 dp
        assertEquals(2f / 0.75f, ChinoFocus.RingGap.value, 0.001f) // 2 dp
        assertEquals(4f / 0.75f, ChinoFocus.CameFromWidth.value, 0.001f) // 4 dp
        assertEquals(16f, ChinoFocus.Room.value, 0.001f) // 12 dp
        assertEquals(1.02f, FocusKind.Row.growth)
        assertEquals(1.05f, FocusKind.Card.growth)
        assertEquals(1.05f, FocusKind.Control.growth)
        assertTrue(!FocusKind.Row.ringOutside && FocusKind.Card.ringOutside && FocusKind.Control.ringOutside)
        // Rows and plain controls light up; a card's artwork stays.
        assertTrue(FocusKind.Row.lightsUp && FocusKind.Control.lightsUp && !FocusKind.Card.lightsUp)
        assertEquals(150, ChinoFocus.SettleMillis)
    }

    private fun assertPair(name: String, fg: Color, bg: Color, published: Double, min: Double = 3.0) {
        val ratio = contrast(fg, bg)
        assertEquals("$name: the design system's number", published, ratio, 0.01)
        assertTrue("$name is ${"%.2f".format(ratio)}:1, below $min:1", ratio >= min)
    }

    private fun assertAtLeast(name: String, fg: Color, bg: Color, min: Double) {
        val ratio = contrast(fg, bg)
        assertTrue("$name is ${"%.2f".format(ratio)}:1, below $min:1", ratio >= min)
    }

    companion object {
        /** WCAG 2.2's contrast ratio of two opaque colours. */
        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** WCAG 2.2's relative luminance of an sRGB colour. */
        fun luminance(c: Color): Double {
            fun channel(v: Float): Double {
                val s = v.toDouble()
                return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
        }
    }
}
