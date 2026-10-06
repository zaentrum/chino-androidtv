package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a mode asks the server for, and where it starts. */
class PlayModeTest {
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
}
