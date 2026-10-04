package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRecoveryTest {
    private val at = 754_000L // a spot 12:34 into the title

    @Test
    fun `a packaged title that fails is tried in place, rebuilt at its quality, then the error shows`() {
        val r = PlaybackRecovery()
        val steps = List(6) { r.onError("packaged", "auto", at) }
        assertEquals(
            listOf(
                Recovery.RetryInPlace, Recovery.RetryInPlace, Recovery.RetryInPlace,
                Recovery.Reload("auto"),
                // The rebuilt player failing at the spot again: no more tries.
                Recovery.GiveUp, Recovery.GiveUp,
            ),
            steps,
        )
    }

    @Test
    fun `a packaged title is never moved onto the on-the-fly ladder`() {
        for (q in listOf("auto", "v1", "high", "medium")) {
            val r = PlaybackRecovery()
            val steps = List(8) { r.onError("packaged", q, at) } +
                List(8) { r.onStall("packaged", q, at, loading = false, bufferedAheadMs = 0) }
            assertEquals(q, emptyList<Recovery>(), steps.filterIsInstance<Recovery.StepDown>())
            // A rebuild keeps the rung the viewer picked.
            assertEquals(q, listOf(Recovery.Reload(q)), steps.filterIsInstance<Recovery.Reload>())
        }
    }

    @Test
    fun `an on-the-fly title steps down its ladder, and the error shows at low`() {
        for (mode in listOf("transcode", "remux", "passthrough", null)) {
            val r = PlaybackRecovery()
            assertEquals(Recovery.StepDown("medium"), r.onError(mode, "high", at))
            assertEquals(Recovery.StepDown("low"), r.onError(mode, "medium", at))
            assertEquals(Recovery.GiveUp, r.onError(mode, "low", at))
        }
    }

    @Test
    fun `trouble well past the last spot is new trouble`() {
        val r = PlaybackRecovery()
        repeat(3) { r.onError("packaged", "auto", at) }
        assertEquals(Recovery.Reload("auto"), r.onError("packaged", "auto", at + 20_000))
        // Played 30 s past it: the tries start over, in place first.
        assertEquals(Recovery.RetryInPlace, r.onError("packaged", "auto", at + SPOT_PASSED_MS))
        // The viewer went back a long way: another spot again.
        repeat(2) { r.onError("packaged", "auto", at + SPOT_PASSED_MS) }
        assertEquals(Recovery.RetryInPlace, r.onError("packaged", "auto", 60_000L))
    }

    @Test
    fun `a packaged title that stalls while fetching is left to load`() {
        val r = PlaybackRecovery()
        repeat(10) {
            assertEquals(Recovery.Wait, r.onStall("packaged", "auto", at, loading = true, bufferedAheadMs = 0))
        }
        // Waiting spends no tries: the first stuck stall is still nudged in place.
        assertEquals(Recovery.RetryInPlace, r.onStall("packaged", "auto", at, loading = false, bufferedAheadMs = 0))
    }

    @Test
    fun `a packaged title stuck on what it holds is nudged, rebuilt, then left be`() {
        val r = PlaybackRecovery()
        val steps = List(6) { r.onStall("packaged", "v2", at, loading = true, bufferedAheadMs = 4_000) }
        assertEquals(
            listOf(
                Recovery.RetryInPlace, Recovery.RetryInPlace, Recovery.RetryInPlace,
                Recovery.Reload("v2"),
                Recovery.Wait, Recovery.Wait,
            ),
            steps,
        )
    }

    @Test
    fun `an on-the-fly title that stalls steps down, and waits at low`() {
        val r = PlaybackRecovery()
        assertEquals(Recovery.StepDown("medium"), r.onStall("transcode", "high", at, loading = true, bufferedAheadMs = 0))
        assertEquals(Recovery.StepDown("low"), r.onStall("transcode", "medium", at, loading = true, bufferedAheadMs = 0))
        assertEquals(Recovery.Wait, r.onStall("transcode", "low", at, loading = true, bufferedAheadMs = 0))
    }

    @Test
    fun `the on-the-fly ladder reads any q that is not one of its rungs as high`() {
        assertEquals("medium", lowerOnTheFlyRung("high"))
        assertEquals("low", lowerOnTheFlyRung("Medium"))
        assertNull(lowerOnTheFlyRung("low"))
        assertEquals("medium", lowerOnTheFlyRung("v1"))
        assertEquals("medium", lowerOnTheFlyRung("auto"))
    }
}
