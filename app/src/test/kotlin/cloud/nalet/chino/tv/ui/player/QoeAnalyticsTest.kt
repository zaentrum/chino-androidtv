package cloud.nalet.chino.tv.ui.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What Media3 says of a playback, as the TV player's quality reports say it:
 * its analytics events and its data source's transfers in, chino-web's
 * events out. Under Robolectric for Media3's own types (they need Android's
 * Uri).
 */
@RunWith(RobolectricTestRunner::class)
class QoeAnalyticsTest {
    private var clock = 10_000L
    private val sent = mutableListOf<Pair<String, Map<String, String>>>()
    private val quality = PlaybackQuality(now = { clock }, send = { kind, payload -> sent += kind to payload })
    private val qoe = QoeAnalytics(quality, clock = { clock })

    private fun kinds() = sent.map { it.first }
    private fun last(kind: String) = sent.last { it.first == kind }.second

    private fun at(positionMs: Long = 0L) = AnalyticsListener.EventTime(
        clock, Timeline.EMPTY, 0, null, positionMs, Timeline.EMPTY, 0, null, positionMs, 0L,
    )

    private fun spec(segment: String) =
        DataSpec(Uri.parse("https://media.example.org/api/v1/items/x/play/$segment?stream=token&q=auto"))

    private fun info(task: Long, spec: DataSpec, bytes: Long = 0L, loadMs: Long = 0L) =
        LoadEventInfo(task, spec, spec.uri, emptyMap(), clock, loadMs, bytes)

    private val hd = Format.Builder().setHeight(1080).setPeakBitrate(7_400_000).build()
    private val sd = Format.Builder().setHeight(720).setPeakBitrate(3_200_000).build()

    private fun video(format: Format = hd) =
        MediaLoadData(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_DEFAULT, format, C.SELECTION_REASON_ADAPTIVE, null, 40_000L, 44_000L)

    /** The player's HTTP data source, as the transfer callbacks see it. */
    private val source = object : DataSource {
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long = C.LENGTH_UNSET.toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = mapOf("content-length" to listOf("4100000"))
        override fun close() = Unit
    }

    /** A segment the player loads: its request out, its first byte [ttfbMs]
     *  later, [bytes] in; done after [loadMs] unless [done] is false. */
    private fun load(task: Long, segment: String, ttfbMs: Long, bytes: Int, loadMs: Long, done: Boolean = true) {
        val spec = spec(segment)
        qoe.onLoadStarted(at(), info(task, spec), video())
        qoe.onTransferInitializing(source, spec, true)
        clock += ttfbMs
        qoe.onTransferStart(source, spec, true)
        qoe.onBytesTransferred(source, spec, true, bytes)
        if (!done) return
        clock += loadMs - ttfbMs
        qoe.onTransferEnd(source, spec, true)
        qoe.onLoadCompleted(at(), info(task, spec, bytes = bytes.toLong(), loadMs = loadMs), video())
    }

