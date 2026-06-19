package cloud.nalet.chino.tv.data

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build

/**
 * Comma-separated codec-token list chino-stream's ParseCaps consumes
 * (`avc` / `hvc` / `av1`). Computed once per process; the hardware
 * MediaCodec list doesn't change at runtime.
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

        val out = mutableListOf<String>()
        if (has("video/avc")) out += token("avc", "video/avc")
        if (has("video/hevc")) out += token("hvc", "video/hevc")
        if (has("video/av01")) out += token("av1", "video/av01")
        out
    }

    /** Comma-joined for the ?caps= query parameter; empty string when nothing supported. */
    val queryParam: String get() = tokens.joinToString(",")
}
