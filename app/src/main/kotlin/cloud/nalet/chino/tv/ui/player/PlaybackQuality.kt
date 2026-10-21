package cloud.nalet.chino.tv.ui.player

import androidx.media3.common.PlaybackException
import java.util.Locale
import kotlin.math.max

/*
 * Playback quality: what the player measures of how a title plays and
 * reports to chino-api's /api/v1/play/events, as chino-web does
 * (src/lib/qoe.ts): the first frame, a stall mid-play, the bandwidth it sees
 * and how long its segments take, a switch of the rendition on screen, and a
 * fatal error. chino-api counts startups, rebuffers, bandwidth and the
 * segments' time to first byte by these events' names and fields (its
 * README); the TV's events name their client, chino-androidtv, which it
 * counts as a TV.
 *
 * Pure: QoeAnalytics, a Media3 AnalyticsListener, hands in the times
 * (elapsedRealtime, ms) and what the player says; this keeps the session's
 * state and makes the payloads. Every value is a string, as the TV sends all
 * of its telemetry and chino-api reads it; a value that is not known is left
 * out, where chino-web sends null.
 */

/** How the title plays, as /play/info says; unknown for anything else. */
internal fun playMode(mode: String?): String = when (mode) {
    "packaged", "passthrough", "remux", "transcode" -> mode
    else -> "unknown"
}

/** A rendition: the height of its picture and the bitrate its variant
 *  declares (BANDWIDTH, bit/s); null where it is not known. */
internal data class Rendition(val height: Int?, val bitrate: Int?)

/** The rendition on screen is another one: by its height, or its bitrate. */
internal fun renditionChanged(from: Rendition?, to: Rendition): Boolean =
    from != null && (from.height != to.height || from.bitrate != to.bitrate)

/** What a segment fetch is: the video's init segment, or a media segment of
 *  the video or the audio. Subtitles and playlists are none. */
internal enum class SegmentKind(val wire: String) { INIT("init"), VIDEO("video"), AUDIO("audio") }

/**
 * A segment fetch as the player and its data source see it, times in ms
 * (elapsedRealtime): when its request went out, its first byte came and its
 * last; the bytes so far and its size once the server said it. The data
 * source fills it in on its loader thread while it loads; the player says
 * what it is.
 */
internal class SegmentFetch(@Volatile var startMs: Long) {
    /** What it is; null for a request the player has not claimed (yet). */
    @Volatile var kind: SegmentKind? = null
    /** The video's picture height, for a video segment. */
    @Volatile var height: Int? = null
    @Volatile var firstByteMs: Long? = null
    @Volatile var endMs: Long? = null
    @Volatile var bytes: Long = 0
    @Volatile var totalBytes: Long? = null
    /** How much media it holds, in ms; null where the player does not say. */
    @Volatile var mediaDurationMs: Long? = null
}

/** A start from the first second on is a resume. */
internal const val RESUME_FROM_MS = 1_000L

/** Where in the title a start was, coarsely. */
internal fun startBucket(positionMs: Long): String = when {
    positionMs < RESUME_FROM_MS -> "0"
    positionMs < 300_000L -> "<5m"
    positionMs < 1_800_000L -> "5-30m"
    positionMs < 3_600_000L -> "30-60m"
    else -> "60m+"
}

private fun ms(x: Long): String = max(0L, x).toString()

/** A position in seconds, to a tenth, as chino-web's are. */
private fun seconds(positionMs: Long): String = "%.1f".format(Locale.ROOT, positionMs / 1000.0)

/** A segment fetch as a payload: bytes in, its size if known, how long it has
 *  been loading (until its last byte, or until [now]) and when its first byte
 *  came. Empty without one. */
internal fun segmentPayload(fetch: SegmentFetch?, now: Long): Map<String, String> {
    if (fetch == null) return emptyMap()
    val end = fetch.endMs ?: now
    return buildMap {
        put("seg_loaded", max(0L, fetch.bytes).toString())
        fetch.totalBytes?.takeIf { it > 0 }?.let { put("seg_total", it.toString()) }
        put("seg_load_ms", ms(end - fetch.startMs))
        fetch.firstByteMs?.let { put("seg_ttfb_ms", ms(it - fetch.startMs)) }
    }
}

