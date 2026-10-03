package cloud.nalet.chino.tv.data

/**
 * Pages a chino-api list the way chino-api pages it: `?limit=&offset=`.
 *
 * The list routes (GET /v1/items, /v1/me/watched) answer a plain `{ items }`
 * — no cursor, no total — so the next page starts where the items received
 * so far end, and a page shorter than the limit is the last one. (A last page
 * that happens to be exactly full costs one more request, which comes back
 * empty.) The client used to send a `page_token` the API has never read and
 * wait for a `next_page_token` it has never sent, so every grid stopped after
 * its first page.
 *
 * An offset can slip when the catalog changes between two requests (a title
 * added under sort=newest pushes the rest down a place), handing back a title
 * already shown. It is dropped — a LazyVerticalGrid keyed by id crashes on a
 * duplicate key — while the offset still advances by what the server sent.
 *
 * [pageSize] must stay within chino-api's limit cap (1..200): above it the
 * server falls back to 50 and every page would look like the last one.
 */
class OffsetPager<T>(
    private val pageSize: Int,
    private val keyOf: (T) -> String,
    private val fetch: suspend (offset: Int, limit: Int) -> List<T>,
) {
    init {
        require(pageSize in 1..200) { "pageSize $pageSize is outside chino-api's 1..200" }
    }

    private var offset = 0
    private val handedOut = HashSet<String>()

    /** False once a page came back shorter than [pageSize]. */
    var hasMore: Boolean = true
        private set

    /**
     * The next page's items that were not handed out before; empty at the end.
     * A page made only of repeats is skipped for the one after it, so a slip
     * never reads as an empty page to a caller that is still owed items. A
     * failed fetch throws and leaves the pager where it was, to be retried.
     */
    suspend fun next(): List<T> {
        repeat(MAX_PAGES_PER_CALL) {
            if (!hasMore) return emptyList()
            val page = fetch(offset, pageSize)
            offset += page.size
            hasMore = page.size >= pageSize
            val fresh = page.filter { handedOut.add(keyOf(it)) }
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    private companion object {
        /** Bounds the skip-a-page-of-repeats loop in [next]. */
        const val MAX_PAGES_PER_CALL = 3
    }
}
