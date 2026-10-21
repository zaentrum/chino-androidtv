package cloud.nalet.chino.tv.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV's playback-quality reports carry chino-web's event names and fields
 * (src/lib/qoe.ts and its README), so chino-api's metrics count them: what
 * each happening of a playback reports, and what it does not.
 */
class PlaybackQualityTest {
    private var clock = 1_000L
    private val sent = mutableListOf<Pair<String, Map<String, String>>>()
    private val quality = PlaybackQuality(now = { clock }, send = { kind, payload -> sent += kind to payload })

    private fun kinds() = sent.map { it.first }
    private fun last(kind: String) = sent.last { it.first == kind }.second

    /** A playback under way: its source asked for, its first frame shown, playing. */
    private fun started(atMs: Long = 0L) {
        quality.mode = "packaged"
        quality.quality = "auto"
        quality.sourceRequested()
        clock += 400
        quality.firstFrame(positionMs = atMs, onScreen = Rendition(height = 1080, bitrate = 7_400_000))
        quality.playing()
        sent.clear()
    }

    @Test
    fun `the first frame says how long the start took and what it started on`() {
        quality.mode = "packaged"
        clock += 250 // the screen asks the server for the title first
        quality.sourceRequested()
        clock += 750
        quality.firstFrame(positionMs = 0L, onScreen = Rendition(height = 720, bitrate = 3_200_000))

        assertEquals(listOf("first_frame"), kinds())
        assertEquals(
            mapOf(
                "attempt" to "1",
                "startup_ms" to "1000",
                "request_ms" to "750",
                "sources" to "1",
                "mode" to "packaged",
                "height" to "720",
                "bitrate" to "3200000",
                "resume" to "false",
                "start_bucket" to "0",
                "hidden" to "false",
                "click_to_play" to "false",
                "via" to "frame_callback",
            ),
            last("first_frame"),
        )
    }

    @Test
    fun `a start is reported once, a resume as one, and the bandwidth it was made on with it`() {
        quality.sourceRequested()
        quality.bandwidthEstimate(12_000_000)
        val first = SegmentFetch(startMs = clock).apply {
            kind = SegmentKind.VIDEO
            height = 720
            firstByteMs = clock + 80
            endMs = clock + 600
            bytes = 2_000_000
            mediaDurationMs = 6_000
        }
        quality.segmentLoaded(first, playing = false) // in before the first frame
        quality.firstFrame(positionMs = 1_250_000L, onScreen = null)
        // The renderer shows a first frame again after every seek: no report.
        quality.firstFrame(positionMs = 0L, onScreen = null)

        assertEquals(listOf("bandwidth", "first_frame"), kinds())
        assertEquals(
            mapOf(
                "bps" to "12000000",
                "frag_bytes" to "2000000",
                "frag_duration_ms" to "6000",
                "frag_load_ms" to "600",
                "frag_ttfb_ms" to "80",
                "height" to "720",
                "trigger" to "first_frame",
            ),
            last("bandwidth"),
        )
        val frame = last("first_frame")
        assertEquals("true", frame["resume"])
        assertEquals("5-30m", frame["start_bucket"])
        assertEquals("unknown", frame["mode"])
        assertNull(frame["height"])
    }

    @Test
    fun `a first frame before its segment is in reports the start's bandwidth with that segment`() {
        // Media3 shows a frame while the first segment still loads.
        quality.sourceRequested()
        quality.bandwidthEstimate(9_000_000)
        quality.firstFrame(positionMs = 0L, onScreen = Rendition(1080, 7_400_000))
        quality.playing()
        assertEquals(listOf("first_frame"), kinds())

        val segment = SegmentFetch(startMs = clock).apply {
            kind = SegmentKind.VIDEO
            height = 1080
            firstByteMs = clock + 120
            endMs = clock + 1_400
            bytes = 5_000_000
        }
        quality.segmentLoaded(segment, playing = true)
        assertEquals("first_frame", last("bandwidth")["trigger"])
        assertEquals("120", last("bandwidth")["frag_ttfb_ms"])
        // The next one within 30 s reports nothing.
        clock += 5_000
        quality.segmentLoaded(segment, playing = true)
        assertEquals(1, kinds().count { it == "bandwidth" })
    }

    @Test
    fun `the sources a start took, and whether the screen was hidden before it`() {
        quality.sourceRequested()
        quality.hidden()
        clock += 3_000
        quality.sourceRequested() // a recovery rebuilt the player
        clock += 500
        quality.firstFrame(positionMs = 0L, onScreen = null)

        val frame = last("first_frame")
        assertEquals("2", frame["sources"])
        assertEquals("500", frame["request_ms"])
        assertEquals("true", frame["hidden"])
    }

