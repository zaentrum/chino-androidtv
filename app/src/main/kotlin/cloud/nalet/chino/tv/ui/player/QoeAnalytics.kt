package cloud.nalet.chino.tv.ui.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * What one player says of how it plays, handed to the screen's
 * [PlaybackQuality]: a Media3 AnalyticsListener for the start, the stalls,
 * the bandwidth estimate, the renditions and the errors, and a
 * TransferListener on the player's HTTP data source for the segments'
 * timings - when a request went out and when its first byte came, which
 * Media3's load events leave out. Never blocks playback: it only notes, and
 * the reports are queued (Telemetry.queue).
 *
 * The analytics callbacks come on the player's application thread (the main
 * one), the transfer callbacks on its loader threads; they share one record
 * per request ([requests]), which the data source fills in and the player
 * names.
 */
internal class QoeAnalytics(
    private val quality: PlaybackQuality,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : AnalyticsListener, TransferListener {

    /** Every request the data source has open or the player loads, by its
     *  address, until the player says its load is done. */
    private val requests = ConcurrentHashMap<String, SegmentFetch>()

    /** The segment fetches the player loads now, by its load task. */
    private val loads = HashMap<Long, SegmentFetch>()

    private var playWhenReady = false
    private var playing = false
    private var videoFormat: Format? = null

    // ---- The data source (loader threads) ----

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        // Requests the player never claims - playlists, subtitles - do not
        // pile up.
        if (requests.size > MAX_REQUESTS) requests.entries.removeIf { it.value.kind == null }
        val at = clock()
        val fetch = requests.getOrPut(key(dataSpec)) { SegmentFetch(startMs = at) }
        // A request that goes out again (a retry) is timed from now.
        fetch.startMs = at
        fetch.firstByteMs = null
        fetch.endMs = null
        fetch.bytes = 0
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        val fetch = requests[key(dataSpec)] ?: return
        fetch.firstByteMs = clock()
        fetch.totalBytes = source.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Length", ignoreCase = true) }
            ?.value?.firstOrNull()?.toLongOrNull()
    }

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        requests[key(dataSpec)]?.let { it.bytes += bytesTransferred }
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        requests[key(dataSpec)]?.endMs = clock()
    }

    // ---- The player (application thread) ----

    override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val kind = segmentKind(mediaLoadData) ?: return
        val now = clock()
        val fetch = requests.getOrPut(key(loadEventInfo.dataSpec)) { SegmentFetch(startMs = now) }
        fetch.kind = kind
        fetch.height = mediaLoadData.trackFormat?.height?.takeIf { kind != SegmentKind.AUDIO && it > 0 }
        fetch.mediaDurationMs = mediaDuration(mediaLoadData)
        loads[loadEventInfo.loadTaskId] = fetch
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        val fetch = loads.remove(loadEventInfo.loadTaskId) ?: return
        requests.remove(key(loadEventInfo.dataSpec))
        // Media3 counts what the load read; the data source, when it ended.
        fetch.bytes = loadEventInfo.bytesLoaded
        if (fetch.endMs == null) fetch.endMs = clock()
        quality.segmentLoaded(fetch, playing)
    }

    override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        forget(loadEventInfo)
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) = forget(loadEventInfo)

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) = quality.bandwidthEstimate(bitrateEstimate)

    override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) =
        quality.firstFrame(eventTime.currentPlaybackPositionMs, videoFormat?.let { rendition(it.height, it.bitrate) })

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        videoFormat = format
        quality.renditionOnScreen(rendition(format.height, format.bitrate))
    }

    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying) quality.playing()
    }

    override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) {
        this.playWhenReady = playWhenReady
        if (!playWhenReady) quality.paused()
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        // Waiting for data while it is to play: a stall, once it was playing.
        if (state == Player.STATE_BUFFERING && playWhenReady) {
            quality.waiting(eventTime.currentPlaybackPositionMs, inFlight())
        }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
            quality.seeked()
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        val failed = error.cause as? HttpDataSource.HttpDataSourceException
        quality.fatal(
            errorCode = error.errorCode,
            errorCodeName = error.errorCodeName,
            message = error.message,
            url = failed?.dataSpec?.uri?.toString(),
            httpStatus = (failed as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
            positionMs = eventTime.currentPlaybackPositionMs,
        )
    }

    private fun forget(info: LoadEventInfo) {
        loads.remove(info.loadTaskId)
        requests.remove(key(info.dataSpec))
    }

    /** The fetch loading now: the video's, else the audio's. */
    private fun inFlight(): SegmentFetch? =
        loads.values.lastOrNull { it.kind != SegmentKind.AUDIO } ?: loads.values.lastOrNull()

    internal companion object {
        const val MAX_REQUESTS = 64

        /** A request by its address and where it starts in the resource. */
        fun key(dataSpec: DataSpec): String = "${dataSpec.uri}#${dataSpec.position}"

        /** What Media3 loads, as a segment kind: the main variants' media and
         *  init segments are the video's (HLS's main track type is DEFAULT),
         *  an audio rendition's media segments the audio's; subtitles and
         *  playlists are none. */
        fun segmentKind(data: MediaLoadData): SegmentKind? {
            val video = data.trackType == C.TRACK_TYPE_DEFAULT || data.trackType == C.TRACK_TYPE_VIDEO
            return when {
                data.dataType == C.DATA_TYPE_MEDIA && video -> SegmentKind.VIDEO
                data.dataType == C.DATA_TYPE_MEDIA && data.trackType == C.TRACK_TYPE_AUDIO -> SegmentKind.AUDIO
                data.dataType == C.DATA_TYPE_MEDIA_INITIALIZATION && video -> SegmentKind.INIT
                else -> null
            }
        }

        fun mediaDuration(data: MediaLoadData): Long? =
            if (data.mediaStartTimeMs != C.TIME_UNSET && data.mediaEndTimeMs != C.TIME_UNSET) {
                (data.mediaEndTimeMs - data.mediaStartTimeMs).takeIf { it > 0 }
            } else {
                null
            }
    }
}
