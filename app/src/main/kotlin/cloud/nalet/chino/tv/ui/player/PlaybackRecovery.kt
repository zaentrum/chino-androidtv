package cloud.nalet.chino.tv.ui.player

import kotlin.math.abs

/*
 * How the player gets a failing or stalled stream going again without losing
 * the viewer's place. Pure: PlaybackRecoveryTest runs it on the JVM.
 *
 * What helps depends on what the server does with the title (/play/info's
 * mode). An on-the-fly title — transcode, remux, passthrough — runs through
 * chino-stream's pipeline at a rung of its ladder, ?q=high, medium or low
 * (high also stream-copies a direct stream), so a step down is a real change:
 * a smaller encode, a transcode instead of a copy. A packaged title is
 * pre-segmented files: the ladder for this device's caps (q=auto, which
 * ExoPlayer steps through by itself) or the one rung the viewer picked. On it
 * high, medium and low mean auto, so stepping it down to medium only reloaded
 * the stream it was on. A packaged title is tried again where it stands
 * instead, and rebuilt at its own quality and position when that keeps
 * failing; it is never put on the on-the-fly ladder. chino-web's
 * lib/playback.ts recovers the same way.
 */

/** What the player does about an error or a stall. */
sealed interface Recovery {
    /** Reload one rung down the on-the-fly ladder, at the position. */
    data class StepDown(val quality: String) : Recovery

    /** Try again where the playhead stands: prepare() after an error, a seek
     *  to the playhead on a stall. */
    data object RetryInPlace : Recovery

    /** Rebuild the source at the same quality, at the position. */
    data class Reload(val quality: String) : Recovery

    /** Leave the player to it: it is fetching, and a rebuild would throw away
     *  what is arriving. */
    data object Wait : Recovery

    /** Nothing left to try: show the error. */
    data object GiveUp : Recovery
}

/** In-place tries at one spot before a packaged title is rebuilt there. */
const val IN_PLACE_TRIES = 3

/** Rebuilds at one spot before an error there is shown. */
const val RELOADS_PER_SPOT = 1

/** How far the playhead must be from a spot for trouble to count as new. */
const val SPOT_PASSED_MS = 30_000L

/** Media buffered past a stalled playhead that a seek to it can play. */
const val NUDGE_BUFFERED_MS = 500L

fun isPackaged(mode: String?): Boolean = mode.equals("packaged", ignoreCase = true)

/** The rung below [quality] on the on-the-fly ladder; null at low. A q that
 *  is not one of its rungs (a packaged one, on a title the server no longer
 *  serves packaged) is read as high, the server's default. */
fun lowerOnTheFlyRung(quality: String): String? = when (quality.lowercase()) {
    "low" -> null
    "medium" -> "low"
    else -> "medium"
}

/**
 * The tries of one play session. A rebuild makes a new player, so this lives
 * with the session (PlayerViewModel), not with a player: failing at the same
 * spot after a reload is the same trouble. Once the playhead is
 * [SPOT_PASSED_MS] away from the spot — played past it, or the viewer went
 * elsewhere — it is new trouble and the tries start over.
 */
class PlaybackRecovery(private val passedMs: Long = SPOT_PASSED_MS) {
    private var spotMs = -1L
    private var inPlace = 0
    private var reloads = 0

    /** A player error at [positionMs] of a title of [mode] played at [quality]. */
    fun onError(mode: String?, quality: String, positionMs: Long): Recovery {
        moveTo(positionMs)
        if (!isPackaged(mode)) return lowerOnTheFlyRung(quality)?.let { Recovery.StepDown(it) } ?: Recovery.GiveUp
        return retryOrReload(quality) ?: Recovery.GiveUp
    }

    /**
     * A stall at [positionMs]: buffering mid-stream for a while, with no
     * error. [loading] is whether the player is fetching; [bufferedAheadMs]
     * the media it holds past the playhead. A packaged title that is fetching
     * with nothing to play is left to it — the network is the bottleneck,
     * and on Auto ExoPlayer steps down by itself. A stall is never shown as
     * an error: on the fly at low, and once a packaged title's tries are
     * spent, the player waits.
     */
    fun onStall(mode: String?, quality: String, positionMs: Long, loading: Boolean, bufferedAheadMs: Long): Recovery {
        moveTo(positionMs)
        if (!isPackaged(mode)) return lowerOnTheFlyRung(quality)?.let { Recovery.StepDown(it) } ?: Recovery.Wait
        if (loading && bufferedAheadMs < NUDGE_BUFFERED_MS) return Recovery.Wait
        return retryOrReload(quality) ?: Recovery.Wait
    }

    // A rebuilt player that fails at the spot again gets no tries in place of
    // its own: three and a rebuild have not helped, more would only keep the
    // viewer looking at a spinner.
    private fun retryOrReload(quality: String): Recovery? = when {
        inPlace < IN_PLACE_TRIES -> {
            inPlace++
            Recovery.RetryInPlace
        }
        reloads < RELOADS_PER_SPOT -> {
            reloads++
            Recovery.Reload(quality)
        }
        else -> null
    }

    private fun moveTo(positionMs: Long) {
        if (spotMs < 0 || abs(positionMs - spotMs) >= passedMs) {
            spotMs = positionMs
            inPlace = 0
            reloads = 0
        }
    }
}
