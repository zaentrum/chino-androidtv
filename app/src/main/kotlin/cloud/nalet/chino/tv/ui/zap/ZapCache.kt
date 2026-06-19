package cloud.nalet.chino.tv.ui.zap

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.io.File

/**
 * Process-wide on-disk cache for Zap channel-surf bytes.
 *
 * A single [SimpleCache] (256 MB, LRU-evicted) backs BOTH the ZapScreen player
 * and the [ZapPrefetcher]. Sharing one cache instance is mandatory — media3
 * forbids two SimpleCache instances over the same directory in one process, and
 * the whole point is that bytes the prefetcher writes for an upcoming card are
 * the exact bytes the player reads when that card settles (so the swipe-to-next
 * and the app-start open of Zap begin instantly off disk, no network round-trip).
 *
 * The cache key is derived from the segment URL (default CacheKeyFactory), so a
 * prefetched init/media segment is keyed identically whether it was written by
 * the prefetcher or read by the player. The master.m3u8 URL already carries the
 * per-device caps (incl. the per-codec HW height, e.g. hvc:1080), so each device
 * caches the variant correct FOR IT — a 1080-capped tablet caches the 720p
 * transcode, a 4K box caches 4K — without hardcoding any quality here.
 *
 * Bounded: [LeastRecentlyUsedCacheEvictor] keeps total on-disk size under
 * [MAX_BYTES]; once full, the least-recently-touched segments are evicted, so a
 * long surfing session never grows the cache without limit.
 */
object ZapCache {
    /** On-disk LRU ceiling. ~256 MB comfortably holds the init + a handful of
     *  ~10-15s seek windows for the 2-3 cards we ever prefetch ahead, with room
     *  for the segments the player itself pulls while watching. */
    private const val MAX_BYTES = 256L * 1024L * 1024L

    private const val CACHE_DIR = "zap-cache"

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var dbProvider: StandaloneDatabaseProvider? = null

    /** The shared cache, created lazily on first use under the app cache dir.
     *  Double-checked so the player and the prefetcher race to the SAME
     *  instance rather than two over the same directory (which would throw). */
    fun get(context: Context): SimpleCache {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val appCtx = context.applicationContext
            val dir = File(appCtx.cacheDir, CACHE_DIR)
            val provider = StandaloneDatabaseProvider(appCtx)
            val cache = SimpleCache(dir, LeastRecentlyUsedCacheEvictor(MAX_BYTES), provider)
            dbProvider = provider
            instance = cache
            return cache
        }
    }

    /**
     * A [CacheDataSource.Factory] that reads from the shared Zap cache and, on a
     * miss, falls through to the network ([upstreamClient]) AND writes the bytes
     * back into the cache. Used both by the ZapScreen player (so it reads
     * prefetched bytes) and by the [ZapPrefetcher]'s CacheWriter (so it warms
     * those same bytes ahead of time). No caps/quality decisions here — those are
     * already baked into the resolved segment URLs.
     */
    fun dataSourceFactory(context: Context, upstreamClient: OkHttpClient, userAgent: String): CacheDataSource.Factory {
        val upstream = OkHttpDataSource.Factory(upstreamClient).setUserAgent(userAgent)
        return CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(upstream)
            // Tolerate cache holes by reaching upstream rather than failing the
            // read — a partially-prefetched window must still play.
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
