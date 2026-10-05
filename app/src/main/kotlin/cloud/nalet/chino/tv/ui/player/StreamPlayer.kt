package cloud.nalet.chino.tv.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import cloud.nalet.chino.tv.data.AppContainer

/*
 * What the TV's players of chino-stream's HLS share: the full player
 * (PlayerScreen) and the trailer's (ui/trailer) take their HTTP data source,
 * their retry rules and their ExoPlayer from here.
 */

/** What a player tells chino-stream it is. */
private const val STREAM_USER_AGENT = "chino-tv/0.1 (Android)"

/**
 * The players' HTTP data source: OkHttp over the container's stream client
 * ([AppContainer.streamHttpClient]) instead of DefaultHttpDataSource. The
 * master and every segment share its connection pool and TLS sessions, so
 * first-segment latency drops from ~300ms (a cold TLS handshake per fetch) to
 * <50ms once the pool is warm; its logging interceptor surfaces every segment
 * URL and response code in logcat, so chino-stream 404s are visible during
 * debug. The URLs carry the stream token, so no bearer is sent.
 */
fun streamDataSourceFactory(container: AppContainer): OkHttpDataSource.Factory =
    OkHttpDataSource.Factory(container.streamHttpClient).setUserAgent(STREAM_USER_AGENT)

/**
 * chino-stream's retry rules. Its transcode pipeline can drop a brief 404 on
 * the first request for an uncached segment (ffmpeg hasn't finished writing
 * it yet). The default policy retries 3× with linear backoff; this one 6×,
 * backing off exponentially on a segment's 404, so the second viewer of a
 * cold item doesn't see a fatal error mid-stream. A 404 on the master is
 * permanent and fails at once. Other failure classes keep the default.
 */
class StreamLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 6

    override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val ex = info.exception
        if (ex is HttpDataSource.InvalidResponseCodeException && ex.responseCode == 404) {
            // A 404 on the MANIFEST (.m3u8) is permanent — chino-stream has no
            // playback asset for this item ("no playback asset" / "file
            // missing on filesystem"). Retrying just stalls the viewer ~18s
            // before the same failure, so fail fast.
            if (ex.dataSpec.uri.toString().contains(".m3u8")) return C.TIME_UNSET
            // A SEGMENT 404 is transient (ffmpeg hasn't finished writing it).
            // Back off + retry: 500ms, 1.5s, 3s, 5s, 8s, then give up.
            return when (info.errorCount) {
                1 -> 500L
                2 -> 1_500L
                3 -> 3_000L
                4 -> 5_000L
                5 -> 8_000L
                else -> C.TIME_UNSET
            }
        }
        return super.getRetryDelayMsFor(info)
    }
}

/**
 * An ExoPlayer for chino-stream's HLS: its sources from [mediaSourceFactory]
 * (over [streamDataSourceFactory], retrying by [StreamLoadErrorPolicy]), and
 * adaptive video that starts on the master's first variant, the one
 * chino-stream warmed when the master was fetched
 * ([firstVariantTrackSelector]).
 */
fun streamPlayerBuilder(context: Context, mediaSourceFactory: MediaSource.Factory): ExoPlayer.Builder =
    ExoPlayer.Builder(context)
        .setMediaSourceFactory(mediaSourceFactory)
        .setTrackSelector(firstVariantTrackSelector(context))
