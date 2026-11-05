package cloud.nalet.chino.tv.ui.watchlist

import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.api.WatchlistCounts
import cloud.nalet.chino.tv.data.model.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a list splits into its movies row and its shows row (docs/chino,
 *  watchlist). */
class KindRowsTest {
    private fun item(id: String, kind: String?) = Item(id = id, title = id, kind = kind)
    private fun ids(items: List<Item>) = items.map { it.id }

    @Test
    fun `movies go in one row and everything else in the other, in the list's order`() {
        val rows = splitByKind(
            listOf(
                item("s1", "series"), item("m1", "movie"), item("e1", "episode"),
                item("a1", "album"), item("m2", "movie"), item("n1", null),
            ),
        )
        assertEquals(listOf("m1", "m2"), ids(rows.movies))
        assertEquals(listOf("s1", "e1", "a1", "n1"), ids(rows.shows))
    }

    @Test
    fun `an empty list splits into nothing`() {
        assertTrue(splitByKind(emptyList()).isEmpty())
    }

    @Test
    fun `the heading's count is the sum of its rows, not the titles saved`() {
        // One saved title the library no longer holds is in neither row.
        val list = Watchlist(id = "l", name = "L", itemCount = 8, counts = WatchlistCounts(movie = 4, series = 3))
        assertEquals(7, list.headingCount(byKind = true))
        assertEquals(8, list.headingCount(byKind = false))
        assertEquals(8, list.copy(counts = null).headingCount(byKind = true))
    }

    @Test
    fun `a row's count is the server's, and what it holds where the server gave none`() {
        val rows = KindRows(movies = listOf(item("m1", "movie")), shows = emptyList())
        val list = Watchlist(id = "l", name = "L", counts = WatchlistCounts(movie = 20, series = 3))
        assertEquals(20, list.countOf(KindRow.Movies, rows))
        assertEquals(3, list.countOf(KindRow.Shows, rows))
        assertEquals(1, list.copy(counts = null).countOf(KindRow.Movies, rows))
    }

    @Test
    fun `a row's section starts after the row before it and its heading`() {
        val movies = listOf(item("m1", "movie"), item("m2", "movie"))
        val shows = listOf(item("s1", "series"))
        assertEquals(0, sectionStart(KindRows(movies, shows), KindRow.Movies))
        assertEquals(3, sectionStart(KindRows(movies, shows), KindRow.Shows))
        // No movies: no heading for them either.
        assertEquals(0, sectionStart(KindRows(emptyList(), shows), KindRow.Shows))
    }
}