/** The player's estimate and the last video segment it loaded: its size, the
 *  media it holds, how long it took and when its first byte came. Null
 *  without an estimate. */
internal fun bandwidthPayload(bps: Long, last: SegmentFetch?, trigger: String): Map<String, String>? {
    if (bps <= 0) return null
    // Only a fetch that is done has a size and a time.
    val done = last?.takeIf { it.endMs != null }
    return buildMap {
        put("bps", bps.toString())
        if (done != null) {
            put("frag_bytes", done.bytes.toString())
            done.mediaDurationMs?.takeIf { it > 0 }?.let { put("frag_duration_ms", it.toString()) }
            put("frag_load_ms", ms(done.endMs!! - done.startMs))
            done.firstByteMs?.let { put("frag_ttfb_ms", ms(it - done.startMs)) }
        }
        last?.height?.let { put("height", it.toString()) }
        put("trigger", trigger)
    }
}

internal fun levelSwitchPayload(from: Rendition, to: Rendition, manual: Boolean, mode: String): Map<String, String> =
    buildMap {
        from.height?.let { put("from_height", it.toString()) }
        from.bitrate?.let { put("from_bitrate", it.toString()) }
        to.height?.let { put("to_height", it.toString()) }
        to.bitrate?.let { put("to_bitrate", it.toString()) }
        put("manual", manual.toString())
        put("mode", mode)
    }

/**
 * A fatal error as chino-web's hls_fatal says it: hls.js's error type for the
 * kind of Media3's error code, Media3's code name for its details, the
 * message for its reason, and the request that failed. The URL goes without
 * its query, which holds the stream token.
 */
internal fun fatalPayload(
    errorCode: Int,
    errorCodeName: String,
    message: String?,
    url: String?,
    httpStatus: Int?,
): Map<String, String> = buildMap {
    put("type", errorType(errorCode))
    put("details", errorCodeName)
    put("reason", message.orEmpty())
    url?.substringBefore('?')?.takeIf { it.isNotBlank() }?.let { put("url", it) }
    httpStatus?.let { put("httpStatus", it.toString()) }
}

/** hls.js's error types, by the thousands of Media3's error codes
 *  (PlaybackException): input and output, parsing, decoding, audio output,
 *  DRM, the rest. */
internal fun errorType(errorCode: Int): String = when (errorCode / 1000) {
    2 -> "networkError"
    3 -> "muxError"
    4, 5 -> "mediaError"
    6 -> "keySystemError"
    else -> "otherError"
}

/**
 * The browser's MediaError a decoder error is, as chino-web's media_error
 * names it (code and name): a format the TV cannot decode is
 * SRC_NOT_SUPPORTED (4), any other decoder failure DECODE (3). Null for an
 * error that is not the decoder's, which a browser does not report there.
 */
internal fun mediaError(errorCode: Int): Pair<Int, String>? = when (errorCode) {
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
    -> 4 to "SRC_NOT_SUPPORTED"
    // The decoder could not start, be found, or decode.
    in 4000..4999 -> 3 to "DECODE"
    else -> null
}

/** How many of each kind a session reports at most, and how far apart:
 *  chino-web's BUDGETS, and a cap of the TV's for the rest. A session is one
 *  player screen: a title, or an episode of a binge. */
internal object QoeBudgets {
    const val BANDWIDTH_EVERY_MS = 30_000L
    val max: Map<String, Pair<Int, Long>> = mapOf(
        "first_frame" to (10 to 0L),
        "rebuffer" to (100 to 0L),
        "waiting" to (100 to 0L),
        // Two a minute: 240 over a film of two hours, and room for a long session.
        "bandwidth" to (1000 to BANDWIDTH_EVERY_MS),
        "level_switch" to (100 to 0L),
        "hls_fatal" to (20 to 0L),
        "media_error" to (20 to 0L),
    )
}

