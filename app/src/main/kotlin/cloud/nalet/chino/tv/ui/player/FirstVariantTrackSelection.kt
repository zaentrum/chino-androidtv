package cloud.nalet.chino.tv.ui.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.TrackGroup
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.source.chunk.MediaChunk
import androidx.media3.exoplayer.source.chunk.MediaChunkIterator
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.collect.ImmutableList

/**
 * Adaptive video that starts on the master's first variant.
 *
 * chino-stream lists first the variant a client is to start on, and warms it
 * when the master is fetched; Zap's prefetch warms the same one. Media3 starts
 * where its bandwidth estimate points: AdaptiveTrackSelection's first pick is
 * the best rung under 70 % of the estimate, and on a fresh process that is the
 * meter's default for the network type and country (0.86-4.3 Mbit/s on Wi-Fi
 * and Ethernet). Below a 7.4 Mbit/s HEVC 1080p top rung that is the 720p one:
 * a cold start on a rung nobody warmed, with the warm spent on the other.
 *
 * So the first segment comes from the first variant — the group's first
 * track — when the track selector offers it (the device decodes it, it fits
 * the display) and it is not excluded, and from the next segment on Media3's
 * ABR decides as before: a slow network steps down from there. Only the
 * first start is pinned; where a seek later goes is ABR's. An on-the-fly
 * master has one variant, so nothing changes for it. chino-web starts hls.js
 * the same way (lib/hlsConfig.ts).
 */
class FirstVariantTrackSelection(
    group: TrackGroup,
    tracks: IntArray,
    type: Int,
    bandwidthMeter: BandwidthMeter,
    adaptationCheckpoints: List<AdaptationCheckpoint>,
    private val clock: Clock = Clock.DEFAULT,
) : AdaptiveTrackSelection(
    group,
    tracks,
    type,
    bandwidthMeter,
    DEFAULT_MIN_DURATION_FOR_QUALITY_INCREASE_MS.toLong(),
    DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS.toLong(),
    DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS.toLong(),
    DEFAULT_MAX_WIDTH_TO_DISCARD,
    DEFAULT_MAX_HEIGHT_TO_DISCARD,
    DEFAULT_BANDWIDTH_FRACTION,
    DEFAULT_BUFFERED_FRACTION_TO_LIVE_EDGE_FOR_QUALITY_INCREASE,
    adaptationCheckpoints,
    clock,
) {
    /** Where the first variant sits in this selection; C.INDEX_UNSET when
     *  the track selector left it out. */
    private val firstVariant = indexOf(0)
    private var starting = firstVariant != C.INDEX_UNSET

    override fun updateSelectedTrack(
        playbackPositionUs: Long,
        bufferedDurationUs: Long,
        availableDurationUs: Long,
        queue: List<MediaChunk>,
        mediaChunkIterators: Array<MediaChunkIterator>,
    ) {
        super.updateSelectedTrack(playbackPositionUs, bufferedDurationUs, availableDurationUs, queue, mediaChunkIterators)
        // Once a chunk is in, ABR goes on from the track it came from.
        if (starting && (queue.isNotEmpty() || isTrackExcluded(firstVariant, clock.elapsedRealtime()))) {
            starting = false
        }
    }

    override fun getSelectedIndex(): Int = if (starting) firstVariant else super.getSelectedIndex()

    override fun getSelectionReason(): Int =
        if (starting) C.SELECTION_REASON_INITIAL else super.getSelectionReason()

    /** AdaptiveTrackSelection's factory, but video starts on the first variant. */
    class Factory : AdaptiveTrackSelection.Factory() {
        override fun createAdaptiveTrackSelection(
            group: TrackGroup,
            tracks: IntArray,
            type: Int,
            bandwidthMeter: BandwidthMeter,
            adaptationCheckpoints: ImmutableList<AdaptationCheckpoint>,
        ): AdaptiveTrackSelection =
            if (group.type == C.TRACK_TYPE_VIDEO) {
                FirstVariantTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints)
            } else {
                super.createAdaptiveTrackSelection(group, tracks, type, bandwidthMeter, adaptationCheckpoints)
            }
    }
}

/** The track selector of the player and of Zap: Media3's own, with adaptive
 *  video starting on the first variant ([FirstVariantTrackSelection]). */
fun firstVariantTrackSelector(context: Context): DefaultTrackSelector =
    DefaultTrackSelector(context, FirstVariantTrackSelection.Factory())