    @Test
    fun `a stall mid-play is a waiting at its start and a rebuffer at its end`() {
        started()
        clock += 60_000
        // The segment loading when it stops: asked for 300 ms before.
        val fetch = SegmentFetch(startMs = clock - 300).apply {
            kind = SegmentKind.VIDEO
            height = 1080
            firstByteMs = clock - 100
            bytes = 250_000
            totalBytes = 1_000_000
        }
        quality.waiting(positionMs = 61_234L, segment = fetch)
        assertEquals(listOf("waiting"), kinds())
        assertEquals(mapOf("t" to "61.2", "q" to "auto"), last("waiting"))

        clock += 4_500
        quality.playing()
        assertEquals(listOf("waiting", "rebuffer"), kinds())
        assertEquals(
            mapOf(
                "duration_ms" to "4500",
                "position" to "61.2",
                "cause" to "waiting",
                "end" to "playing",
                "seeked" to "false",
                "mode" to "packaged",
                "seg_kind" to "video",
                "seg_loaded" to "250000",
                "seg_total" to "1000000",
                // Still loading when the stall ended: timed until then.
                "seg_load_ms" to "4800",
                "seg_ttfb_ms" to "200",
            ),
            last("rebuffer"),
        )
    }

    @Test
    fun `the wait at the start, after a seek or while paused is no stall`() {
        quality.sourceRequested()
        quality.waiting(positionMs = 0L, segment = null) // before the first frame
        quality.firstFrame(positionMs = 0L, onScreen = null)
        quality.playing()
        quality.seeked()
        quality.waiting(positionMs = 600_000L, segment = null) // the seek's own wait
        quality.playing()
        quality.paused()
        quality.waiting(positionMs = 600_000L, segment = null) // paused

        assertTrue(kinds().none { it == "waiting" || it == "rebuffer" })
    }

    @Test
    fun `a seek in a stall leaves it standing, and the stall ends where playback stops`() {
        started()
        quality.waiting(positionMs = 10_000L, segment = null)
        quality.seeked()
        clock += 2_000
        quality.playing()
        assertEquals("true", last("rebuffer")["seeked"])
        assertEquals("playing", last("rebuffer")["end"])
        assertNull(last("rebuffer")["seg_kind"])

        for ((how, end) in listOf<Pair<() -> Unit, String>>(
            { quality.paused() } to "pause",
            { quality.hidden() } to "hidden",
            { quality.stop("exit") } to "exit",
            { quality.stop("error") } to "error",
        )) {
            quality.playing()
            quality.waiting(positionMs = 20_000L, segment = null)
            clock += 1_000
            how()
            assertEquals(end, last("rebuffer")["end"])
            assertEquals("1000", last("rebuffer")["duration_ms"])
        }
    }

    @Test
    fun `a new source carries a stall on and waits for playback before the next`() {
        started()
        quality.waiting(positionMs = 30_000L, segment = null)
        clock += 12_000
        quality.sourceRequested() // the recovery drops a rung
        clock += 3_000
        quality.playing()
        assertEquals("15000", last("rebuffer")["duration_ms"])
        // The new source's own start is not a stall.
        quality.sourceRequested()
        quality.waiting(positionMs = 30_000L, segment = null)
        assertEquals(1, kinds().count { it == "rebuffer" })
    }

    @Test
    fun `the bandwidth while it plays, with the last video segment, at most every 30 seconds`() {
        started()
        quality.bandwidthEstimate(18_500_000)
        // The first segment in after the first frame is the start's.
        quality.segmentLoaded(SegmentFetch(startMs = clock).apply { kind = SegmentKind.VIDEO; endMs = clock + 500 }, playing = true)
        assertEquals("first_frame", last("bandwidth")["trigger"])
        sent.clear()
        clock += 30_000
        val segment = SegmentFetch(startMs = clock).apply {
            kind = SegmentKind.VIDEO
            height = 1080
            firstByteMs = clock + 40
            endMs = clock + 900
            bytes = 4_100_000
            mediaDurationMs = 4_000
        }
        quality.segmentLoaded(segment, playing = true)
        assertEquals(
            mapOf(
                "bps" to "18500000",
                "frag_bytes" to "4100000",
                "frag_duration_ms" to "4000",
                "frag_load_ms" to "900",
                "frag_ttfb_ms" to "40",
                "height" to "1080",
                "trigger" to "interval",
            ),
            last("bandwidth"),
        )
        clock += 10_000
        quality.segmentLoaded(segment, playing = true) // too soon
        clock += 20_000
        quality.segmentLoaded(segment, playing = false) // paused
        quality.segmentLoaded(SegmentFetch(startMs = clock).apply { kind = SegmentKind.AUDIO; endMs = clock }, playing = true)
        assertEquals(1, kinds().count { it == "bandwidth" })
        quality.segmentLoaded(segment, playing = true)
        assertEquals(2, kinds().count { it == "bandwidth" })
    }

    @Test
    fun `no bandwidth without an estimate, or before the first frame`() {
        quality.sourceRequested()
        val segment = SegmentFetch(startMs = clock).apply { kind = SegmentKind.VIDEO; endMs = clock + 100 }
        quality.bandwidthEstimate(9_000_000)
        quality.segmentLoaded(segment, playing = true)
        assertTrue(kinds().isEmpty())
        assertNull(bandwidthPayload(0, segment, "interval"))
    }

