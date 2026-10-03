package cloud.nalet.chino.tv.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Comma-separated codec-token list chino-stream's ParseCaps consumes —
 * video `avc` / `hvc` / `av1`, audio `aac` / `mp3` / `opus` / `ac3` /
 * `eac3`. Computed once per process; the MediaCodec list doesn't change at
 * runtime.
 *
 * Audio tokens matter as much as video ones: once a client sends caps,
 * ParseCaps starts from an EMPTY audio set, so a caps string with video
 * tokens only made every source's audio "not in the client's decoder set".
 * DecideWith then never chose passthrough — every non-packaged title went
 * through a remux that re-encoded its audio to stereo AAC, even AAC the TV
 * plays as is. Each audio token is advertised when MediaCodecList has a
 * decoder for it: AAC, MP3 and Opus come with the platform; AC-3 / E-AC-3
 * only where the device ships a Dolby decoder (most TVs), and passthrough to
 * an HDMI receiver isn't counted — without a decoder the server's stereo
 * AAC is the safe answer. ExoPlayer reads the real codec from the copied
 * segments (the copy master always says mp4a.40.2) and opens the matching
 * decoder. `vorbis` is not sent: Vorbis copied into fragmented MP4 is
 * non-standard and untried on TV, while the remux to AAC always plays.
 *
 * Each VIDEO token may carry an optional `:maxHeight` suffix = the
 * largest frame HEIGHT a HARDWARE decoder advertises for that codec
 * (`avc:2160`, `hvc:2160`, `av1:2160`). A bare token (no suffix) means
 * "supported, no known hardware height ceiling" and parses exactly as
 * the legacy form did. chino-stream uses the ceiling to route oversized
 * packages to the transcode ladder instead of letting the device drop
 * to its software decoder: a box advertises HEVC, the server serves a 4K
 * HEVC package, the HW decoder tops out at 1080, ExoPlayer silently
 * falls back to software and playback crawls (the SM-T500 Zap bug).
 *
 * Suffix is emitted only when a HARDWARE decoder exists for the codec
 * and reports a finite supported-height upper bound. Software-only
 * codecs stay bare — the server can transcode for them freely and we do
 * not want a SW ceiling masquerading as a HW one. Heights map to the
 * families chino-stream's codecFamily() returns: avc=h264, hvc=hevc,
 * av1=av1.
 *
 * Intentionally conservative:
 *  - `aacmc` (multi-channel AAC) never advertised — MSE-style pipelines
 *    reject 5.1/7.1 fmp4 segments; downmix-to-stereo on the server is
 *    the safe path (same reason chino-web omits it).
 *
 * HEVC + AV1 are advertised on every device (32-bit Sony BRAVIA
 * included). Verified against MT5895 BRAVIA_VH2 25-05-2026: hardware
 * decoder reports profile/level support up to Main10HDR10Plus /
 * High 5.2 / 4K@120 and decodes Main / L4.0 hev1 fmp4 from
 * shaka-packager cleanly, so the BRAVIA emits hvc:2160 (or higher) and
 * sees no behaviour change. A previous 32-bit ABI gate was removed once
 * per-profile MediaCodec probes were trustworthy.
 *
 * Shared by PlayerViewModel (which puts it on the master.m3u8 URL) and
 * DetailViewModel (which pre-warms chino-stream's /play/info on Detail
 * mount with the same caps so the pipeline decision matches).
 */
object CodecCaps {
    val tokens: List<String> by lazy {
        val mcl = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val infos = mcl.codecInfos

        fun has(mime: String): Boolean {
            val fmt = MediaFormat.createVideoFormat(mime, 1920, 1080)
            return runCatching { mcl.findDecoderForFormat(fmt) != null }.getOrDefault(false)
        }

        // Largest frame height any HARDWARE decoder advertises for this
        // MIME, or null when there's no HW decoder / no finite ceiling.
        // isHardwareAccelerated needs API 29; below that we treat any
        // non-"OMX.google."/"c2.android." (software) decoder as hardware.
        fun hwMaxHeight(mime: String): Int? {
            var best: Int? = null
            for (info in infos) {
                if (info.isEncoder) continue
                if (!info.supportedTypes.any { it.equals(mime, ignoreCase = true) }) continue
                val isSoftware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    !info.isHardwareAccelerated
                } else {
                    val n = info.name.lowercase()
                    n.startsWith("omx.google.") || n.startsWith("c2.android.")
                }
                if (isSoftware) continue
                val upper = runCatching {
                    info.getCapabilitiesForType(mime).videoCapabilities?.supportedHeights?.upper
                }.getOrNull() ?: continue
                if (upper > 0 && (best == null || upper > best!!)) best = upper
            }
            return best
        }

        fun token(name: String, mime: String): String {
            val h = hwMaxHeight(mime)
            return if (h != null) "$name:$h" else name
        }

        // Any decoder (hardware or software) for an audio MIME: audio decode
        // is cheap enough that a software decoder plays it fine.
        fun hasAudioDecoder(mime: String): Boolean = infos.any { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        val out = mutableListOf<String>()
        if (has("video/avc")) out += token("avc", "video/avc")
        if (has("video/hevc")) out += token("hvc", "video/hevc")
        if (has("video/av01")) out += token("av1", "video/av01")
        for ((name, mime) in AUDIO_TOKENS) {
            if (hasAudioDecoder(mime)) out += name
        }
        out
    }

    /** ParseCaps audio token → the decoder MIME type that backs it. */
    private val AUDIO_TOKENS = listOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
    )

    /** Comma-joined for the ?caps= query parameter; empty string when nothing supported. */
    val queryParam: String get() = tokens.joinToString(",")
}
