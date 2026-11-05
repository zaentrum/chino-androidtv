package cloud.nalet.chino.tv.ui.watchlist

import cloud.nalet.chino.tv.data.api.Watchlist
import cloud.nalet.chino.tv.data.model.Item

/** The two rows a list splits into with "Separate movies and shows" on, movies
 *  first: [word] as the screen says it, [query] as the server asks for it. */
enum class KindRow(val word: String, val query: String) {
    Movies("movies", "movie"),
    Shows("shows", "series"),
}

/** A list's titles by row, each in the list's order. */
data class KindRows(val movies: List<Item> = emptyList(), val shows: List<Item> = emptyList()) {
    operator fun get(row: KindRow): List<Item> = when (row) {
        KindRow.Movies -> movies
        KindRow.Shows -> shows
    }

    fun isEmpty(): Boolean = movies.isEmpty() && shows.isEmpty()
}

/** Movies in one row and everything else in the other: episodes belong with
 *  their show, and a title of any other kind must not disappear. */
fun splitByKind(items: List<Item>): KindRows {
    val (movies, shows) = items.partition { it.kind == KindRow.Movies.query }
    return KindRows(movies, shows)
}

/** The count beside a row's word: the server's, or what the row holds where it
 *  gave none. */
fun Watchlist.countOf(row: KindRow, rows: KindRows): Int = when (row) {
    KindRow.Movies -> counts?.movie
    KindRow.Shows -> counts?.series
} ?: rows[row].size

/** The count beside a list's name. Split, it is the sum of the rows': a saved
 *  title the library no longer holds is in neither, so it can be less than
 *  [Watchlist.itemCount]. */
fun Watchlist.headingCount(byKind: Boolean): Int {
    val counts = counts
    return if (byKind && counts != null) counts.movie + counts.series else itemCount
}

/** Where [row]'s heading is in a whole list's grid: each row that holds
 *  anything is its heading, then its cards. */
fun sectionStart(rows: KindRows, row: KindRow): Int =
    KindRow.entries.takeWhile { it != row }
        .filter { rows[it].isNotEmpty() }
        .sumOf { rows[it].size + 1 }