/** Keeps a session within [QoeBudgets]: whether a report of [kind] may go
 *  now, counting it if so; [ignoreGap] lets one through sooner (the bandwidth
 *  at the first frame), still counted. */
internal class EventBudget {
    private val sent = HashMap<String, Pair<Int, Long>>()

    fun allow(kind: String, now: Long, ignoreGap: Boolean = false): Boolean {
        val (max, gap) = QoeBudgets.max[kind] ?: return true
        val s = sent[kind]
        if (s != null && s.first >= max) return false
        if (s != null && !ignoreGap && now - s.second < gap) return false
        sent[kind] = ((s?.first ?: 0) + 1) to now
        return true
    }
}

/**
 * One player screen's playback quality: the start, the stalls, the
 * bandwidth, the renditions and the fatal errors, reported to [send] with
 * chino-web's event names and fields. Its sources are the players the screen
 * builds for the title - a quality switch or a recovery builds another - and
 * it lives across them. Called on the main thread.
 */
internal class PlaybackQuality(
    private val now: () -> Long,
    private val send: (kind: String, payload: Map<String, String>) -> Unit,
) {
    /** How the title plays (/play/info's mode); the view model sets it. */
    var mode: String = "unknown"

    /** The quality the master is asked with ("auto", a rung); the view model sets it. */
    var quality: String = ""

    private val budget = EventBudget()
    private val startedAt = now()

    // The start: the sources asked for before the first frame, the latest one's
    // request, whether the screen was hidden meanwhile.
    private var sources = 0
    private var requestedAt: Long? = null
    private var hiddenBeforeFrame = false
    private var frameShown = false

    // The renditions on screen; a pick in the quality menu makes the next
    // source's first switch the viewer's.
    private var source = 0
    private var manualSource = -1
    private var rendition: Rendition? = null

    // The bandwidth the player estimates, and its last video segment.
    private var bps = 0L
    private var lastVideo: SegmentFetch? = null

    // The bandwidth the start was made on goes with the first video segment
    // that is in: Media3 shows a first frame while that segment still loads.
    private var startBandwidthDue = false

    // Stalls that begin mid-play: once the first frame was shown and playback
    // has been under way since the last seek, pause or new source.
    private var moving = false
    private var stall: Stall? = null

    private class Stall(
        val at: Long,
        val positionMs: Long,
        val segment: SegmentFetch?,
    ) {
        var seeked = false
    }

    /** A player was built for the title and asks for its master: a new source. */
    fun sourceRequested() {
        requestedAt = now()
        sources += 1
        source += 1
        // Playback is under way again only once it plays; a stall goes on.
        moving = false
    }

    /** The viewer picked in the quality menu: the next source is theirs. */
    fun manualPick() {
        manualSource = source + 1
    }

    /** The first frame of the playback, at [positionMs]; with it, the
     *  bandwidth the start was made on. Reported once. */
    fun firstFrame(positionMs: Long, onScreen: Rendition?) {
        if (frameShown) return
        frameShown = true
        val at = now()
        if (onScreen != null && rendition == null) rendition = onScreen
        if (lastVideo != null) startBandwidth() else startBandwidthDue = true
        report(
            "first_frame",
            buildMap {
                put("attempt", "1")
                put("startup_ms", ms(at - startedAt))
                requestedAt?.let { put("request_ms", ms(at - it)) }
                put("sources", sources.toString())
                put("mode", mode)
                onScreen?.height?.let { put("height", it.toString()) }
                onScreen?.bitrate?.let { put("bitrate", it.toString()) }
                put("resume", (positionMs >= RESUME_FROM_MS).toString())
                put("start_bucket", startBucket(positionMs))
                put("hidden", hiddenBeforeFrame.toString())
                // A TV starts by itself: no autoplay to refuse.
                put("click_to_play", "false")
                put("via", "frame_callback")
            },
        )
    }

    /** The player's bandwidth estimate, bit/s. */
    fun bandwidthEstimate(bitsPerSecond: Long) {
        if (bitsPerSecond > 0) bps = bitsPerSecond
    }

    /** A segment is in; a video one while it plays reports the bandwidth, at
     *  most every 30 s - the first after the first frame, the start's. */
    fun segmentLoaded(fetch: SegmentFetch, playing: Boolean) {
        if (fetch.kind != SegmentKind.VIDEO) return
        lastVideo = fetch
        if (startBandwidthDue) {
            startBandwidth()
        } else if (frameShown && playing) {
            bandwidthPayload(bps, fetch, "interval")?.let { report("bandwidth", it) }
        }
    }

    /** The bandwidth the start was made on, with its last video segment. */
    private fun startBandwidth() {
        startBandwidthDue = false
        bandwidthPayload(bps, lastVideo, "first_frame")?.let { report("bandwidth", it, ignoreGap = true) }
    }

    /** The rendition the video decoder takes now: a level_switch, but for the first one. */
    fun renditionOnScreen(to: Rendition) {
        val from = rendition
        rendition = to
        val manual = manualSource == source
        if (manual) manualSource = -1
        if (renditionChanged(from, to)) report("level_switch", levelSwitchPayload(from!!, to, manual, mode))
    }

    /** The player plays: a stall ends, or playback is under way. */
    fun playing() {
        stall?.let { end(it, "playing") }
        moving = frameShown
    }

    /** The player waits for data while it is to play, at [positionMs], with
     *  [segment] the fetch under way: a stall begins if playback was under
     *  way. chino-web's waiting goes at its start, its rebuffer at its end. */
    fun waiting(positionMs: Long, segment: SegmentFetch?) {
        if (stall != null || !moving) return
        moving = false
        stall = Stall(now(), positionMs, segment)
        report(
            "waiting",
            buildMap {
                put("t", seconds(positionMs))
                if (quality.isNotEmpty()) put("q", quality)
            },
        )
    }

    /** A seek: playback is under way again only once it plays. A stall it
     *  falls in goes on, the viewer still waiting. */
    fun seeked() {
        moving = false
        stall?.seeked = true
    }

    /** A pause: it ends a stall. */
    fun paused() {
        moving = false
        stall?.let { end(it, "pause") }
    }

    /** The screen went to the background before or while it played. */
    fun hidden() {
        if (!frameShown) hiddenBeforeFrame = true
        stop("hidden")
    }

    /** The player gave up ("error") or the screen closed ("exit"). */
    fun stop(how: String) {
        moving = false
        stall?.let { end(it, how) }
    }

    /** A fatal error of the player: chino-web's hls_fatal, and its
     *  media_error where it is the decoder's. */
    fun fatal(errorCode: Int, errorCodeName: String, message: String?, url: String?, httpStatus: Int?, positionMs: Long) {
        report("hls_fatal", fatalPayload(errorCode, errorCodeName, message, url, httpStatus))
        mediaError(errorCode)?.let { (code, name) ->
            report(
                "media_error",
                buildMap {
                    put("code", code.toString())
                    put("name", name)
                    put("message", message.orEmpty())
                    put("t", seconds(positionMs))
                    if (quality.isNotEmpty()) put("q", quality)
                },
            )
        }
    }

    // The stall is measured from where playback stopped to where it goes on.
    private fun end(s: Stall, how: String) {
        stall = null
        val at = now()
        report(
            "rebuffer",
            buildMap {
                put("duration_ms", ms(at - s.at))
                put("position", seconds(s.positionMs))
                put("cause", "waiting")
                put("end", how)
                put("seeked", s.seeked.toString())
                put("mode", mode)
                s.segment?.kind?.let { put("seg_kind", it.wire) }
                putAll(segmentPayload(s.segment, at))
            },
        )
    }

    private fun report(kind: String, payload: Map<String, String>, ignoreGap: Boolean = false) {
        if (budget.allow(kind, now(), ignoreGap)) send(kind, payload)
    }
}

/** A Media3 format's rendition: its height and the bitrate it declares. */
internal fun rendition(height: Int, bitrate: Int): Rendition =
    Rendition(height = height.takeIf { it > 0 }, bitrate = bitrate.takeIf { it > 0 })
