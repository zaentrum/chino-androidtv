package cloud.nalet.chino.tv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seek bar's colours are the design system's tokens, its stripes too:
 *  no colour of the bar's own; and every stripe stands out from what lies
 *  under and beside it - the loaded part and the track. */
class SeekBarLookTest {
    /** The design system's colour tokens (tokens/tokens.json, v0.3.0). */
    private val tokens = mapOf(
        "bg" to 0xFF0B0F19, "bg-2" to 0xFF0D1117, "surface" to 0xFF11161F, "surface-2" to 0xFF161B26,
        "border" to 0xFF1F2633, "border-2" to 0xFF2A3142, "surface-selected" to 0xFF111B2A,
        "fg" to 0xFFE6E6E6, "fg-2" to 0xFFC9D1D9, "fg-muted" to 0xFFAEB8C2, "fg-dim" to 0xFF7B8597,
        "cloud-blue" to 0xFF58A6FF, "cloud-cyan" to 0xFF00A4DC,
        "signal-green" to 0xFF2EA043, "signal-amber" to 0xFFF2B233, "signal-red" to 0xFFF85149,
        "border-control" to 0xFF646C7A,
        "information-bg" to 0xFF15202E, "information-border" to 0xFF3F6697,
        "success-bg" to 0xFF101F1B, "success-border" to 0xFF2C6342,
        "warning-bg" to 0xFF24211A, "warning-border" to 0xFF846B3B,
        "error-bg" to 0xFF25171C, "error-border" to 0xFF873F45,
    ).mapValues { (_, v) -> Color(v) }

    private fun token(c: Color): String? = tokens.entries.firstOrNull { it.value.toArgb() == c.toArgb() }?.key

    private val kinds = listOf("intro", "recap", "credits", "intermission", "chapter")

    /** WCAG's contrast ratio of two opaque colours. */
    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /** A grey: no hue to set it apart from the grey loaded part, only its
     *  lightness. */
    private fun isGrey(c: Color): Boolean {
        val max = maxOf(c.red, c.green, c.blue)
        return max == 0f || (max - minOf(c.red, c.green, c.blue)) / max < 0.25f
    }

    @Test
    fun `every colour of the bar and every stripe is a token`() {
        val bar = listOf(SeekBarLook.Track, SeekBarLook.Buffered, SeekBarLook.Played, SeekBarLook.Thumb)
        val stripes = kinds.map(SeekBarLook::segment)
        for (c in bar + stripes) assertTrue("#%08X".format(c.toArgb()), token(c) != null)
        assertEquals("signal-amber", token(SeekBarLook.segment("intro")))
        assertEquals("fg-2", token(SeekBarLook.segment("Recap")))
        // A recap ahead never reads as the played part.
        assertNotEquals(SeekBarLook.Played, SeekBarLook.segment("recap"))
        assertEquals(stripes.size, stripes.distinct().size)
    }

    @Test
    fun `every stripe stands out from the track and the loaded part`() {
        for (kind in kinds) {
            val c = SeekBarLook.segment(kind)
            // Non-text UI, 3:1 (WCAG 2.2 AA): a stripe far ahead, on the track.
            assertTrue("$kind on the track: ${contrast(c, SeekBarLook.Track)}", contrast(c, SeekBarLook.Track) >= 3f)
            // A grey stripe on the loaded part shows by its lightness alone.
            if (isGrey(c)) {
                assertTrue("$kind on the loaded part: ${contrast(c, SeekBarLook.Buffered)}", contrast(c, SeekBarLook.Buffered) >= 2.5f)
            }
        }
        // A recap opens an episode, where the buffer covers it at once: 3:1
        // on the loaded part too - never less than the #79B8FF it was (2.55
        // and 6.26) - and apart from the played part beside it.
        val recap = SeekBarLook.segment("recap")
        assertTrue(contrast(recap, SeekBarLook.Buffered) >= 3f)
        assertTrue(contrast(recap, SeekBarLook.Track) >= 6.26f)
        assertTrue(contrast(recap, SeekBarLook.Played) >= 1.5f)
    }

    @Test
    fun `an episode's recap, intro and credits are never two greys`() {
        val greys = listOf("recap", "intro", "credits").map(SeekBarLook::segment).filter(::isGrey)
        assertTrue("greys: ${greys.map(::token)}", greys.size <= 1)
    }
}
