package cloud.nalet.chino.tv.ui.zap

import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.model.Item
import kotlin.random.Random

/**
 * The Zap candidate feed — faithful port of chino-web's useZapFeed.
 *
 * Builds a pool from movie (by rating) + series (by newest) lists, dedups
 * against watched ids + a session-permanent shown-set + the packaged-only
 * filter, Fisher-Yates shuffles it (so a cold/empty preference vector doesn't
 * collapse to "always the top-rated title"), then ε-greedy samples a queue.
 *
 * V1 drops type=episode (web treats it as best-effort, and episodes routinely
 * lack a list-level duration → a visible post-READY re-seek on the single
 * reused ExoPlayer). [scoreItem] is the exploit-branch ranking (type-level on
 * the pool; full genre/cast scoring happens per-card). [random] is injectable
 * for deterministic tests.
 */
class ZapFeed(
    private val api: ChinoApi,
    private val scoreItem: (Item) -> Double = { 0.0 },
    private val random: Random = Random.Default,
) {
    private var pool: List<Item> = emptyList()
    private val shown = HashSet<String>()
    private val _queue = ArrayList<Item>()

    // The deterministic top-ranked candidate, captured at load() time BEFORE the
    // pool is shuffled. Both the app-start warm and the Zap screen's first card
    // resolve to THIS exact item so the warmed init + seek-window segments are
    // the ones the screen plays first (see deterministicFirstCard).
    private var topCandidate: Item? = null

    val queue: List<Item> get() = _queue.toList()
    var empty: Boolean = false
        private set

    /** Top-ranked packaged candidate, fixed for the lifetime of this feed and
     *  independent of the shuffle/epsilon sampling. Null only when the pool is
     *  empty. Used to make card[0] deterministic + identical across the
     *  app-start warm and the Zap screen. */
    fun topCandidate(): Item? = topCandidate

    /** Fetch + assemble the pool and seed the queue. Each fetch degrades to
     *  empty on failure (the packaged filter is skipped entirely if its
     *  endpoint is unavailable — older deploys still get a pool). */
    suspend fun load() {
        val movies = runCatching {
            api.listItems(type = "movie", sort = "rating", limit = POOL_SIZE_PER_TYPE).items
        }.getOrDefault(emptyList())
        val series = runCatching {
            api.listItems(type = "series", sort = "newest", limit = POOL_SIZE_PER_TYPE).items
        }.getOrDefault(emptyList())
        val watched = runCatching {
            api.watched(limit = WATCHED_LIMIT).items.mapTo(HashSet()) { it.id }
        }.getOrDefault(emptySet())
        // null → packaged endpoint unavailable → skip the filter entirely.
        val packaged: Set<String>? = runCatching { api.packagedIds().ids.toHashSet() }.getOrNull()

        val seen = HashSet<String>()
        val assembled = ArrayList<Item>()
        for (item in (movies + series)) {
            val id = item.id
            if (id.isBlank() || !seen.add(id)) continue        // first-wins dedup
            if (id in watched || item.watchedAt != null) continue
            if (packaged != null && id !in packaged) continue  // packaged-only
            assembled.add(item)
        }
        // Capture the deterministic top-ranked candidate BEFORE shuffling so
        // card[0] is reproducible: no shuffle, no epsilon, no jitter. Ranked by
        // the same exploit signal the sampler uses minus the random term, with a
        // stable id tiebreak so identical scores resolve the same every time.
        topCandidate = assembled.maxWithOrNull(
            compareBy<Item> { scoreItem(it) + (it.rating ?: 0.0) / 100.0 }.thenByDescending { it.id },
        )

        shuffleInPlace(assembled)
        pool = assembled
        empty = pool.isEmpty()

        _queue.clear()
        _queue.addAll(sample(SEED_SIZE))
    }

    /** Mark an id shown so it never resurfaces this session; drop it from the queue. */
    fun markShown(id: String) {
        shown.add(id)
        _queue.removeAll { it.id == id }
    }

    /** Top up the queue when it runs short; flips [empty] when exhausted. */
    fun refill() {
        if (_queue.size >= REFILL_AT) return
        val more = sample(SEED_SIZE - _queue.size)
        if (more.isEmpty() && _queue.isEmpty()) empty = true
        _queue.addAll(more)
    }

    private fun sample(n: Int): List<Item> {
        val inQueue = _queue.mapTo(HashSet()) { it.id }
        val candidates = pool.filter { it.id !in shown && it.id !in inQueue }
        if (candidates.isEmpty()) return emptyList()

        val taken = HashSet<String>()
        val out = ArrayList<Item>()
        for (i in 0 until n) {
            val available = candidates.filter { it.id !in taken }
            if (available.isEmpty()) break
            val pick = if (random.nextDouble() < EPSILON) {
                available[random.nextInt(available.size)]                       // explore
            } else {
                available.maxByOrNull {                                          // exploit
                    scoreItem(it) + (it.rating ?: 0.0) / 100.0 + random.nextDouble() * SCORE_JITTER
                }!!
            }
            taken.add(pick.id)
            out.add(pick)
        }
        return out
    }

    private fun shuffleInPlace(arr: MutableList<Item>) {
        for (i in arr.indices.reversed()) {
            val j = random.nextInt(i + 1)
            val tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp
        }
    }

    companion object {
        private const val EPSILON = 0.6
        private const val SCORE_JITTER = 0.05
        private const val POOL_SIZE_PER_TYPE = 30
        private const val WATCHED_LIMIT = 200
        private const val SEED_SIZE = 8
        private const val REFILL_AT = 5
    }
}
