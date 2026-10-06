package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.PlayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a mode asks the server for: a title all of it, an extra none —
 *  where each starts, how it ends, and how it is recovered. */
class PlayModeTest {
    private val extra = PlayMode.Extra(itemId = "m1", extraId = "x1")

    @Test
    fun `an extra asks for none of what a title does`() {
        assertEquals(
            PlayRequests(
                playInfo = false,
                progress = false,
                watched = false,
                segments = false,
                subtitles = false,
                trickplay = false,
                nextUp = false,
                prewarm = false,
                telemetry = false,
            ),
            extra.requests,
        )
    }

    @Test
    fun `a title asks for all of it, however it was opened`() {
        val all = PlayRequests(
            playInfo = true,
            progress = true,
            watched = true,
            segments = true,
            subtitles = true,
            trickplay = true,
            nextUp = true,
            prewarm = true,
            telemetry = true,
        )
        for (title in listOf(
            PlayMode.Title("m1"),
            PlayMode.Title("m1", fromStart = true),
            PlayMode.Title("e1", fromBinge = true),
            PlayMode.Title("m1", resumeSec = 754),
        )) {
            assertEquals(title.toString(), all, title.requests)
        }
    }

    @Test
    fun `an extra starts at 0, and a reload of it where the viewer was`() {
        assertEquals(0, startSec(extra, atSec = null))
        assertEquals(42, startSec(extra, atSec = 42))
    }

    @Test
    fun `a title starts where the viewer left off, unless told where`() {
        // Null: the saved progress, read from the server.
        assertNull(startSec(PlayMode.Title("m1"), atSec = null))
        assertNull(startSec(PlayMode.Title("e1", fromBinge = true), atSec = null))
        assertEquals(0, startSec(PlayMode.Title("m1", fromStart = true), atSec = null))
        // Zap's scene.
        assertEquals(754, startSec(PlayMode.Title("m1", resumeSec = 754), atSec = null))
        // A reload goes on at the playhead, whatever opened the title.
        assertEquals(30, startSec(PlayMode.Title("m1", fromStart = true), atSec = 30))
        assertEquals(30, startSec(PlayMode.Title("m1", resumeSec = 754), atSec = 30))
        assertEquals(0, startSec(PlayMode.Title("m1"), atSec = -5))
    }

    @Test
    fun `an extra closes at its end, back to its title - a title does not`() {
        assertTrue(extra.closesAtEnd)
        assertFalse(PlayMode.Title("m1").closesAtEnd)
    }

    @Test
    fun `an extra is recovered as the packaged file it is`() {
        assertEquals("packaged", streamMode(extra, info = null))
        assertEquals("transcode", streamMode(PlayMode.Title("m1"), PlayInfo(mode = "transcode")))
        assertNull(streamMode(PlayMode.Title("m1"), info = null))
    }
}
