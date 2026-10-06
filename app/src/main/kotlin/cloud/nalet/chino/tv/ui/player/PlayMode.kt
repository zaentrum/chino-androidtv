package cloud.nalet.chino.tv.ui.player

/*
 * What the player plays, and what that has it ask the server for and write
 * there. Pure: PlayModeTest runs it on the JVM.
 *
 * A title (a movie, an episode) is the player's whole session: it starts
 * where the viewer left off, saves the position for Continue Watching, marks
 * the title watched, skips its intro and credits, previews the scrubber, and
 * goes on to the next episode or the top recommendation.
 */

/** What the player plays. [itemId] is the title played. */
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
}

/**
 * What a mode asks the server for and writes there, besides what it plays
 * (the title's detail, the master and its segments). A request whose flag is
 * off is never made.
 */
data class PlayRequests(
    /** GET …/play/info: the server's transcode decision, the codecs and the
     *  quality ladder. */
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
     *  quality, errors. */
    val telemetry: Boolean,
)

/** A title asks for all of it, however it was opened. */
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
    }

/**
 * Where a prepare starts, in seconds — or null for where the viewer left off,
 * the saved progress. A reload ([atSec]: a quality switch, a recovery) goes
 * on where the viewer was; else a title starts at Zap's scene, or at 0 from
 * Play from start.
 */
fun startSec(mode: PlayMode, atSec: Int?): Int? = when {
    atSec != null -> atSec.coerceAtLeast(0)
    mode is PlayMode.Title && mode.resumeSec > 0 -> mode.resumeSec
    mode is PlayMode.Title && mode.fromStart -> 0
    else -> null
}
