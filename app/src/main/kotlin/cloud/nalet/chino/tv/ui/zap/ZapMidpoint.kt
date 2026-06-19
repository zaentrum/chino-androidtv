package cloud.nalet.chino.tv.ui.zap

import cloud.nalet.chino.tv.data.api.Segment
import kotlin.math.roundToInt

enum class MidpointSource { SEGMENTS, PERCENT, FALLBACK }

/** Fixed seek ratio for the DETERMINISTIC first Zap card (card[0]). The
 *  app-start warm and the Zap screen both seed card[0] at this exact ratio of
 *  the seekable runway so the prefetched seek-window segments are precisely the
 *  bytes the screen plays first. Subsequent cards (index >= 1) keep their own
 *  per-card random roll. 0.35 lands comfortably past the intro, before the
 *  midpoint — a representative "channel already in progress" scene. */
const val DETERMINISTIC_FIRST_CARD_RATIO = 0.35

/** The seek (in seconds) for the deterministic first card of [durationMs],
 *  shared by [cloud.nalet.chino.tv.data.AppContainer.warmFirstZapCard] and the
 *  Zap ViewModel so the warmed window matches the scene the screen lands on. */
fun deterministicFirstCardSeekSec(durationMs: Long?): Int =
    pickZapMidpoint(durationMs = durationMs, randomRatio = DETERMINISTIC_FIRST_CARD_RATIO).seekSec

/** Where to drop the viewer into a title so it feels like flipping to a
 *  channel already in progress. [source] == FALLBACK means "too short to
 *  land mid-scene — treat as start-at-0 / skip". */
data class ZapMidpoint(val seekSec: Int, val source: MidpointSource)

private const val MIN_SEEK_SEC = 60
private const val TAIL_BUFFER_SEC = 90
private const val DEFAULT_RATIO = 0.40
private const val RAND_LOW = 0.10
private const val RAND_HIGH = 0.80

/** Squeeze a [0,1] roll into [0.10,0.80] so zaps stay out of the opening
 *  minute and the closing credits zone. Null → the deterministic 0.40
 *  fallback (used in tests). */
fun ratioFromRandom(r: Double?): Double {
    if (r == null) return DEFAULT_RATIO
    val c = r.coerceIn(0.0, 1.0)
    return RAND_LOW + c * (RAND_HIGH - RAND_LOW)
}

/**
 * Faithful port of chino-web's useZapMidpoint.pickZapMidpoint (pure). Picks a
 * mid-content seek point from the item duration + analyzer segments. The seek
 * is applied CLIENT-SIDE (player.seekTo on the reused ExoPlayer), never via a
 * server ?t= — packaged items would ignore ?t= and start at source-time 0.
 */
fun pickZapMidpoint(
    durationMs: Long?,
    segments: List<Segment> = emptyList(),
    randomRatio: Double? = null,
): ZapMidpoint {
    val ratio = ratioFromRandom(randomRatio)
    val durationSec = ((durationMs ?: 0L) / 1000L).toInt()

    // Too short to land mid-scene without risking intro/credits — caller
    // treats FALLBACK as "skip / start at 0".
    if (durationSec <= MIN_SEEK_SEC + TAIL_BUFFER_SEC) {
        val s = (durationSec / 2).coerceAtMost(MIN_SEEK_SEC).coerceAtLeast(0)
        return ZapMidpoint(s, MidpointSource.FALLBACK)
    }

    val lower = MIN_SEEK_SEC
    val upper = durationSec - TAIL_BUFFER_SEC
    fun clamp(v: Int, lo: Int = lower, hi: Int = upper): Int = v.coerceIn(lo, hi)

    if (segments.isNotEmpty()) {
        val introEndSec = segments.filter { it.kind == "intro" }
            .maxOfOrNull { (it.endMs / 1000L).toInt() } ?: 0
        val creditsStartSec = segments.filter { it.kind == "credits" }
            .minOfOrNull { (it.startMs / 1000L).toInt() } ?: durationSec

        // Both intro + credits known and sane: land inside [introEnd, creditsStart].
        if (introEndSec > 0 && creditsStartSec < durationSec && creditsStartSec > introEndSec + 60) {
            val windowSec = creditsStartSec - introEndSec
            return ZapMidpoint(clamp((introEndSec + windowSec * ratio).roundToInt()), MidpointSource.SEGMENTS)
        }
        // Only intro known: window runs from introEnd to the tail buffer.
        if (introEndSec > 0) {
            val window = (durationSec - TAIL_BUFFER_SEC - introEndSec).coerceAtLeast(0)
            return ZapMidpoint(clamp((introEndSec + window * ratio).roundToInt()), MidpointSource.SEGMENTS)
        }
        // Only credits known: trust them only if they're not implausibly early.
        if (creditsStartSec < durationSec) {
            val trustworthy = creditsStartSec >= durationSec * 0.5 ||
                creditsStartSec >= lower + 2 * TAIL_BUFFER_SEC
            return if (!trustworthy) {
                ZapMidpoint(clamp((durationSec * ratio).roundToInt()), MidpointSource.PERCENT)
            } else {
                val hi = clamp(creditsStartSec - TAIL_BUFFER_SEC)
                ZapMidpoint(clamp((durationSec * ratio).roundToInt(), lower, hi), MidpointSource.PERCENT)
            }
        }
    }

    // No usable segments → plain percentage of duration.
    return ZapMidpoint(clamp((durationSec * ratio).roundToInt()), MidpointSource.PERCENT)
}
