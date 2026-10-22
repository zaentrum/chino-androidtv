package cloud.nalet.chino.tv.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/** The title actions' names say the state, in the words of docs/chino. */
class TitleActionsTest {
    @Test
    fun `the names a screen reader says`() {
        assertEquals("add to Watchlist", addToListName(inWatchlist = false))
        assertEquals("in your Watchlist", addToListName(inWatchlist = true))
        assertEquals("choose lists", CHOOSE_LISTS)
        assertEquals("mark as watched", watchedName(watched = false))
        assertEquals("watched", watchedName(watched = true))
        assertEquals("like", likedName(liked = false))
        assertEquals("liked", likedName(liked = true))
    }
}
