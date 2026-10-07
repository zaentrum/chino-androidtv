package cloud.nalet.chino.tv.data

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Comma-separated codec-token list chino-stream's ParseCaps consumes —
 * video `avc` / `hvc` / `av1`, audio `aac` / `mp3` / `opus` / `ac3` /
 * `eac3`. What the device decodes is read once per process; the MediaCodec
 * list doesn't change at runtime. Where the TV's audio goes is read at each
 * play ([play]): a receiver can be switched on between two.
 *
 * Audio tokens matter as much as video ones: once a client sends caps,
 * ParseCaps starts from an EMPTY audio set, so a caps string with video
 * tokens only made every source's audio "not in the client's decoder set".
 * DecideWith then never chose passthrough — every non-packaged title went
 * through a remux that re-encoded its audio to stereo AAC, even AAC the TV
 * plays as is. Each audio token is advertised when MediaCodecList has a
 * decoder for it: AAC, MP3 and Opus come with the platform; AC-3 only where
 * the device ships a Dolby decoder (most TVs). ExoPlayer reads the real codec
 * from the copied segments (the copy master always says mp4a.40.2) and opens
 * the matching decoder. `vorbis` is not sent: Vorbis copied into fragmented
 * MP4 is non-standard and untried on TV, while the remux to AAC always plays.
 *
 * `eac3` is sent where the device decodes E-AC-3, or where the audio output
 * as it is routed now takes 5.1 E-AC-3 as it is: Media3 passes it through to
 * a receiver or sound bar over HDMI (or to the TV's own decoder), as
 * Media3's AudioCapabilities read the route. With it chino-stream serves a
 * package's 5.1 E-AC-3 companions beside the stereo tracks, in one audio
 * group; the player starts on the 5.1 track where the output takes 5.1
 * ([PlayCaps.surround], SurroundAudio.kt), on the stereo one where it does
 * not. A device that neither decodes nor passes it through gets the stereo
 * tracks alone.
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
 * The player (a title's master and an extra's, /play/info, the next
 * episode's warm) and Detail (which pre-warms chino-stream's /play/info on
 * mount so the pipeline decision matches) send [play]'s caps. Zap sends
 * [zapQuery]: a card plays in stereo.
 */
object CodecCaps {
    /** What MediaCodecList decodes: its caps tokens (video with the hardware
     *  heights, then audio, no eac3), and whether it decodes E-AC-3. */
    private class Decoders(val tokens: List<String>, val eac3: Boolean)

    private val decoders: Decoders by lazy {
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
        fun hasAudioDecoder(mime: String): Boolean = infos.any { info: MediaCodecInfo ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }

        val out = mutableListOf<String>()
        if (has("video/avc")) out += token("avc", "video/avc")
        if (has("video/hevc")) out += token("hvc", "video/hevc")
        if (has("video/av01")) out += token("av1", "video/av01")
        for ((name, mime) in AUDIO_TOKENS) {
            if (hasAudioDecoder(mime)) out += name
        }
        Decoders(out, eac3 = hasAudioDecoder(MimeTypes.AUDIO_E_AC3))
    }

    /** ParseCaps audio token → the decoder MIME type that backs it. */
    private val AUDIO_TOKENS = listOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "ac3" to "audio/ac3",
    )

    /** 5.1 E-AC-3 as a package's companions are: what the output must take
     *  as it is for the player to pass it through. */
    private val SURROUND_EAC3: Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_E_AC3)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    /**
     * Whether the audio output, as it is routed now, takes 5.1 E-AC-3 as it
     * is: Media3's AudioCapabilities for the default route - HDMI to a
     * receiver or sound bar, or the TV's own output - say so, and Media3
     * passes it through there rather than decoding it. Binder calls: not on
     * the main thread.
     */
    fun outputTakesSurround(context: Context): Boolean = runCatching {
        AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            .isPassthroughPlaybackSupported(SURROUND_EAC3, AudioAttributes.DEFAULT)
    }.getOrDefault(false)

    /** The caps of a play, as the audio output is routed now: see [PlayCaps].
     *  Reads the route (binder calls): not on the main thread. */
    fun play(context: Context): PlayCaps {
        val surround = outputTakesSurround(context)
        val d = decoders
        return PlayCaps(query = capsQuery(d.tokens, eac3 = d.eac3 || surround), surround = surround)
    }

    /** Zap's caps: what the device decodes, without the surround codecs — a
     *  card plays in stereo, and chino-stream serves stereo alone for them. */
    val zapQuery: String by lazy { capsQuery(decoders.tokens, eac3 = false, stereo = true) }
}

/**
 * The caps of a play: [query] for the master's, /play/info's and /prewarm's
 * ?caps=, and whether the audio output takes 5.1 ([surround]): the player
 * starts on a language's 5.1 track only then.
 */
data class PlayCaps(val query: String, val surround: Boolean)

/** The tokens chino-stream reads as 5.1 audio a client decodes. */
private val SURROUND_TOKENS = setOf("ac3", "eac3")

/**
 * The ?caps= value, comma-joined: the [decoded] tokens in their order, then
 * eac3 where [eac3] says the device plays E-AC-3 (decodes it, or its output
 * takes it as it is). [stereo] leaves the surround codecs out, eac3 and ac3:
 * chino-stream then serves the stereo tracks alone. Empty when nothing is
 * decoded.
 */
fun capsQuery(decoded: List<String>, eac3: Boolean, stereo: Boolean = false): String {
    val tokens = decoded.filter { it != "eac3" } + if (eac3) listOf("eac3") else emptyList()
    return tokens.filter { !stereo || it !in SURROUND_TOKENS }.joinToString(",")
}
