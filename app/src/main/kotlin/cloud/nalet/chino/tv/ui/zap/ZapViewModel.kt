package cloud.nalet.chino.tv.ui.zap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import cloud.nalet.chino.tv.data.AppContainer
import cloud.nalet.chino.tv.data.CodecCaps
import cloud.nalet.chino.tv.data.UserFlagsRepository
import cloud.nalet.chino.tv.data.api.ChinoApi
import cloud.nalet.chino.tv.data.auth.StreamTokenManager
import cloud.nalet.chino.tv.data.model.Item
import cloud.nalet.chino.tv.data.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** One card in the channel-surf history. randomRatio is rolled once per card
 *  so revisiting (zap-prev) keeps the same scene; features fill in once the
 *  detail fetch resolves (genres/cast are detail-only). */
class ZapEntry(
    val item: Item,
    val randomRatio: Double,
    val seekSec: Int,
    val source: MidpointSource,
) {
    var features: ZapFeatures = ZapFeatures(type = item.kind)
}

sealed interface ZapUiState {
    data object Loading : ZapUiState
    data object Empty : ZapUiState
    data class Active(
        val item: Item,
        val masterUrl: String,
        /** Item backdrop URL (poster fallback baked in by chino-stream when no
         *  backdrop exists) — shown full-screen UNDER the PlayerView while the
         *  channel cold-starts, hidden on the player's first rendered frame.
         *  Mirrors web's ZapCard cold-start artwork. */
        val backdropUrl: String,
        /** Poster fallback for the artwork layer (portrait items / when the
         *  backdrop 404s). */
        val posterUrl: String,
        val seekSec: Int,
        val index: Int,
    ) : ZapUiState
}

/**
 * Orchestrates the Zap channel-surf feed for TV. Owns the feed (ZapFeed), the
 * preference vector (ZapPreferences), and the zap_* telemetry funnel; the
 * ZapScreen owns the single reused ExoPlayer and just consumes
 * [state].masterUrl/seekSec. Surfing must NOT touch progress / watched /
 * continue-watching (so this does NOT reuse PlayerViewModel) — only an OK
 * "Watch from here" expand promotes a channel into the real player.
 */
