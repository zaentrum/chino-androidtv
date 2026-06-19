package cloud.nalet.chino.tv.ui.zap

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UriUtil
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.util.Collections

/** One upcoming Zap card to warm: the per-device master URL + the scene the
 *  card will seek to. The window we fetch is [seekSec, seekSec + WINDOW_SEC]. */
data class ZapPrefetchTarget(val itemId: String, val masterUrl: String, val seekSec: Int)

/**
 * Client-side background prefetcher for upcoming Zap cards (TikTok/Instagram
 * feel): warms the on-disk [ZapCache] so swiping to the next card — and opening
 * Zap from a cold home screen — begins instantly instead of stalling on a
 * network round-trip.
 *
 * Per card it resolves, then writes into the shared cache:
 *   1. master.m3u8         (already carries device caps incl. per-codec HW
 *                           height, so the variant it resolves to is the
 *                           per-device-correct one — no hardcoded quality)
 *   2. the chosen variant playlist
 *   3. the init segment (EXT-X-MAP)
 *   4. ONLY the media segments covering the seek window [seekSec, seekSec +
 *      WINDOW_SEC] — NOT segment 0 (Zap drops into a random mid-scene) and NOT
 *      the whole movie.
 *
 * Good-citizen bounds:
 *   - prefetch only the next [MAX_AHEAD] not-yet-cached cards
 *   - only ~10-15s of segments per card (the seek window), never the full asset
 *   - concurrency capped to [MAX_CONCURRENT] segment downloads at once
 *   - DEDUP by item id (an id is warmed at most once per prefetcher lifetime)
 *   - cancellable: [cancelAll] kills in-flight work when leaving Zap, and the
 *     shared cache's LRU evictor keeps total on-disk bytes bounded.
 *
 * This COMPLEMENTS the server-side warm (prewarm POST + zap-feed) — the server
 * makes the asset playable; this downloads the warmed bytes onto the device.
 */
