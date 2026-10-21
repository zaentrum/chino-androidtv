package cloud.nalet.chino.tv.ui.player

import cloud.nalet.chino.tv.data.model.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAdvanceTest {

    /** A film of the loop: its length, where the viewer's progress was saved,
     *  whether they watched it, and its "more like this", best first. */
    private data class Film(
        val id: String,
        val durationSec: Int,
        val savedSec: Int,
        val watched: Boolean,
        val similar: List<String>,
    )

    /** How the player goes on at the end of a title: what it picks next, where
     *  that starts, and whether it waits for a title to have played. */
    private class Rules(
        val next: (current: Film, similar: List<Item>) -> String?,
        val start: (film: Film) -> Int,
        val guarded: Boolean,
    )

    /**
     * The chain at the end of [first], watched through: each next title
     * starts where [rules] say, plays to its end - as fast as the TV plays,
     * one second a second - and the player goes on as [rules] say. The titles
     * it went on to, at most [maxHops].
     */
    private fun chain(first: Film, films: Map<String, Film>, rules: Rules, maxHops: Int = 10): List<String> {
        val hops = mutableListOf<String>()
        var current = first
        var playedSec = first.durationSec
        while (hops.size < maxHops) {
            if (rules.guarded) {
                var clock = 0L
                val guard = AdvanceGuard(now = { clock })
                guard.firstFrame()
                guard.playing(true)
                clock = playedSec * 1000L
                guard.playing(false)
                if (!guard.mayAdvance()) break
            }
            val similar = current.similar.map { id ->
                val film = films.getValue(id)
                Item(id = film.id, title = film.id, watchedAt = if (film.watched) "2026-10-21T05:32:00Z" else null)
            }
            val nextId = rules.next(current, similar) ?: break
            val next = films.getValue(nextId)
            hops += nextId
            playedSec = next.durationSec - rules.start(next)
            current = next
        }
        return hops
    }

    // The demo's two films, watched to their ends: each the other's top
    // "more like this".
    private val a = Film("a", durationSec = 146, savedSec = 146, watched = true, similar = listOf("b"))
    private val b = Film("b", durationSec = 90, savedSec = 90, watched = true, similar = listOf("a"))
    private val films = mapOf("a" to a, "b" to b)

    private val before = Rules(
        next = { current, similar -> similar.firstOrNull { it.id != current.id }?.id },
        start = { it.savedSec },
        guarded = false,
    )

    private val now = Rules(
        next = { current, similar -> nextSimilar(similar, current.id) },
        start = { startFrom(it.savedSec, it.durationSec) },
        guarded = true,
    )

    @Test
    fun `the loop as it was - two finished films that recommend each other go on and on`() {
        // Each resumed at its saved end, ended at once and went on.
        assertEquals(listOf("b", "a", "b", "a", "b", "a", "b", "a", "b", "a"), chain(a, films, before))
    }

    @Test
    fun `now the player stays at the end where the next film is watched already`() {
        assertEquals(emptyList<String>(), chain(a, films, now))
    }

    @Test
    fun `a film not marked watched but saved at its end plays from the top, once`() {
        val unmarked = b.copy(watched = false)
        val hops = chain(a, mapOf("a" to a, "b" to unmarked), now)
        // b plays from the top (90 s), then a - watched - is not gone on to.
        assertEquals(listOf("b"), hops)
        assertEquals(0, startFrom(90, 90))
    }

    @Test
    fun `the guard holds where a title ends right after it starts, whatever picks the next`() {
        // The rules before, with only the guard: b resumes at its saved end
        // and plays no second, so the player does not go on from it.
        val guardedOnly = Rules(next = before.next, start = before.start, guarded = true)
        assertEquals(listOf("b"), chain(a, films, guardedOnly))
    }

    @Test
    fun `the server's resume point resumes only mid-watch - chino-web's player's rule`() {
        // More than 30 s in, and not in the last minute. chino-api gives none
        // for a title the viewer finished (its last 5 % or 2 minutes): 0.
        assertEquals(600, startFrom(600, 3_000))
        assertEquals(0, startFrom(0, 3_000))
        assertEquals(0, startFrom(30, 3_000))
        assertEquals(31, startFrom(31, 3_000))
        assertEquals(0, startFrom(2_940, 3_000))
        assertEquals(2_939, startFrom(2_939, 3_000))
        assertEquals(0, startFrom(3_000, 3_000))
        // An unknown length keeps any position past 30 s.
        assertEquals(5_000, startFrom(5_000, 0))
    }

    @Test
    fun `a film goes on to its first similar title not watched, never itself`() {
        val similar = listOf(
            Item(id = "self", title = "self"),
            Item(id = "seen", title = "seen", watchedAt = "2026-10-01T20:00:00Z"),
            Item(id = "new", title = "new"),
            Item(id = "later", title = "later"),
        )
        assertEquals("new", nextSimilar(similar, currentId = "self"))
        assertNull(nextSimilar(similar.take(2), currentId = "self"))
        assertNull(nextSimilar(emptyList(), currentId = "self"))
    }

    @Test
    fun `the guard counts the time a title plays after its first frame`() {
        var clock = 0L
        val guard = AdvanceGuard(now = { clock })
        // Playing, but no frame yet.
        guard.playing(true)
        clock = 20_000
        assertFalse(guard.mayAdvance())
        guard.firstFrame()
        assertTrue(guard.mayAdvance())

        // A new title: paused and stalled time does not count.
        clock = 0
        val next = AdvanceGuard(now = { clock })
        next.firstFrame()
        next.playing(true)
        clock = 4_000
        next.playing(false) // a pause
        clock = 60_000
        assertFalse(next.mayAdvance())
        assertEquals(4_000L, next.playedMs())
        next.playing(true)
        clock = 65_000
        assertFalse(next.mayAdvance())
        clock = 66_000
        assertTrue(next.mayAdvance())
        // A second report of the same state changes nothing.
        next.playing(true)
        assertEquals(10_000L, next.playedMs())
    }
}