    /** The player starts: its source asked for, to play, a first segment in. */
    private fun start() {
        quality.mode = "packaged"
        quality.quality = "auto"
        quality.sourceRequested()
        qoe.onPlayWhenReadyChanged(at(), true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        qoe.onPlaybackStateChanged(at(), Player.STATE_BUFFERING)
        qoe.onVideoInputFormatChanged(at(), hd, null)
        qoe.onBandwidthEstimate(at(), 735, 4_100_000L, 20_000_000L)
        load(task = 1, segment = "v1/seg-1.m4s", ttfbMs = 35, bytes = 4_100_000, loadMs = 735)
        qoe.onPlaybackStateChanged(at(), Player.STATE_READY)
        qoe.onRenderedFirstFrame(at(), Any(), clock)
        qoe.onIsPlayingChanged(at(), true)
    }

    @Test
    fun `the first frame, and the bandwidth it was made on with the segment's timings`() {
        start()
        // The start's own wait is no stall.
        assertEquals(listOf("bandwidth", "first_frame"), kinds())
        assertEquals(
            mapOf(
                "bps" to "20000000",
                "frag_bytes" to "4100000",
                "frag_duration_ms" to "4000",
                "frag_load_ms" to "735",
                "frag_ttfb_ms" to "35",
                "height" to "1080",
                "trigger" to "first_frame",
            ),
            last("bandwidth"),
        )
        val frame = last("first_frame")
        assertEquals("1080", frame["height"])
        assertEquals("7400000", frame["bitrate"])
        assertEquals("735", frame["request_ms"])
        assertEquals("packaged", frame["mode"])
    }

    @Test
    fun `a stall mid-play, with the segment that was loading, from waiting to playing`() {
        start()
        clock += 60_000
        // A segment that takes its time, still loading when playback stops.
        load(task = 2, segment = "v1/seg-16.m4s", ttfbMs = 1_200, bytes = 300_000, loadMs = 0, done = false)
        qoe.onIsPlayingChanged(at(64_000L), false)
        qoe.onPlaybackStateChanged(at(64_000L), Player.STATE_BUFFERING)
        assertEquals(mapOf("t" to "64.0", "q" to "auto"), last("waiting"))
        clock += 3_800
        qoe.onPlaybackStateChanged(at(64_000L), Player.STATE_READY)
        qoe.onIsPlayingChanged(at(64_000L), true)
        assertEquals(
            mapOf(
                "duration_ms" to "3800",
                "position" to "64.0",
                "cause" to "waiting",
                "end" to "playing",
                "seeked" to "false",
                "mode" to "packaged",
                "seg_kind" to "video",
                "seg_loaded" to "300000",
                "seg_total" to "4100000",
                "seg_load_ms" to "5000",
                "seg_ttfb_ms" to "1200",
            ),
            last("rebuffer"),
        )
    }

    @Test
    fun `no stall while paused or for a seek's wait`() {
        start()
        qoe.onPositionDiscontinuity(
            at(600_000L),
            Player.PositionInfo(null, 0, null, null, 0, 64_000L, 64_000L, C.INDEX_UNSET, C.INDEX_UNSET),
            Player.PositionInfo(null, 0, null, null, 0, 600_000L, 600_000L, C.INDEX_UNSET, C.INDEX_UNSET),
            Player.DISCONTINUITY_REASON_SEEK,
        )
        qoe.onIsPlayingChanged(at(600_000L), false)
        qoe.onPlaybackStateChanged(at(600_000L), Player.STATE_BUFFERING)
        qoe.onIsPlayingChanged(at(600_000L), true)
        qoe.onPlayWhenReadyChanged(at(600_000L), false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        qoe.onPlaybackStateChanged(at(600_000L), Player.STATE_BUFFERING)
        assertTrue(kinds().none { it == "waiting" || it == "rebuffer" })
    }

    @Test
    fun `the rendition the decoder takes is a switch from what was on screen`() {
        start()
        qoe.onVideoInputFormatChanged(at(), sd, null)
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
    }

    @Test
    fun `a fatal error, with the request that failed and its status`() {
        start()
        val failed = spec("v1/seg-17.m4s")
        val cause = HttpDataSource.InvalidResponseCodeException(502, "Bad Gateway", null, emptyMap(), failed, ByteArray(0))
        qoe.onPlayerError(at(68_000L), PlaybackException("Response code: 502", cause, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertEquals(
            mapOf(
                "type" to "networkError",
                "details" to "ERROR_CODE_IO_BAD_HTTP_STATUS",
                "reason" to "Response code: 502",
                "url" to "https://media.example.org/api/v1/items/x/play/v1/seg-17.m4s",
                "httpStatus" to "502",
            ),
            last("hls_fatal"),
        )
        assertTrue("media_error" !in kinds())

        qoe.onPlayerError(at(68_000L), PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals("DECODE", last("media_error")["name"])
        assertNull(last("hls_fatal")["url"])
    }

    @Test
    fun `what a segment is - the main track's media and init, an audio rendition's media`() {
        fun data(type: Int, track: Int) = MediaLoadData(type, track, null, C.SELECTION_REASON_UNKNOWN, null, C.TIME_UNSET, C.TIME_UNSET)
        assertEquals(SegmentKind.VIDEO, QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_DEFAULT)))
        assertEquals(SegmentKind.VIDEO, QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_VIDEO)))
        assertEquals(SegmentKind.INIT, QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_DEFAULT)))
        assertEquals(SegmentKind.AUDIO, QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_AUDIO)))
        assertNull(QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA_INITIALIZATION, C.TRACK_TYPE_AUDIO)))
        assertNull(QoeAnalytics.segmentKind(data(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_TEXT)))
        assertNull(QoeAnalytics.segmentKind(data(C.DATA_TYPE_MANIFEST, C.TRACK_TYPE_UNKNOWN)))
        assertNull(QoeAnalytics.mediaDuration(data(C.DATA_TYPE_MEDIA, C.TRACK_TYPE_DEFAULT)))
    }

    @Test
    fun `requests the player never claims do not pile up`() {
        repeat(QoeAnalytics.MAX_REQUESTS * 3) { i ->
            qoe.onTransferInitializing(source, spec("v1/playlist-$i.m3u8"), true)
        }
        // A segment still gets its timings after them.
        start()
        assertEquals("35", last("bandwidth")["frag_ttfb_ms"])
    }
}