class ZapPrefetcher(
    context: Context,
    streamHttpClient: OkHttpClient,
    private val userAgent: String = "chino-tv/0.1 (Android; zap-prefetch)",
) {
    private val appCtx = context.applicationContext
    private val cacheFactory: CacheDataSource.Factory =
        ZapCache.dataSourceFactory(appCtx, streamHttpClient, userAgent)

    // Own scope so leaving Zap can cancel everything in flight at once.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // DEDUP: ids we have started (or finished) warming this lifetime. Never
    // re-warm — markShown in the feed guarantees an id is surfaced once, and
    // the cache holds the bytes for the rest of the session.
    private val warmed = Collections.synchronizedSet(HashSet<String>())

    // CONCURRENCY cap across ALL cards' segment writes.
    private val gate = Semaphore(MAX_CONCURRENT)

    // In-flight CacheWriters so cancelAll can interrupt a download mid-segment
    // (cancelling the coroutine alone won't break out of CacheWriter.cache()).
    private val inFlight = Collections.synchronizedSet(HashSet<CacheWriter>())

    /**
     * Prefetch the first [MAX_AHEAD] of [targets] that aren't already warmed.
     * Safe to call repeatedly (as the feed yields cards and on each settle) —
     * already-warmed ids are skipped, so re-issuing the upcoming window is cheap.
     */
    fun prefetch(targets: List<ZapPrefetchTarget>) {
        var launched = 0
        for (t in targets) {
            if (launched >= MAX_AHEAD) break
            if (t.itemId.isBlank() || t.masterUrl.isBlank()) continue
            if (!warmed.add(t.itemId)) continue // dedup: claim the id atomically
            launched++
            scope.launch { runCatching { warmCard(t) } }
        }
    }

    /** Cancel all in-flight prefetch when leaving Zap. The cache (and its bytes)
     *  survives — only the pending downloads stop. Idempotent. */
    fun cancelAll() {
        synchronized(inFlight) {
            for (w in inFlight) w.cancel()
            inFlight.clear()
        }
        // Cancel the children but keep the scope usable if Zap is re-entered
        // on the SAME prefetcher (the ViewModel re-creates one per session, so
        // this is mostly belt-and-braces).
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }
        warmed.clear()
    }

    private suspend fun warmCard(target: ZapPrefetchTarget) = coroutineScope {
        // 1. master.m3u8 → multivariant playlist (also caches the manifest bytes).
        val masterUri = Uri.parse(target.masterUrl)
        val master = readPlaylist(masterUri) as? HlsMultivariantPlaylist ?: return@coroutineScope

        // 2. Pick the variant ExoPlayer would: the master already encodes the
        //    device caps, so chino-stream advertises only per-device-correct
        //    variants. Take the first (single-variant is the common case); a
        //    multi-variant master leaves ABR to the player at watch time but we
        //    still warm one ladder rung's window.
        val variantUri = master.variants.firstOrNull()?.url
            ?: master.mediaPlaylistUrls.firstOrNull()
            ?: return@coroutineScope
        val media = readPlaylist(variantUri) as? HlsMediaPlaylist ?: return@coroutineScope

        // 3 + 4. The init segment (shared EXT-X-MAP) and the media segments that
        //        cover [seekSec, seekSec + WINDOW_SEC] around the mid-scene seek.
        val specs = ArrayList<DataSpec>()
        val seenInit = HashSet<String>()
        val windowStartUs = target.seekSec * 1_000_000L
        val windowEndUs = windowStartUs + WINDOW_SEC * 1_000_000L

        for (seg in media.segments) {
            val segStartUs = seg.relativeStartTimeUs
            val segEndUs = segStartUs + seg.durationUs
            // Overlap test against the seek window (NOT segment 0 / not the whole movie).
            if (segEndUs <= windowStartUs || segStartUs >= windowEndUs) continue

            seg.initializationSegment?.let { init ->
                val initUri = UriUtil.resolveToUri(media.baseUri, init.url).toString()
                if (seenInit.add(initUri)) specs += dataSpecFor(media.baseUri, init)
            }
            specs += dataSpecFor(media.baseUri, seg)
            if (specs.size >= MAX_SEGMENTS_PER_CARD) break // hard cap per card
        }

        // Write each spec into the shared cache, bounded by the global semaphore.
        for (spec in specs) {
            launch { gate.withPermit { cacheSpec(spec) } }
        }
    }

    /** Read + parse an HLS playlist THROUGH the cache factory (so the manifest
     *  bytes are cached too and the player reuses them). */
    private fun readPlaylist(uri: Uri): Any? {
        val ds = cacheFactory.createDataSource()
        return runCatching {
            ds.open(DataSpec(uri))
            val bytes = DataSourceUtil.readToEnd(ds)
            HlsPlaylistParser().parse(uri, ByteArrayInputStream(bytes))
        }.also { DataSourceUtil.closeQuietly(ds) }.getOrNull()
    }

    /** A byte-range-aware DataSpec for a segment/init, URL resolved against the
     *  playlist base. Honours EXT-X-BYTERANGE so we cache only the bytes the
     *  player will read, not a whole multi-segment container file. */
    private fun dataSpecFor(baseUri: String, seg: HlsMediaPlaylist.SegmentBase): DataSpec {
        val uri = UriUtil.resolveToUri(baseUri, seg.url)
        val builder = DataSpec.Builder().setUri(uri)
        if (seg.byteRangeLength != C.LENGTH_UNSET.toLong()) {
            builder.setPosition(seg.byteRangeOffset).setLength(seg.byteRangeLength)
        }
        return builder.build()
    }

    private fun cacheSpec(spec: DataSpec) {
        val ds = cacheFactory.createDataSource()
        val writer = CacheWriter(ds, spec, null, null)
        inFlight.add(writer)
        try {
            writer.cache() // blocks; reads cache-or-network, writes into the cache
        } catch (_: Throwable) {
            // Cancelled (leaving Zap), a dead channel (404 segment), or evicted
            // mid-write — non-fatal, the card just won't be fully pre-warmed.
        } finally {
            inFlight.remove(writer)
        }
    }

    companion object {
        /** Seconds past the seek point to warm — one card's "instant start" window. */
        private const val WINDOW_SEC = 12

        /** Next N not-yet-cached cards to warm ahead of the current one. */
        private const val MAX_AHEAD = 3

        /** Simultaneous segment downloads across all cards (be a good citizen). */
        private const val MAX_CONCURRENT = 2

        /** Belt-and-braces ceiling on segments per card if a variant uses very
         *  short target durations — the window math should already bound this. */
        private const val MAX_SEGMENTS_PER_CARD = 8
    }
}
