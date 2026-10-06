package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.api.PlayInfo

/*
 * What the player plays — a title, or one of a title's extras — and what
 * that has it ask the server for and write there. Pure: PlayModeTest runs it
 * on the JVM.
 *
 * A title (a movie, an episode) is the player's whole session: it starts
 * where the viewer left off, saves the position for Continue Watching, marks
 * the title watched, skips its intro and credits, previews the scrubber, and
 * goes on to the next episode or the top recommendation. An extra — a
 * trailer, a teaser, a file of its own the server packaged apart from its
 * title — plays in the same player, with the same controls, menus and
 * remote, and none of that: chino-api has no play info, progress, watched,
 * segments, trickplay, prewarm or next episode for one. It reads its title's
 * detail, which names its master (Extras.kt), starts at 0, closes at its
 * end, and sends one trailer_play.
 */

/** What the player plays. [itemId] is the title: the one played, or the one
 *  the extra played belongs to. */
sealed interface PlayMode {
    val itemId: String

    /**
     * The title [itemId], from where the viewer left off — or from the start
     * ([fromStart]: Detail's Play from start), or at [resumeSec] when above 0
     * (Zap's handoff). [fromBinge]: auto-play-next opened it, so an intro at
     * its head is skipped.
     */
    data class Title(
        override val itemId: String,
        val fromStart: Boolean = false,
        val fromBinge: Boolean = false,
        val resumeSec: Int = 0,
    ) : PlayMode

    /** The extra [extraId] of the title [itemId]: Detail's Trailer. */
    data class Extra(override val itemId: String, val extraId: String) : PlayMode
}

/**
 * What a mode asks the server for and writes there, besides what it plays:
 * the title's detail, the master and what the master names. A request whose
 * flag is off is never made.
 */
data class PlayRequests(
    /** GET …/play/info: the server's transcode decision, the codecs and the
     *  quality ladder. Without it the quality menu is the master's own
     *  variants ([variantMenu]). */
    val playInfo: Boolean,
    /** GET …/progress to start where the viewer left off ([startSec]); POST
     *  every 10 s and on leaving — what Continue Watching shows. */
    val progress: Boolean,
    /** POST …/watched, in the credits or past 95 %. */
    val watched: Boolean,
    /** GET …/segments: skip intro, recap and credits, the scrubber's stripes. */
    val segments: Boolean,
    /** GET …/subtitles: the sidecar subtitles. */
    val subtitles: Boolean,
    /** GET …/play/trickplay: the scrubber's previews. */
    val trickplay: Boolean,
    /** The next or previous episode, or the top recommendation: up next. */
    val nextUp: Boolean,
    /** GET the next title's master before the end, to warm it. */
    val prewarm: Boolean,
    /** The player's own events: play, pause, seek, buffering, first frame,
     *  quality, errors. An extra sends its one trailer_play instead. */
    val telemetry: Boolean,
)

/** A title asks for all of it, however it was opened; an extra for none. */
val PlayMode.requests: PlayRequests
    get() = when (this) {
        is PlayMode.Title -> PlayRequests(
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
        is PlayMode.Extra -> PlayRequests(
            playInfo = false,
            progress = false,
            watched = false,
            segments = false,
            subtitles = false,
            trickplay = false,
            nextUp = false,
            prewarm = false,
            telemetry = false,
        )
    }

/**
 * Where a prepare starts, in seconds — or null for where the viewer left off,
 * the saved progress, which only a title asks for. A reload ([atSec]: a
 * quality switch, a recovery) goes on where the viewer was; else a title
 * starts at Zap's scene, or at 0 from Play from start, and an extra at 0.
 */
fun startSec(mode: PlayMode, atSec: Int?): Int? = when {
    atSec != null -> atSec.coerceAtLeast(0)
    !mode.requests.progress -> 0
    mode is PlayMode.Title && mode.resumeSec > 0 -> mode.resumeSec
    mode is PlayMode.Title && mode.fromStart -> 0
    else -> null
}

/** The player closes at the end of the media, back to where it was opened:
 *  an extra does, back to its title. A title stays, or goes on to the next
 *  one (auto-play-next). */
val PlayMode.closesAtEnd: Boolean
    get() = this is PlayMode.Extra

/**
 * How chino-stream serves what plays, as /play/info's mode names it — what
 * recovery goes by (PlaybackRecovery). A title's play info says, null without
 * one; an extra is always packaged: chino-stream serves one from its package
 * alone, with no on-the-fly fallback.
 */
fun streamMode(mode: PlayMode, info: PlayInfo?): String? = when (mode) {
    is PlayMode.Title -> info?.mode
    is PlayMode.Extra -> "packaged"
}
