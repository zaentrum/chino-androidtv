package cloud.nalet.chino.tv.ui.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seek bar's colours are the design system's tokens, its stripes too:
 *  no colour of the bar's own. */
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

    @Test
    fun `every colour of the bar and every stripe is a token`() {
        val bar = listOf(SeekBarLook.Track, SeekBarLook.Buffered, SeekBarLook.Played, SeekBarLook.Thumb)
        val stripes = listOf("intro", "recap", "credits", "intermission", "chapter").map(SeekBarLook::segment)
        for (c in bar + stripes) assertTrue("#%08X".format(c.toArgb()), token(c) != null)
        assertEquals("signal-amber", token(SeekBarLook.segment("intro")))
        assertEquals("information-border", token(SeekBarLook.segment("Recap")))
        // A recap ahead never reads as the played part.
        assertNotEquals(SeekBarLook.Played, SeekBarLook.segment("recap"))
        assertEquals(stripes.size, stripes.distinct().size)
    }
}
