package cloud.nalet.chino.tv.ui.library

import cloud.nalet.chino.tv.data.api.ContinueWatchingItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Watchlist's bookmark on a continue-watching card: an episode's card
 *  is its series', a movie's its own (docs/chino, title actions: cards). */
class ContinueWatchingMarkTest {
    private val episode = ContinueWatchingItem(
        id = "ep-3", title = "Low Tide", type = "episode", parentId = "series-1",
        seriesTitle = "Harbour Lights", seasonNumber = 1, episodeNumber = 3,
    )
    private val movie = ContinueWatchingItem(id = "movie-1", title = "Quiet Orchard", type = "movie")

    @Test
    fun `a series in the Watchlist marks its episodes' cards`() {
        assertTrue(cardInWatchlist(episode, setOf("series-1")))
        assertTrue(cardInWatchlist(episode.copy(upNext = true), setOf("series-1", "other")))
    }

    @Test
    fun `an episode's card is not marked by the episode alone, nor by another series`() {
        assertFalse(cardInWatchlist(episode, setOf("ep-3")))
        assertFalse(cardInWatchlist(episode, setOf("series-2")))
        assertFalse(cardInWatchlist(episode, emptySet()))
    }

    @Test
    fun `a movie's card by the movie, an episode without its series by itself`() {
        assertTrue(cardInWatchlist(movie, setOf("movie-1")))
        assertFalse(cardInWatchlist(movie, setOf("series-1")))
        assertTrue(cardInWatchlist(episode.copy(parentId = null), setOf("ep-3")))
    }
}