    @Test
    fun `a rendition switch says from and to, and whether the viewer picked it`() {
        started()
        quality.renditionOnScreen(Rendition(1080, 7_400_000)) // the one it started on
        quality.renditionOnScreen(Rendition(720, 3_200_000)) // the player stepped down
        assertEquals(
            mapOf(
                "from_height" to "1080",
                "from_bitrate" to "7400000",
                "to_height" to "720",
                "to_bitrate" to "3200000",
                "manual" to "false",
                "mode" to "packaged",
            ),
            last("level_switch"),
        )
        // A pick in the quality menu: the next source's first switch is the viewer's.
        quality.manualPick()
        quality.sourceRequested()
        quality.renditionOnScreen(Rendition(1080, 7_400_000))
        assertEquals("true", last("level_switch")["manual"])
        quality.renditionOnScreen(Rendition(480, 1_400_000))
        assertEquals("false", last("level_switch")["manual"])
        assertEquals(2 + 1, kinds().count { it == "level_switch" })
    }

    @Test
    fun `a fatal error is chino-web's hls_fatal, and a decoder's its media_error`() {
        started(atMs = 90_000L)
        quality.fatal(
            errorCode = 2004, // ERROR_CODE_IO_BAD_HTTP_STATUS
            errorCodeName = "ERROR_CODE_IO_BAD_HTTP_STATUS",
            message = "Response code: 502",
            url = "https://media.example.org/api/v1/items/x/play/seg-12.m4s?stream=secret&q=auto",
            httpStatus = 502,
            positionMs = 90_500L,
        )
        assertEquals(listOf("hls_fatal"), kinds())
        assertEquals(
            mapOf(
                "type" to "networkError",
                "details" to "ERROR_CODE_IO_BAD_HTTP_STATUS",
                "reason" to "Response code: 502",
                // Without its query: the stream token stays home.
                "url" to "https://media.example.org/api/v1/items/x/play/seg-12.m4s",
                "httpStatus" to "502",
            ),
            last("hls_fatal"),
        )

        quality.fatal(4001, "ERROR_CODE_DECODER_INIT_FAILED", "no decoder", null, null, positionMs = 91_000L)
        assertEquals(listOf("hls_fatal", "hls_fatal", "media_error"), kinds())
        assertEquals(mapOf("code" to "3", "name" to "DECODE", "message" to "no decoder", "t" to "91.0", "q" to "auto"), last("media_error"))
        assertEquals("mediaError", sent[1].second["type"])

        quality.fatal(4004, "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES", null, null, null, positionMs = 0L)
        assertEquals("SRC_NOT_SUPPORTED", last("media_error")["name"])
        assertEquals("4", last("media_error")["code"])
    }

    @Test
    fun `the error types and media errors by Media3's codes`() {
        assertEquals("otherError", errorType(1000))
        assertEquals("networkError", errorType(2001))
        assertEquals("muxError", errorType(3001))
        assertEquals("mediaError", errorType(4005))
        assertEquals("mediaError", errorType(5001))
        assertEquals("keySystemError", errorType(6001))
        assertNull(mediaError(2001))
        assertNull(mediaError(5001))
        assertEquals(3 to "DECODE", mediaError(4001)) // the decoder could not start
        assertEquals(3 to "DECODE", mediaError(4003)) // decoding failed
        assertEquals(4 to "SRC_NOT_SUPPORTED", mediaError(4004)) // beyond the TV's decoder
        assertEquals(4 to "SRC_NOT_SUPPORTED", mediaError(4005)) // a format it has no decoder for
    }

    @Test
    fun `a session stays within chino-web's budgets`() {
        started()
        repeat(150) { i ->
            quality.renditionOnScreen(Rendition(if (i % 2 == 0) 720 else 1080, null))
        }
        assertEquals(100, kinds().count { it == "level_switch" })
        repeat(30) { quality.fatal(2001, "ERROR_CODE_IO_UNSPECIFIED", null, null, null, 0L) }
        assertEquals(20, kinds().count { it == "hls_fatal" })
    }

    @Test
    fun `what a payload is - play modes, start buckets, unknown values left out`() {
        assertEquals("transcode", playMode("transcode"))
        assertEquals("unknown", playMode(null))
        assertEquals("unknown", playMode("direct"))
        assertEquals("0", startBucket(999L))
        assertEquals("<5m", startBucket(1_000L))
        assertEquals("30-60m", startBucket(1_800_000L))
        assertEquals("60m+", startBucket(3_600_000L))
        assertEquals(Rendition(null, null), rendition(-1, -1))
        assertTrue(segmentPayload(null, 0L).isEmpty())
        val loading = SegmentFetch(startMs = 100L).apply { kind = SegmentKind.AUDIO }
        assertEquals(mapOf("seg_loaded" to "0", "seg_load_ms" to "400"), segmentPayload(loading, 500L))
        assertEquals(mapOf("from_height" to "720", "manual" to "true", "mode" to "remux"), levelSwitchPayload(Rendition(720, null), Rendition(null, null), true, "remux"))
    }
}
