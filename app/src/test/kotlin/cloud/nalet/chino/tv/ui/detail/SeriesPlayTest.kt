package cloud.nalet.chino.tv.ui.detail

import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import cloud.nalet.chino.tv.data.api.Episode
import cloud.nalet.chino.tv.data.api.NextEpisodeResponse
import cloud.nalet.chino.tv.data.api.Season
import cloud.nalet.chino.tv.data.model.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesPlayTest {
    private val series = "show"

    /** A 45-minute episode of [series]. */
    private fun ep(season: Int, number: Int, watched: Boolean = false) = Episode(
        id = "s${season}e$number",
        title = "Episode $number",
        seasonNumber = season,
        episodeNumber = number,
        parentId = series,
        watchedAt = if (watched) "2026-09-30T20:00:00Z" else null,
        durationMs = 45 * 60_000L,
    )

    /** The episode list as chino-api groups it: by season, specials first. */
    private fun seasons(vararg eps: Episode): List<Season> =
        eps.groupBy { it.seasonNumber ?: 0 }.toSortedMap().map { (n, list) -> Season(n, list) }

    private fun row(
        id: String,
        parent: String? = series,
        position: Int = 0,
        duration: Int = 2700,
        upNext: Boolean = false,
    ) = ContinueWatchingItem(
        id = id,
        title = id,
        positionSec = position,
        durationSec = duration,
        upNext = upNext,
        seasonNumber = id.substringAfter('s').substringBefore('e').toIntOrNull(),
        episodeNumber = id.substringAfter('e').toIntOrNull(),
        parentId = parent,
        type = "episode",
    )

    @Test
    fun `an episode in progress resumes where it was left`() {
        val t = continueWatchingTarget(series, listOf(row("s2e3", position = 754)))!!
        assertEquals("s2e3", t.episodeId)
        assertEquals(754, t.positionSec)
        assertTrue(t.resumes)
        assertEquals("S02E03", t.code)
    }

    @Test
    fun `the up-next episode after a finished one starts from the top`() {
        val t = continueWatchingTarget(series, listOf(row("s2e4", upNext = true)))!!
        assertEquals("s2e4", t.episodeId)
        assertFalse(t.resumes)
    }

    @Test
    fun `the series' newest row decides, other titles are ignored`() {
        val rows = listOf(
            row("movie", parent = null, position = 600),
            row("s1e1", parent = "other-show", position = 300),
            row("s1e5", upNext = true),
            row("s1e2", position = 900),
        )
        assertEquals("s1e5", continueWatchingTarget(series, rows)?.episodeId)
        assertNull(continueWatchingTarget("unseen-show", rows))
    }

    @Test
    fun `a series never touched starts at S01E01, not at a special`() {
        val list = seasons(ep(0, 1), ep(0, 2), ep(1, 1), ep(1, 2))
        // next-episode without an anchor names the series' first episode,
        // which is the special: specials sort first.
        val t = nextTarget(list, lastTouched = null, serverNext = item("s0e1", 0, 1))!!
        assertEquals("s1e1", t.episodeId)
        assertFalse(t.resumes)
    }

    @Test
    fun `an episode left mid-watch resumes even when the feed dropped it`() {
        val list = seasons(ep(1, 1, watched = true), ep(1, 2), ep(1, 3))
        val t = nextTarget(list, LastTouched("s1e2", 1200), serverNext = item("s1e3", 1, 3))!!
        assertEquals("s1e2", t.episodeId)
        assertEquals(1200, t.positionSec)
    }

    @Test
    fun `an episode barely started plays again from the top`() {
        val list = seasons(ep(1, 1), ep(1, 2))
        val t = nextTarget(list, LastTouched("s1e1", 12), serverNext = item("s1e2", 1, 2))!!
        assertEquals("s1e1", t.episodeId)
        assertFalse(t.resumes)
    }

    @Test
    fun `after a finished episode comes the next one not yet watched`() {
        val list = seasons(
            ep(1, 1, watched = true),
            ep(1, 2, watched = true),
            ep(1, 3, watched = true),
            ep(2, 1),
        )
        // Rewatched S01E01 to the end: S01E02 and S01E03 were seen before.
        val t = nextTarget(list, LastTouched("s1e1", 2690), serverNext = item("s1e2", 1, 2))!!
        assertEquals("s2e1", t.episodeId)
        assertFalse(t.resumes)
    }

    @Test
    fun `an episode in its last minute counts as finished`() {
        val list = seasons(ep(1, 1), ep(1, 2))
        val t = nextTarget(list, LastTouched("s1e1", 45 * 60 - 30), serverNext = null)!!
        assertEquals("s1e2", t.episodeId)
    }

    @Test
    fun `after the finale, an episode skipped earlier, else the first`() {
        val skipped = seasons(ep(1, 1, watched = true), ep(1, 2), ep(1, 3, watched = true))
        val response = NextEpisodeResponse(next = null, reason = "end_of_series")
        val anchor = lastTouchedEpisodeId(response, skipped)
        assertEquals("s1e3", anchor)
        assertEquals("s1e2", nextTarget(skipped, LastTouched(anchor!!, 2700), null)?.episodeId)

        val allSeen = seasons(ep(1, 1, watched = true), ep(1, 2, watched = true))
        assertEquals("s1e1", nextTarget(allSeen, LastTouched("s1e2", 2700), null)?.episodeId)
    }

    @Test
    fun `without an episode list, the server's next episode`() {
        val t = nextTarget(emptyList(), LastTouched("s3e4", 2700), serverNext = item("s3e5", 3, 5))!!
        assertEquals("s3e5", t.episodeId)
        assertEquals("S03E05", t.code)
        assertNull(nextTarget(emptyList(), null, null))
    }

    @Test
    fun `the episode a next-episode answer went from`() {
        val list = seasons(ep(1, 1), ep(1, 2))
        assertEquals("s1e1", lastTouchedEpisodeId(NextEpisodeResponse(item("s1e2", 1, 2), anchor = "s1e1"), list))
        assertNull(lastTouchedEpisodeId(NextEpisodeResponse(item("s1e1", 1, 1)), list))
        assertNull(lastTouchedEpisodeId(null, list))
    }

    @Test
    fun `mid-watch is more than 30 s in and short of the last minute`() {
        assertFalse(isMidWatch(30, 2700))
        assertTrue(isMidWatch(31, 2700))
        assertTrue(isMidWatch(2639, 2700))
        assertFalse(isMidWatch(2640, 2700))
        assertTrue(isMidWatch(5000, 0)) // unknown duration: never finished by position
    }

    private fun item(id: String, season: Int, number: Int) =
        Item(id = id, title = id, kind = "episode", seasonNumber = season, episodeNumber = number)
}