class ZapViewModel(
    private val api: ChinoApi,
    private val streamTokens: StreamTokenManager,
    private val telemetry: Telemetry,
    private val baseUrl: String,
    private val random: Random = Random.Default,
    // Client-side segment prefetcher. Nullable so tests (and any
    // context-less construction) can omit it without a fake.
    private val prefetcher: ZapPrefetcher? = null,
    // Shared watchlist/likes cache so a Zap save shows up on Detail + the
    // watchlist surface. Nullable so context-less construction (tests) can
    // omit it; the save control is hidden when absent.
    private val userFlags: UserFlagsRepository? = null,
) : ViewModel() {
    private val prefs = ZapPreferences()
    private val feed = ZapFeed(
        api = api,
        // Pool-level exploit ranking is type-only (list items lack genres/cast).
        scoreItem = { item -> prefs.score(ZapFeatures(type = item.kind)) },
        random = random,
    )

    private val _state = MutableStateFlow<ZapUiState>(ZapUiState.Loading)
    val state: StateFlow<ZapUiState> = _state.asStateFlow()

    /** Item ids in >=1 of the user's lists — drives the channel-info save
     *  control's Bookmark/BookmarkCheck state. Empty flow when no userFlags
     *  cache is wired (tests / context-less construction). */
    val savedItems: StateFlow<Set<String>> =
        userFlags?.savedItems ?: MutableStateFlow(emptySet<String>()).asStateFlow()

    private val history = ArrayList<ZapEntry>()
    private var cursor = -1
    private var token: String = ""
    // Wall-clock ms when the current card became active (dwell measurement).
    private var cardActiveSince = 0L
    // Seek ratio pre-rolled per queued id so the window the prefetcher warms
    // matches the scene pushAndShow will land on. Consumed (and removed) when
    // the card is actually shown; defaults to a fresh roll if absent.
    private val preRolledRatios = HashMap<String, Double>()
    // Server-prewarm dedup — an id is POSTed at most once per session (mirrors
    // mobile's `prewarmed` set / web's prewarmedRef).
    private val prewarmed = HashSet<String>()

    init {
        telemetry.event("zap_session_start", extra = mapOf("source" to "rail"))
        // Warm the watchlist cache so the save control reflects existing
        // membership the moment a channel lands (non-gating, off the IO scope).
        userFlags?.let { flags -> viewModelScope.launch { runCatching { flags.warm() } } }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            token = withContext(Dispatchers.IO) { runCatching { streamTokens.valid() }.getOrDefault("") }
            withContext(Dispatchers.IO) { feed.load() }
            if (feed.empty && feed.queue.isEmpty()) {
                _state.value = ZapUiState.Empty
                return@launch
            }
            advanceToNext()
        }
    }

    /** D-pad DOWN — next channel. */
    fun zapNext() {
        if (_state.value !is ZapUiState.Active) return
        reportDwell()
        if (cursor < history.size - 1) {
            cursor += 1
            emitActive()
            prefetchUpcoming() // keep warming ahead even when replaying history
        } else {
            advanceToNext()
        }
    }

    /** D-pad UP — previous channel (replays a visited card, same scene). */
    fun zapPrev() {
        if (_state.value !is ZapUiState.Active || cursor <= 0) return
        reportDwell()
        cursor -= 1
        emitActive()
    }

    /** CENTER/expand → hand off to the full player. Returns the absolute
     *  resume position to pass as ?resume= (the playhead the teaser reached,
     *  or the scene start if it never advanced). */
    fun onExpand(currentPositionSec: Int): Int {
        val entry = currentEntry() ?: return 0
        prefs.update(entry.features, ZapPreferences.EXPAND)
        telemetry.event("zap_expand", itemId = entry.item.id, extra = baseExtra(entry))
        return if (currentPositionSec > 0) currentPositionSec else entry.seekSec
    }

    /** Toggle the currently-zapped item's membership in the DEFAULT watchlist.
     *  Mirrors chino-mobile's ZapScreenModel.onSaveToggle / web's onSaveToggle:
     *  a save bumps the preference vector by the SAVE strength and writes
     *  through to the watchlist (so it shows up on Detail), telemetry fires for
     *  both directions. Optimism + server reconciliation are handled inside
     *  [UserFlagsRepository.setWatchlist]; the savedItems flow drives the icon.
     *  No-op without a userFlags cache. */
    fun onSaveToggle() {
        val flags = userFlags ?: return
        val entry = currentEntry() ?: return
        val id = entry.item.id
        val nowSaved = id !in flags.savedItems.value
        if (nowSaved) prefs.update(entry.features, ZapPreferences.SAVE)
        flags.setWatchlist(id, nowSaved)
        telemetry.event(
            "zap_save",
            itemId = id,
            extra = baseExtra(entry) + ("saved" to nowSaved.toString()),
        )
    }

    /** The teaser played to the natural end of source. */
    fun onComplete() {
        val entry = currentEntry() ?: return
        prefs.update(entry.features, ZapPreferences.COMPLETE)
        telemetry.event("zap_complete", itemId = entry.item.id, extra = baseExtra(entry))
    }

    private fun advanceToNext() {
        // DETERMINISTIC card[0]: the very first card shown is the top-ranked
        // packaged candidate (no shuffle/epsilon), seeded at the fixed seek
        // ratio — IDENTICAL to what AppContainer.warmFirstZapCard prefetched, so
        // the app-start-warmed init + seek-window segments are the bytes this
        // first card plays. Cards index >= 1 keep the random sampling below.
        if (history.isEmpty()) {
            val top = feed.topCandidate()
            if (top != null) {
                pushAndShow(top, deterministic = true)
                feed.refill()
                return
            }
        }
        val next = feed.queue.firstOrNull()
        if (next == null) {
            feed.refill()
            val refilled = feed.queue.firstOrNull()
            if (refilled == null) {
                if (history.isEmpty()) _state.value = ZapUiState.Empty
                return
            }
            pushAndShow(refilled)
            return
        }
        pushAndShow(next)
        // Keep the queue topped up for the next zap.
        feed.refill()
    }

    /** Promote [item] to the active card. [deterministic] pins card[0] to the
     *  fixed first-card seek ratio (matching the app-start warm); otherwise the
     *  per-card random ratio is used. */
    private fun pushAndShow(item: Item, deterministic: Boolean = false) {
        feed.markShown(item.id)
        // For card[0] use the deterministic fixed ratio (no random roll, no
        // preRolled consume); otherwise reuse the ratio the prefetcher rolled for
        // this id (so the warmed window == the scene we land on), or roll fresh.
        val ratio = if (deterministic) {
            DETERMINISTIC_FIRST_CARD_RATIO
        } else {
            preRolledRatios.remove(item.id) ?: random.nextDouble()
        }
        val mid = pickZapMidpoint(item.durationMs, emptyList(), ratio)
        val entry = ZapEntry(item, ratio, mid.seekSec, mid.source)
        history.add(entry)
        cursor = history.size - 1
        emitActive()
        resolveFeatures(entry)
        // On each card settle, warm further ahead.
        prefetchUpcoming()
    }

    private fun emitActive() {
        val entry = currentEntry() ?: return
        cardActiveSince = System.currentTimeMillis()
        _state.value = ZapUiState.Active(
            item = entry.item,
            masterUrl = buildMasterUrl(entry.item.id),
            backdropUrl = buildArtworkUrl(entry.item.id, "backdrop"),
            posterUrl = buildArtworkUrl(entry.item.id, "poster"),
            seekSec = entry.seekSec,
            index = cursor,
        )
        telemetry.event(
            "zap_impression",
            itemId = entry.item.id,
            extra = baseExtra(entry) + ("seek_source" to entry.source.name.lowercase()),
        )
    }

    /** Fetch detail for genres/cast so dwell/expand signals learn the full
     *  feature vector (non-gating — playback already started on the percent
     *  midpoint). */
    private fun resolveFeatures(entry: ZapEntry) {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { runCatching { api.getItem(entry.item.id) }.getOrNull() } ?: return@launch
            val castNames = detail.cast
                .filter { it.role == null || it.role.equals("actor", ignoreCase = true) }
                .map { it.name }
            entry.features = ZapFeatures(type = detail.kind ?: entry.item.kind, genres = detail.genres, castNames = castNames)
        }
    }

    /** Classify how long the outgoing card was watched and learn from it. */
    private fun reportDwell() {
        val entry = currentEntry() ?: return
        if (cardActiveSince == 0L) return
        val dwellMs = System.currentTimeMillis() - cardActiveSince
        if (dwellMs < ZapPreferences.DWELL_NOISE_FLOOR_MS) return
        val (kind, strength) = when {
            dwellMs < ZapPreferences.FAST_SKIP_MS -> "zap_skip_fast" to ZapPreferences.SKIP_FAST
            dwellMs < ZapPreferences.DWELL_MS -> "zap_skip" to ZapPreferences.SKIP_NORMAL
            else -> "zap_dwell" to ZapPreferences.DWELL_LONG
        }
        prefs.update(entry.features, strength)
        telemetry.event(kind, itemId = entry.item.id, extra = baseExtra(entry) + ("dwell_ms" to dwellMs.toString()))
    }

    /** Build prefetch targets for the next not-yet-shown queued cards and hand
     *  them to the client prefetcher. Pre-rolls (and remembers) each card's seek
     *  ratio so the warmed segment window is the exact mid-scene the card will
     *  play. No-op when no prefetcher is wired (tests). The prefetcher dedups by
     *  id and caps how far ahead it actually warms. */
    private fun prefetchUpcoming() {
        if (token.isEmpty()) return
        val targets = feed.queue.take(PREFETCH_QUEUE_LOOKAHEAD).map { item ->
            val ratio = preRolledRatios.getOrPut(item.id) { random.nextDouble() }
            val seekSec = pickZapMidpoint(item.durationMs, emptyList(), ratio).seekSec
            ZapPrefetchTarget(itemId = item.id, masterUrl = buildMasterUrl(item.id), seekSec = seekSec)
        }
        // CLIENT-side warm: download the upcoming cards' init + seek-window
        // segments into the shared on-disk cache (no-op without a prefetcher).
        if (targets.isNotEmpty()) prefetcher?.prefetch(targets)
        // SERVER-side warm: tell chino-stream to warm the next card's transcode
        // pipeline so its first mid-scene segment is on disk before the user
        // surfs to it. Mirrors chino-mobile's ZapScreenModel.prewarmNext —
        // fire-and-forget, deduped per session, best-effort. We warm only the
        // SINGLE next card (targets[0]); the prefetcher's window math + the
        // server warm-only pool keep the rest cheap.
        targets.firstOrNull()?.let { prewarmNext(it) }
    }

    /** Fire-and-forget POST /v1/items/{id}/play/prewarm for the next upcoming
     *  Zap card so chino-stream warms the segment the teaser will start on.
     *  Deduped per session; the `t` seek hint + caps/q match the card's master
     *  URL so the right rung + mid-scene window is primed (mirrors mobile). */
    private fun prewarmNext(target: ZapPrefetchTarget) {
        if (!prewarmed.add(target.itemId)) return
        val caps = CodecCaps.queryParam.ifEmpty { null }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    api.prewarm(target.itemId, caps = caps, quality = TEASER_QUALITY, seekSec = target.seekSec)
                }
            }
        }
    }

    private fun currentEntry(): ZapEntry? = history.getOrNull(cursor)

    private fun baseExtra(entry: ZapEntry): Map<String, String> = buildMap {
        entry.features.type?.let { put("type", it) }
        if (entry.features.genres.isNotEmpty()) put("genres", entry.features.genres.joinToString(","))
    }

    /** Artwork URL for the cold-start layer — same `?stream=` token the master
     *  carries (no bearer needed; the HMAC token authorises image GETs too). */
    private fun buildArtworkUrl(id: String, kind: String): String =
        "$baseUrl/v1/items/$id/$kind?stream=$token"

    private fun buildMasterUrl(id: String): String {
        val caps = CodecCaps.queryParam
        return buildString {
            append("$baseUrl/v1/items/$id/play/master.m3u8?stream=$token")
            if (caps.isNotEmpty()) append("&caps=$caps")
            append("&q=$TEASER_QUALITY")
        }
    }

    override fun onCleared() {
        // Final dwell + session close so the funnel is complete.
        reportDwell()
        // Leaving Zap → stop downloading upcoming segments (the already-cached
        // bytes survive for a future session; only in-flight work is cancelled).
        prefetcher?.cancelAll()
        telemetry.event("zap_session_end")
    }

    companion object {
        private const val TEASER_QUALITY = "medium"

        /** How many upcoming queued cards to consider for prefetch each trigger.
         *  The prefetcher itself caps how many it actually warms (MAX_AHEAD) and
         *  dedups, so a generous lookahead here is cheap. */
        private const val PREFETCH_QUEUE_LOOKAHEAD = 4

        fun factory(container: AppContainer) = viewModelFactory {
            initializer {
                ZapViewModel(
                    api = container.chinoApi,
                    streamTokens = container.streamTokenManager,
                    telemetry = container.telemetry,
                    baseUrl = container.baseUrl,
                    prefetcher = container.zapPrefetcher,
                    userFlags = container.userFlags,
                )
            }
        }
    }
}
