package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.ui.detail.isMidWatch

/*
 * Auto-advance - at the end of what plays, the next episode, or a film's top
 * "more like this" title - and where a title starts. It once looped: at the
 * end of a film the player went on to its top similar title; that one resumed
 * at its saved end, ended at once and went on to its own top similar - the
 * first film - and so on, every second. Three rules end that: a title starts
 * where the server's resume point is, and chino-api has none for a finished
 * one ([startFrom]); a film goes on only to a title the viewer has not
 * watched ([nextSimilar]); and nothing goes on from a title that has hardly
 * played ([AdvanceGuard]). Pure: AutoAdvanceTest runs the loop and these on
 * the JVM.
 */

/** How long a title plays, after its first frame, before auto-advance may
 *  leave it. */
internal const val MIN_PLAYED_MS = 10_000L

/**
 * Where a title opened without a second to start at starts - the home's
 * hero, an episode row, auto-advance; Resume on the detail page and Zap name
 * theirs - from the resume point the server gives, [savedSec]: chino-api has
 * none for a title the viewer finished (its last 5 % or 2 minutes, its one
 * rule), and the player resumes the one it has as chino-web's does - more
 * than 30 s in and not in the title's last minute ([isMidWatch]) - else from
 * the top. [durationSec] 0 is unknown.
 */
internal fun startFrom(savedSec: Int, durationSec: Int): Int =
    if (isMidWatch(savedSec, durationSec)) savedSec else 0

/** What a film goes on to: its first "more like this" title the viewer has
 *  not watched (chino-api stamps watched_at on each), itself left out. Null
 *  where there is none: the player stays at the end. */
internal fun nextSimilar(similar: List<Item>, currentId: String): String? =
    similar.firstOrNull { it.id != currentId && it.watchedAt == null }?.id

/**
 * Whether auto-advance may leave the title that plays: once a frame of it has
 * been on screen and it has played [MIN_PLAYED_MS] - so a title that ends, or
 * reaches its credits, right after it starts stays where it is rather than
 * the player chaining titles nobody saw. Only the time it plays counts, not
 * time paused or stalled. One per title, across the players it builds (a
 * quality switch, a recovery). [now] is in ms (elapsedRealtime). Main thread.
 */
internal class AdvanceGuard(private val now: () -> Long) {
    private var frameShown = false
    private var playingSince: Long? = null
    private var playedMs = 0L

    /** A frame of the title is on screen. */
    fun firstFrame() {
        frameShown = true
    }

    /** The player plays, or stops playing (a pause, a stall, its end, its
     *  release). */
    fun playing(isPlaying: Boolean) {
        val at = now()
        val since = playingSince
        if (isPlaying) {
            if (since == null) playingSince = at
        } else if (since != null) {
            playedMs += (at - since).coerceAtLeast(0L)
            playingSince = null
        }
    }

    /** How long the title has played, in ms. */
    fun playedMs(): Long = playedMs + (playingSince?.let { (now() - it).coerceAtLeast(0L) } ?: 0L)

    fun mayAdvance(): Boolean = frameShown && playedMs() >= MIN_PLAYED_MS
}
